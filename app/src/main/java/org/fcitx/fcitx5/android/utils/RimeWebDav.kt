package org.fcitx.fcitx5.android.utils

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.syncRimeUserData
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Read rime WebDAV config (url/user/pass) from app prefs. */
private fun readRimeWebDavConfig(): Triple<String, String, String> {
    val p = AppPrefs.getInstance().internal
    return Triple(
        p.rimeWebDavUrl.getValue().trim(),
        p.rimeWebDavUser.getValue().trim(),
        p.rimeWebDavPass.getValue(),
    )
}

private val rimeScope = kotlinx.coroutines.CoroutineScope(
    kotlinx.coroutines.SupervisorJob() + Dispatchers.Main
)

/** Cancellation flag for the running rime WebDAV pull/push loop. */
val rimeCancel = AtomicBoolean(false)

// number of history snapshots kept locally and remotely
private const val RIME_KEEP = 10

private fun rimeToast(context: Context, msg: String) {
    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
}

private fun rimeUserDir(context: Context): File {
    val custom = AppPrefs.getInstance().internal.rimeUserDataDir.getValue()
    return if (custom.isEmpty())
        File(context.getExternalFilesDir(null) ?: context.filesDir, "data/rime")
    else File(custom)
}

/** Snapshot root chosen by the user; holds current/ and history/<ts>/. */
private fun backupRoot(context: Context): File? {
    val dir = AppPrefs.getInstance().internal.rimeBackupDir.getValue()
    if (dir.isEmpty()) {
        rimeToast(context, context.getString(R.string.rime_backup_need_dir))
        return null
    }
    return File(dir)
}

private fun currentDir(root: File) = File(root, "current")
private fun historyDir(root: File) = File(root, "history")

private fun backupPatterns(): List<String> =
    AppPrefs.getInstance().internal.rimeBackupPatterns.getValue()
        .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

/**
 * Run a rime flow. Shows a progress dialog when [context] is an Activity
 * (settings page); otherwise (IME service context) just toasts the result.
 */
private fun runRimeFlow(context: Context, title: Int, block: suspend (phase: (Int) -> Unit) -> Unit) {
    rimeCancel.set(false)
    val activity = context as? ComponentActivity
    var dialog: AlertDialog? = null
    var text: TextView? = null
    if (activity != null) {
        val dp = activity.resources.displayMetrics.density
        text = TextView(activity).apply {
            setText(title); setPadding((12 * dp).toInt(), 0, 0, 0)
        }
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
            addView(loadingSpinner(activity))
            addView(text)
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle(title).setView(layout).setCancelable(false)
            .setNegativeButton(R.string.clip_sync_cancel) { _, _ -> rimeCancel.set(true) }
            .show()
    }
    rimeScope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching { block { phase -> text?.post { text.setText(phase) } } }
        }
        if (activity != null && !activity.isFinishing && !activity.isDestroyed) dialog?.dismiss()
        rimeToast(context, when {
            rimeCancel.get() -> context.getString(R.string.rime_webdav_cancelled)
            result.isSuccess -> context.getString(R.string.rime_webdav_done)
            else -> context.getString(R.string.rime_webdav_failed, rimeFailReason(context, result.exceptionOrNull()!!))
        })
    }
}

// Map a flow failure to a Chinese reason; never surface the raw (English) exception text.
private fun rimeFailReason(context: Context, t: Throwable): String {
    val msg = t.message ?: ""
    val code = Regex("HTTP (\\d{3})").find(msg)?.groupValues?.get(1)
    return when {
        t is java.net.UnknownHostException -> context.getString(R.string.rime_webdav_err_host)
        code != null -> when (code) {
            "401", "403" -> context.getString(R.string.rime_webdav_err_auth)
            "404" -> context.getString(R.string.rime_webdav_err_notfound)
            else -> context.getString(R.string.rime_webdav_err_http, code)
        }
        msg.contains("empty") -> context.getString(R.string.rime_webdav_err_empty)
        t is java.io.IOException -> context.getString(R.string.rime_webdav_err_net)
        else -> context.getString(R.string.rime_webdav_err_generic)
    }
}

// ---- local snapshot helpers (mirror rime_tool.py) ----

private fun timestamp(): String =
    SimpleDateFormat("yyyy.MM.dd.HH.mm.ss", Locale.US).format(Date())

private fun hasContent(dir: File): Boolean =
    dir.exists() && dir.walkTopDown().any { it.isFile }

private fun pruneHistory(history: File, keep: Int) {
    if (!history.exists()) return
    val dirs = history.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: return
    dirs.dropLast(keep).forEach { it.deleteRecursively() }
}

/** Move current/ into history/<ts> before an overwrite, then prune; recreate empty current/. */
private fun rotateLocal(current: File, history: File, keep: Int) {
    if (hasContent(current)) {
        history.mkdirs()
        val base = timestamp()
        var ts = base
        var n = 0
        while (File(history, ts).exists()) { n += 1; ts = "$base.$n" }
        current.renameTo(File(history, ts))
    }
    pruneHistory(history, keep)
    current.mkdirs()
}

private fun relOf(root: File, f: File): String =
    f.absolutePath.removePrefix(root.absolutePath).trimStart(File.separatorChar)
        .replace(File.separatorChar, '/')

/** Convert a glob (with **, *, ?) to a regex anchored to a relative path. */
private fun globToRegex(pattern: String): Regex {
    val sb = StringBuilder("^")
    var i = 0
    while (i < pattern.length) {
        when (val c = pattern[i]) {
            '*' -> {
                if (i + 1 < pattern.length && pattern[i + 1] == '*') {
                    if (i + 2 < pattern.length && pattern[i + 2] == '/') {
                        sb.append("(?:.*/)?"); i += 3
                    } else { sb.append(".*"); i += 2 }
                } else { sb.append("[^/]*"); i += 1 }
            }
            '?' -> { sb.append("[^/]"); i += 1 }
            '.', '(', ')', '+', '|', '^', '$', '{', '}', '[', ']', '\\' -> {
                sb.append('\\').append(c); i += 1
            }
            else -> { sb.append(c); i += 1 }
        }
    }
    sb.append('$')
    return Regex(sb.toString())
}

/** Files under [source] whose relative path matches any pattern; returns (file, relPath). */
private fun collectMatching(source: File, patterns: List<String>): List<Pair<File, String>> {
    if (!source.isDirectory) return emptyList()
    val regexes = patterns.map { globToRegex(it) }
    return source.walkTopDown().filter { it.isFile }.map { it to relOf(source, it) }
        .filter { (_, rel) -> regexes.any { it.matches(rel) } }
        .toList()
}

/** 备份: rotate current/ -> history/, then copy matching files from rime dir into current/. No native sync. */
fun rimeBackup(context: Context) {
    val root = backupRoot(context) ?: return
    val current = currentDir(root)
    val history = historyDir(root)
    val source = rimeUserDir(context)
    if (!source.isDirectory) { rimeToast(context, context.getString(R.string.rime_backup_no_source)); return }
    val patterns = backupPatterns()
    if (patterns.isEmpty()) { rimeToast(context, context.getString(R.string.rime_backup_need_patterns)); return }
    runRimeFlow(context, R.string.rime_backup) {
        rotateLocal(current, history, RIME_KEEP)
        var copied = 0
        for ((f, rel) in collectMatching(source, patterns)) {
            if (rimeCancel.get()) error("已取消")
            val dst = File(current, rel)
            dst.parentFile?.mkdirs()
            f.copyTo(dst, overwrite = true)
            copied += 1
        }
        if (copied == 0) error(context.getString(R.string.rime_backup_no_match))
    }
}

/** 还原: copy current/ -> rime user dir (no rotation, no sync). */
fun rimeRestore(context: Context) {
    val root = backupRoot(context) ?: return
    val current = currentDir(root)
    val target = rimeUserDir(context)
    if (!hasContent(current)) { rimeToast(context, context.getString(R.string.rime_backup_current_empty)); return }
    if (!target.isDirectory) { rimeToast(context, context.getString(R.string.rime_backup_no_source)); return }
    runRimeFlow(context, R.string.rime_restore) {
        current.walkTopDown().filter { it.isFile }.forEach { f ->
            if (rimeCancel.get()) error("已取消")
            val dst = File(target, relOf(current, f))
            dst.parentFile?.mkdirs()
            f.copyTo(dst, overwrite = true)
        }
    }
}

/** 上传: current/ -> WebDAV, rotating remote history first. */
fun rimePush(context: Context) {
    val (url, user, pass) = readRimeWebDavConfig()
    if (url.isEmpty()) { rimeToast(context, context.getString(R.string.rime_webdav_need_url)); return }
    val root = backupRoot(context) ?: return
    val current = currentDir(root)
    if (!hasContent(current)) { rimeToast(context, context.getString(R.string.rime_backup_current_empty)); return }
    runRimeFlow(context, R.string.rime_push) { phase ->
        phase(R.string.rime_webdav_pushing)
        RimeWebDav(url, user, pass).pushCurrent(current)
    }
}

/** 下载: WebDAV current/ -> local current/, rotating local history first. */
fun rimePull(context: Context) {
    val (url, user, pass) = readRimeWebDavConfig()
    if (url.isEmpty()) { rimeToast(context, context.getString(R.string.rime_webdav_need_url)); return }
    val root = backupRoot(context) ?: return
    runRimeFlow(context, R.string.rime_pull) { phase ->
        phase(R.string.rime_webdav_pulling)
        RimeWebDav(url, user, pass).pullCurrent(currentDir(root), historyDir(root))
    }
}

/**
 * 立即同步: pull -> restore -> native sync -> backup -> push, sequentially in one flow.
 * Pulls the cloud snapshot, lays it over the rime user dir, runs a native sync,
 * snapshots the (now-merged) user dir back into current/, then pushes to the cloud.
 */
fun rimeSyncNow(context: Context) {
    val (url, user, pass) = readRimeWebDavConfig()
    if (url.isEmpty()) { rimeToast(context, context.getString(R.string.rime_webdav_need_url)); return }
    val root = backupRoot(context) ?: return
    val current = currentDir(root)
    val history = historyDir(root)
    val source = rimeUserDir(context)
    if (!source.isDirectory) { rimeToast(context, context.getString(R.string.rime_backup_no_source)); return }
    val patterns = backupPatterns()
    if (patterns.isEmpty()) { rimeToast(context, context.getString(R.string.rime_backup_need_patterns)); return }
    val conn = FcitxDaemon.getFirstConnectionOrNull()
    val client = RimeWebDav(url, user, pass)
    runRimeFlow(context, R.string.rime_sync_now) { phase ->
        phase(R.string.rime_webdav_pulling)
        client.pullCurrent(current, history)
        phase(R.string.rime_restore)
        current.walkTopDown().filter { it.isFile }.forEach { f ->
            if (rimeCancel.get()) error("已取消")
            val dst = File(source, relOf(current, f))
            dst.parentFile?.mkdirs()
            f.copyTo(dst, overwrite = true)
        }
        phase(R.string.rime_webdav_syncing)
        conn?.runOnReady { syncRimeUserData() }
        delay(1500)
        phase(R.string.rime_backup)
        rotateLocal(current, history, RIME_KEEP)
        for ((f, rel) in collectMatching(source, patterns)) {
            if (rimeCancel.get()) error("已取消")
            val dst = File(current, rel)
            dst.parentFile?.mkdirs()
            f.copyTo(dst, overwrite = true)
        }
        phase(R.string.rime_webdav_pushing)
        client.pushCurrent(current)
    }
}

/** 单同步: native rime sync only (no WebDAV). */
fun rimePlainSync(context: Context) {
    val conn = FcitxDaemon.getFirstConnectionOrNull() ?: return
    runRimeFlow(context, R.string.rime_webdav_plain_sync) {
        conn.runOnReady { syncRimeUserData() }
        delay(1200)
    }
}

/** Toolbar/native sync entry: plain sync only (WebDAV snapshot ops live in the settings page). */
fun rimeSyncWithFallback(context: Context) = rimePlainSync(context)

/**
 * WebDAV client for rime snapshots. Remote layout mirrors the desktop tool:
 * <url>/current/ (latest snapshot) and <url>/history/<ts>/ (<= RIME_KEEP).
 *  - pullCurrent: rotate local current -> history, then download remote current/ into it.
 *  - pushCurrent: MOVE remote current -> history/<ts> (server-side), prune, then upload local current.
 * Neither performs a native rime sync.
 */
class RimeWebDav(
    private val baseUrl: String,
    private val user: String,
    private val pass: String,
) {
    data class Item(val path: String, val isDirectory: Boolean)

    private val root = baseUrl.trim().trimEnd('/')
    private val live = "current"
    private val hist = "history"

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun url(rel: String): String =
        "$root/" + rel.split('/').filter { it.isNotEmpty() }.joinToString("/") {
            java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }

    private fun Request.Builder.auth(): Request.Builder =
        addHeader("Authorization", Credentials.basic(user, pass, Charsets.UTF_8))

    /** PROPFIND Depth:infinity under [dirRel]; items with paths relative to root. */
    private fun listDir(dirRel: String): List<Item> {
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:">""" +
                """<d:prop><d:resourcetype/></d:prop></d:propfind>"""
        val req = Request.Builder().auth().url(url(dirRel))
            .method("PROPFIND", body.toRequestBody("application/xml".toMediaType()))
            .addHeader("Depth", "infinity").build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use emptyList()
            val xml = resp.body.string()
            val base = java.net.URI(root).path?.trimEnd('/') ?: return@use emptyList()
            Regex("<(?:[\\w.-]+:)?href>([^<]+)</(?:[\\w.-]+:)?href>")
                .findAll(xml)
                .map { java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") }
                .mapNotNull { href ->
                    val path = if (href.startsWith("http://") || href.startsWith("https://"))
                        java.net.URI(href).path else href
                    if (!path.startsWith("$base/")) null else path.removePrefix("$base/")
                }
                .filter { it.isNotBlank() }
                .map { Item(it, it.endsWith("/")) }
                .toList()
        }
    }

    private fun mkcol(rel: String) {
        val req = Request.Builder().auth().url(url(rel)).method("MKCOL", null).build()
        runCatching { http.newCall(req).execute().use { } }
    }

    private fun ensureRemoteDirs(fileRel: String) {
        val parts = fileRel.split('/')
        for (i in 1 until parts.size) mkcol(parts.subList(0, i).joinToString("/"))
    }

    private fun deleteRemote(rel: String) {
        val req = Request.Builder().auth().url(url(rel)).delete().build()
        runCatching { http.newCall(req).execute().use { } }
    }

    private fun moveRemote(fromRel: String, toRel: String) {
        val req = Request.Builder().auth().url(url(fromRel)).method("MOVE", null)
            .addHeader("Destination", url(toRel)).addHeader("Overwrite", "F").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 201 && resp.code != 204)
                error("MOVE $fromRel -> $toRel: HTTP ${resp.code}")
        }
    }

    /** Direct child names of a remote dir (empty if it doesn't exist yet). */
    private fun remoteChildren(dirRel: String): List<String> {
        val items = listDir(dirRel)
        if (items.isEmpty()) return emptyList()
        val prefix = "$dirRel/"
        return items.map { it.path }.filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix).trimStart('/') }
            .filter { it.isNotEmpty() }
    }

    private fun remoteHistoryNames(): List<String> =
        remoteChildren(hist).filter { it.endsWith("/") }
            .map { it.trimEnd('/').substringBefore('/') }
            .filter { it.isNotEmpty() }.distinct().sorted()

    private fun pruneRemote(keep: Int) {
        remoteHistoryNames().dropLast(keep).forEach { deleteRemote("$hist/$it") }
    }

    /** Server-side rotation of remote current/ into history/<ts>, then prune. */
    private fun remoteRotate() {
        if (remoteChildren(live).none { !it.endsWith("/") }) return
        ensureRemoteDirs("$hist/x")
        val base = timestamp()
        var ts = base
        var n = 0
        while (remoteHistoryNames().contains(ts)) { n += 1; ts = "$base.$n" }
        moveRemote(live, "$hist/$ts")
        pruneRemote(RIME_KEEP)
    }

    /** Download remote current/ into [localCurrent], rotating local history first. */
    suspend fun pullCurrent(localCurrent: File, localHistory: File) = withContext(Dispatchers.IO) {
        if (remoteChildren(live).none { !it.endsWith("/") }) error("remote current/ is empty")
        rotateLocal(localCurrent, localHistory, RIME_KEEP)
        listDir(live).filterNot { it.isDirectory }.forEach { item ->
            if (rimeCancel.get()) error("已取消")
            val rel = item.path.removePrefix("$live/").trimStart('/')
            if (rel.isEmpty()) return@forEach
            val req = Request.Builder().auth().url(url(item.path)).get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("GET ${item.path}: HTTP ${resp.code}")
                val target = secureChild(localCurrent, rel) ?: return@use
                target.parentFile?.mkdirs()
                target.outputStream().use { resp.body.byteStream().copyTo(it) }
            }
        }
    }

    /** Upload local [localCurrent] to remote current/, rotating remote history first. */
    suspend fun pushCurrent(localCurrent: File) = withContext(Dispatchers.IO) {
        remoteRotate()
        ensureRemoteDirs("$live/x")
        localCurrent.walkTopDown().filter { it.isFile }.forEach { f ->
            if (rimeCancel.get()) error("已取消")
            val dest = "$live/${relOf(localCurrent, f)}"
            ensureRemoteDirs(dest)
            val req = Request.Builder().auth().url(url(dest))
                .put(f.readBytes().toRequestBody("application/octet-stream".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 204) error("PUT $dest: HTTP ${resp.code}")
            }
        }
    }

    /** Guard against path traversal in remote hrefs. */
    private fun secureChild(rootDir: File, rel: String): File? {
        val f = File(rootDir, rel)
        val canon = f.canonicalPath
        return if (canon == rootDir.canonicalPath ||
            canon.startsWith(rootDir.canonicalPath + File.separator)
        ) f else null
    }
}
