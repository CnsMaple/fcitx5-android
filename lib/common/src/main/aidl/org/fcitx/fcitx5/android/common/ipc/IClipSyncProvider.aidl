// Clipboard sync provider over the SyncClipboard WebDAV protocol.
// The plugin performs WebDAV HTTP; the host app owns clipboard read/write
// (Android 10+ blocks clipboard access from background processes).
// New methods MUST be appended: transaction codes are positional.
package org.fcitx.fcitx5.android.common.ipc;

import android.os.Bundle;
import org.fcitx.fcitx5.android.common.ipc.IClipSyncProgress;

interface IClipSyncProvider {
    boolean isAvailable();

    // {url, user, pass, auto(bool as int), interval}
    Bundle getSettings();

    // Synchronous so a subsequent read sees the just-written values (no race).
    void setSettings(in Bundle settings);

    // GET {base}/SyncClipboard.json -> {type,hash,text,hasData,dataName,size} or {error}
    Bundle pullMeta();

    // GET {base}/file/{dataName} -> {data: byte[]} or {error}
    Bundle pullData(String dataName);

    // Upload: if data != null PUT {base}/file/{dataName} first, then PUT SyncClipboard.json.
    // type: Text|Image|File|Group. hash may be empty (plugin computes it).
    // Synchronous: returns {ok: bool, error: String}.
    Bundle push(String type, String text, String dataName, in byte[] data, String hash);

    // Result of the last push: {ok: bool, error: String}
    Bundle lastPushResult();

    String getDiagnostics();

    // Streamed download with progress callbacks; returns {fd} or {error}.
    Bundle pullDataWithProgress(String dataName, IClipSyncProgress cb);

    // Cancel an in-flight pullDataWithProgress.
    oneway void cancelDownload();
}
