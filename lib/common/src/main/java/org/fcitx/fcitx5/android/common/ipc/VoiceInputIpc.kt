package org.fcitx.fcitx5.android.common.ipc

// Shared IPC constants for the voice-input provider protocol, used by both the
// main IME app (:app) and provider plugins.
object VoiceInputIpc {
    // Provider service intent action, e.g. "org.fcitx.fcitx5.android.plugin.VOICE_INPUT".
    const val SERVICE_ACTION = "org.fcitx.fcitx5.android.plugin.VOICE_INPUT"

    // Error codes reported via IVoiceInputCallback.onError.
    const val ERR_GENERIC = -1
    const val ERR_NO_PERMISSION = 1
    const val ERR_NOT_CONFIGURED = 2
    const val ERR_NETWORK = 3
}
