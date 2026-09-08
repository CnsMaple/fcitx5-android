// Progress callback for long-running clipboard downloads (bytes streamed).
package org.fcitx.fcitx5.android.common.ipc;

interface IClipSyncProgress {
    // Called periodically during a download. total == -1 if unknown length.
    void onProgress(long done, long total);
}
