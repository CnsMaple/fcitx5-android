package org.fcitx.fcitx5.android.data.clipboard

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import org.fcitx.fcitx5.android.common.ipc.IClipSyncProgress
import org.fcitx.fcitx5.android.common.ipc.IClipSyncProvider
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * In-app clipboard-sync provider (merged from the clipsync plugin). Implements the same
 * [IClipSyncProvider] contract the host driver expects, but runs in-process (no binder),
 * so [ClipSyncClient] talks to it directly.
 */
class LocalClipSyncProvider(private val context: Context) : IClipSyncProvider.Stub() {

    private val conf = ClipSettings(context)
    private val lastPush = AtomicReference<Bundle>(Bundle().apply { putBoolean("ok", true) })
    @Volatile private var currentClient: SyncClipboard? = null

    override fun isAvailable(): Boolean = conf.isConfigured()

    override fun getSettings(): Bundle = Bundle().apply {
        putString("url", conf.url)
        putString("user", conf.user)
        putString("pass", conf.pass)
    }

    override fun setSettings(s: Bundle?) {
        s ?: return
        s.getString("url")?.let { conf.url = it }
        s.getString("user")?.let { conf.user = it }
        s.getString("pass")?.let { conf.pass = it }
    }

    override fun pullMeta(): Bundle = try {
        val m = client().pullMeta()
        if (m == null) Bundle().apply { putString("type", "Text"); putString("text", "") }
        else Bundle().apply {
            putString("type", m.type)
            putString("hash", m.hash)
            putString("text", m.text)
            putBoolean("hasData", m.hasData)
            putString("dataName", m.dataName)
            putLong("size", m.size)
        }
    } catch (e: Exception) {
        ClipLog.log("clip", "pullMeta failed: ${e.javaClass.simpleName}: ${e.message}")
        Bundle().apply { putString("error", e.message ?: e.javaClass.simpleName) }
    }

    override fun pullData(dataName: String?): Bundle = try {
        val bytes = client().pullData(dataName ?: return Bundle().apply { putString("error", "no name") })
        val f = File(context.cacheDir, "dl_${System.nanoTime()}")
        f.writeBytes(bytes)
        val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        Bundle().apply { putParcelable("fd", pfd) }
    } catch (e: Exception) {
        ClipLog.log("clip", "pullData failed: ${e.javaClass.simpleName}: ${e.message}")
        Bundle().apply { putString("error", "${e.javaClass.simpleName}: ${e.message}") }
    }

    override fun push(
        type: String?, text: String?, dataName: String?, data: ByteArray?, hash: String?
    ): Bundle = try {
        val h = if (hash.isNullOrBlank())
            SyncClipboard.computeHash(type ?: "Text", text ?: "", dataName, data)
        else hash
        client().push(type ?: "Text", text ?: "", dataName, data, h)
        lastPush.set(Bundle().apply { putBoolean("ok", true) })
        Bundle().apply { putBoolean("ok", true) }
    } catch (e: Exception) {
        lastPush.set(Bundle().apply { putBoolean("ok", false); putString("error", e.message) })
        ClipLog.log("clip", "push failed: ${e.message}")
        Bundle().apply { putBoolean("ok", false); putString("error", e.message ?: "push failed") }
    }

    override fun lastPushResult(): Bundle = lastPush.get()

    override fun getDiagnostics(): String = ClipLog.dump()

    override fun pullDataWithProgress(dataName: String?, cb: IClipSyncProgress?): Bundle = try {
        val f = File(context.cacheDir, "dl_${System.nanoTime()}")
        client().pullDataStream(dataName ?: error("no name"), f) { done, total ->
            runCatching { cb?.onProgress(done, total) }
            true
        }
        val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        Bundle().apply { putParcelable("fd", pfd) }
    } catch (e: Exception) {
        ClipLog.log("clip", "pullData failed: ${e.javaClass.simpleName}: ${e.message}")
        Bundle().apply {
            putString("error", if (e.message == "cancelled") "已取消" else "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun cancelDownload() {
        runCatching { currentClient?.cancel() }
    }

    private fun client() = SyncClipboard(conf.url, conf.user, conf.pass).also { currentClient = it }
}
