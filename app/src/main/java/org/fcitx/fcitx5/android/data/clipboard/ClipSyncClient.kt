package org.fcitx.fcitx5.android.data.clipboard

import android.content.ClipDescription
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.common.ipc.IClipSyncProgress
import org.fcitx.fcitx5.android.common.ipc.IClipSyncProvider
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.utils.clipboardManager
import timber.log.Timber
import java.io.File

/**
 * Host-side clipboard sync driver. Owns clipboard read/write (only the IME
 * process may read the clipboard on Android 10+) and delegates WebDAV to the
 * clipsync plugin via [IClipSyncProvider].
 */
class ClipSyncClient(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var provider: IClipSyncProvider? = null
    private var connection: ServiceConnection? = null

    @Volatile private var lastRemoteHash = ""
    @Volatile private var lastLocalHash = ""
    @Volatile private var applyingRemote = false

    // auto-sync scheduling
    private var autoEnabled = false
    private var intervalSec = 30
    private val pollTask = object : Runnable {
        override fun run() {
            pullOnce()
            if (autoEnabled) main.postDelayed(this, intervalSec * 1000L)
        }
    }

    private val clipListener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
        if (applyingRemote) return@OnPrimaryClipChangedListener
        pushCurrentClip()
    }

    fun start() {
        current = this
        context.clipboardManager.addPrimaryClipChangedListener(clipListener)
        bind()
    }

    fun stop() {
        if (current === this) current = null
        runCatching { context.clipboardManager.removePrimaryClipChangedListener(clipListener) }
        main.removeCallbacks(pollTask)
        autoEnabled = false
        unbind()
    }

    /** Enable/disable periodic pull at [intervalSec]. Applies stored settings too. */
    fun applyAuto(enabled: Boolean, intervalSec: Int) {
        this.autoEnabled = enabled
        this.intervalSec = intervalSec.coerceAtLeast(5)
        main.removeCallbacks(pollTask)
        if (enabled) main.post(pollTask)
    }

    private fun bind() {
        provider = LocalClipSyncProvider(context)
    }

    private fun unbind() {
        provider = null
    }

    /** Pull remote and apply. [notify] toasts; [force] skips the "already synced" dedupe (manual). */
    fun pullOnce(notify: Boolean = false, force: Boolean = false) {
        val p = provider ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val meta = p.pullMeta()
                if (meta.getString("error") != null) return@runCatching
                val hash = meta.getString("hash").orEmpty()
                val type = meta.getString("type") ?: "Text"
                val text = meta.getString("text").orEmpty()
                val hasData = meta.getBoolean("hasData")
                val dataName = meta.getString("dataName")
                // dedupe only for auto sync; manual (force) always fetches
                if (!force && hash.isNotEmpty() && hash.equals(lastRemoteHash, true)) {
                    if (notify) toast("已是最新")
                    return@runCatching
                }
                // auto sync only pulls text; a non-text remote item is just announced for manual fetch
                if (!force && type != "Text") {
                    toast("云端有${describe(type, null, dataName)}，点工具栏手动获取")
                    return@runCatching
                }
                val applyText: String?
                val applyUri: Uri?
                val doneMsg: String
                if (hasData && dataName != null) {
                    val dl = if (notify) {
                        var cancelling = false
                        showSyncOverlay("准备下载…") {
                            cancelling = true
                            runCatching { provider?.cancelDownload() }
                        }
                        val cb = object : IClipSyncProgress.Stub() {
                            override fun onProgress(done: Long, total: Long) {
                                if (!cancelling) updateSyncOverlay(done, total)
                            }
                        }
                        val r = p.pullDataWithProgress(dataName, cb)
                        hideSyncOverlay()
                        r
                    } else {
                        p.pullData(dataName)
                    }
                    dl.getString("error")?.let { error(it) }
                    val bytes = readPfd(dl) ?: error("no data for $dataName")
                    if (type == "Text") {
                        applyText = String(bytes, Charsets.UTF_8); applyUri = null
                        doneMsg = "已下载到剪贴板：${describe("Text", applyText, null)}"
                    } else {
                        applyText = null; applyUri = saveToFile(dataName, bytes)
                        doneMsg = "已下载到 ${downloadDirLabel()}：${describe(type, null, dataName)}"
                    }
                } else {
                    applyText = text; applyUri = null
                    doneMsg = "已下载到剪贴板：${describe("Text", text, null)}"
                }
                withContext(Dispatchers.Main) {
                    // manual image/file fetch: show an action panel, leave the clipboard untouched
                    if (applyUri != null) {
                        lastRemoteHash = hash; lastLocalHash = hash
                        showClipActionPanel(applyUri, type, dataName, decodeThumb(applyUri))
                        return@withContext
                    }
                    // auto sync: don't overwrite the clipboard when the text is already identical
                    if (!force && applyText != null) {
                        val cur = context.clipboardManager.primaryClip?.getItemAt(0)
                            ?.coerceToText(context)?.toString()
                        if (cur == applyText) {
                            lastRemoteHash = hash; lastLocalHash = hash
                            return@withContext
                        }
                    }
                    applyingRemote = true
                    val clip = applyUri?.let {
                        ClipData.newUri(context.contentResolver, "clip", it)
                    } ?: ClipData.newPlainText("clip", applyText ?: "")
                    context.clipboardManager.setPrimaryClip(clip)
                    lastRemoteHash = hash
                    lastLocalHash = hash
                    main.postDelayed({ applyingRemote = false }, 400)
                    if (notify) toast(doneMsg)
                }
            }.onFailure {
                Timber.w(it, "pull failed")
                if (notify) toast("下载失败：${it.message ?: "?"}")
            }
        }
    }

    /** Display label for where downloaded files are saved. */
    private fun downloadDirLabel(): String {
        val d = AppPrefs.getInstance().internal.clipSyncDownloadDir.getValue()
        return if (d.isEmpty()) "应用缓存" else d
    }

    /** Push the current clipboard to the server. [notify] toasts what was sent. */
    fun pushCurrentClip(notify: Boolean = false) {
        val p = provider ?: return
        if (!runCatching { p.isAvailable }.getOrDefault(false)) {
            if (notify) toast("未配置 WebDAV")
            return
        }
        scope.launch(Dispatchers.IO) {
            runCatching {
                val clip = context.clipboardManager.primaryClip ?: return@launch
                val item = clip.getItemAt(0) ?: return@launch
                val uri = item.uri
                if (uri != null) {
                    if (!notify) return@launch  // auto sync only pushes text; images/files are pushed manually
                    val (name, bytes, mime) = readUri(uri) ?: return@launch
                    val type = if (mime?.startsWith("image/") == true ||
                        name.substringAfterLast('.', "").lowercase() in IMG_EXT
                    ) "Image" else "File"
                    val hash = SyncHash.fileHash(name, bytes)
                    if (hash.equals(lastLocalHash, true)) { if (notify) toast("内容未变化"); return@launch }
                    if (notify) toast("正在上传 ${describe(type, null, name)}（${bytes.size / 1024}KB）…")
                    report(p.push(type, name, name, bytes, hash), { lastLocalHash = hash },
                        "已上传 ${describe(type, null, name)}", notify)
                } else {
                    val text = item.coerceToText(context).toString()
                    if (text.isBlank()) { if (notify) toast("剪贴板为空"); return@launch }
                    val hash = SyncHash.textHash(text)
                    if (hash.equals(lastLocalHash, true)) { if (notify) toast("内容未变化"); return@launch }
                    if (notify) toast("正在上传 ${describe("Text", text, null)}…")
                    val r = if (text.length > MAX_INLINE) {
                        val name = "Text_${System.currentTimeMillis()}.txt"
                        p.push("Text", text.take(MAX_INLINE), name, text.toByteArray(), hash)
                    } else {
                        p.push("Text", text, null, null, hash)
                    }
                    report(r, { lastLocalHash = hash }, "已上传 ${describe("Text", text, null)}", notify)
                }
            }.onFailure {
                Timber.w(it, "push failed")
                if (notify) toast("上传失败：${it.message ?: "?"}")
            }
        }
    }

    /** Interpret a synchronous push result Bundle and toast accordingly. */
    private fun report(result: Bundle?, onOk: () -> Unit, okMsg: String, notify: Boolean) {
        val ok = result?.getBoolean("ok") == true
        if (ok) {
            onOk()
            if (notify) toast(okMsg)
        } else if (notify) {
            toast("上传失败：${result?.getString("error") ?: "?"}")
        }
    }

    /** Upload an explicitly picked file/image (from the toolbar picker). */
    fun uploadPicked(uri: Uri, type: String) {
        val p = provider ?: return
        if (!runCatching { p.isAvailable }.getOrDefault(false)) { toast("未配置 WebDAV"); return }
        scope.launch(Dispatchers.IO) {
            runCatching {
                val (name, bytes, _) = readUri(uri) ?: return@launch
                val hash = SyncHash.fileHash(name, bytes)
                toast("正在上传 ${describe(type, null, name)}（${bytes.size / 1024}KB）…")
                report(p.push(type, name, name, bytes, hash), { lastLocalHash = hash },
                    "已上传 ${describe(type, null, name)}", true)
            }.onFailure {
                Timber.w(it, "uploadPicked failed")
                toast("上传失败：${it.message ?: "?"}")
            }
        }
    }

    private fun toast(msg: String) {
        main.post { android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show() }
    }

    private fun svc() = context as? org.fcitx.fcitx5.android.input.FcitxInputMethodService
    private fun showSyncOverlay(label: String, onCancel: () -> Unit) =
        main.post { svc()?.withKeyboardWindow { showSyncProgress(label, onCancel) } }
    private fun updateSyncOverlay(done: Long, total: Long) =
        main.post { svc()?.withKeyboardWindow { updateSyncProgress(done, total) } }
    private fun hideSyncOverlay() =
        main.post { svc()?.withKeyboardWindow { hideSyncProgress() } }

    private fun readUri(uri: Uri): Triple<String, ByteArray, String?>? = runCatching {
        val mime = context.contentResolver.getType(uri)
        val name = queryName(uri) ?: uri.lastPathSegment ?: "clipboard.bin"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        Triple(name, bytes, mime)
    }.getOrNull()

    private fun queryName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun saveToFile(name: String, bytes: ByteArray): Uri = saveClipFile(context, name, bytes)

    /** Send an image into the focused editor via commitContent; fall back to copy if unsupported. */
    fun sendImage(uri: Uri, mime: String): Boolean {
        val svc = svc()
        val ic = svc?.currentInputConnection ?: run { toast("当前无输入框"); return false }
        val editorInfo = svc.currentInputEditorInfo ?: return false
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1)
            InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION else 0
        val ok = runCatching {
            val info = InputContentInfoCompat(uri, ClipDescription("image", arrayOf(mime)), null)
            InputConnectionCompat.commitContent(ic, editorInfo, info, flags, null)
        }.getOrDefault(false)
        if (!ok) {
            copyImage(uri, mime)
            toast("该应用不支持直接发送，已复制到剪贴板，请手动粘贴")
        }
        return ok
    }

    /** Copy an image uri onto the clipboard with an explicit mime. */
    fun copyImage(uri: Uri, mime: String) {
        // Build ClipData with the known mime instead of ClipData.newUri: FileProvider.getType
        // returns null for names with spaces / non-ascii / uppercase ext, which would leave the
        // clip as "*/*" and break the history thumbnail (isImage detection).
        context.clipboardManager.setPrimaryClip(ClipData("image", arrayOf(mime), ClipData.Item(uri)))
        toast("已复制图片")
    }

    /** Share an image/file via the system chooser. */
    fun shareUri(uri: Uri, mime: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(
                Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { toast("无法分享") }
    }

    private fun mimeFor(name: String?, type: String): String {
        if (type != "Image") return "application/octet-stream"
        return when (name?.substringAfterLast('.', "")?.lowercase()) {
            "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"
            "bmp" -> "image/bmp"; else -> "image/jpeg"
        }
    }

    /** Show the copy/send/share/cancel panel over the keyboard for a fetched image/file. */
    private fun showClipActionPanel(uri: Uri, type: String, name: String?, bmp: android.graphics.Bitmap?) {
        val isImage = type == "Image"
        val mime = mimeFor(name, type)
        svc()?.withKeyboardWindow {
            showClipActionPanel(
                bmp, isImage,
                onCopy = { this@ClipSyncClient.copyImage(uri, mime); hideClipActionPanel() },
                onSend = { if (this@ClipSyncClient.sendImage(uri, mime)) hideClipActionPanel() },
                onShare = { this@ClipSyncClient.shareUri(uri, mime); hideClipActionPanel() },
                onCancel = { hideClipActionPanel() },
            )
        }
    }

    private fun decodeThumb(uri: Uri): android.graphics.Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 800 || bounds.outHeight / sample > 800) sample *= 2
        context.contentResolver.openInputStream(uri)?.use { ins ->
            BitmapFactory.decodeStream(ins, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()

    /** Show the clipboard image preview panel (Send / Cancel) over the keyboard. */
    fun showClipboardImagePanel(uriStr: String, mime: String) {
        val uri = Uri.parse(uriStr)
        val bmp = decodeThumb(uri)
        svc()?.withKeyboardWindow {
            showImagePreviewPanel(
                bmp,
                onSend = { if (this@ClipSyncClient.sendImage(uri, mime)) hideClipActionPanel() },
                onCancel = { hideClipActionPanel() },
            )
        }
    }

    /** Read a byte payload returned via a ParcelFileDescriptor (binder-safe for large files). */
    @Suppress("DEPRECATION")
    private fun readPfd(b: Bundle): ByteArray? =
        (b.getParcelable("fd") as? android.os.ParcelFileDescriptor)?.use {
            java.io.FileInputStream(it.fileDescriptor).readBytes()
        }

    companion object {
        const val MAX_INLINE = 10240
        val IMG_EXT = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")

        /**
         * Persist a downloaded non-text clip to the user-chosen directory (or the
         * app cache when unset) and return a shareable FileProvider URI.
         */
        fun saveClipFile(context: Context, name: String, bytes: ByteArray): Uri {
            val chosen = AppPrefs.getInstance().internal.clipSyncDownloadDir.getValue()
            val dir = if (chosen.isNotEmpty()) File(chosen)
                      else File(context.cacheDir, "clip")
            runCatching { dir.mkdirs() }
            val target = File(dir, File(name).name)
            target.writeBytes(bytes)
            return FileProvider.getUriForFile(context, "${context.packageName}.clipprovider", target)
        }

        /** the live instance, so the settings page can drive manual sync / apply auto */
        @Volatile
        var current: ClipSyncClient? = null

        /** human label for a synced item: text -> truncated preview; file/image -> type + name */
        fun describe(type: String, text: String?, name: String?): String = when (type) {
            "Text" -> {
                val t = text.orEmpty().replace("\n", " ").trim()
                if (t.length > 24) "文本「${t.take(24)}…」" else "文本「$t」"
            }
            "Image" -> "图片「${name ?: "?"}」"
            "File" -> "文件「${name ?: "?"}」"
            else -> name ?: type
        }

        /** Launch the system document picker from the IME to upload a file/image. */
        fun pick(context: Context, type: String) {
            context.startActivity(
                android.content.Intent(context, ClipPickerActivity::class.java)
                    .putExtra(ClipPickerActivity.EXTRA_TYPE, type)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

private object SyncHash {
    fun textHash(text: String) = sha256(text.toByteArray())
    fun fileHash(name: String, bytes: ByteArray) = sha256("$name|${sha256(bytes)}".toByteArray())
    private fun sha256(b: ByteArray) =
        java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02X".format(it) }
}
