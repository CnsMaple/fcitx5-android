// Voice input callback protocol. Transaction order must match the provider side.
package org.fcitx.fcitx5.android.common.ipc;

oneway interface IVoiceInputCallback {
    void onReady();

    void onVolumeLevel(int rms);

    void onPartialResult(String text);

    void onSegmentFinal(String text);

    void onSessionEnded();

    void onError(int code, String message);
}
