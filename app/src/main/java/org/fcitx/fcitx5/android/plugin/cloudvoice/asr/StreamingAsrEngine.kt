package org.fcitx.fcitx5.android.plugin.cloudvoice.asr

/**
 * Minimal streaming ASR engine contract (ported from BiBi-Keyboard).
 * The engine owns microphone capture and reports results via [Listener].
 */
interface StreamingAsrEngine {
    val isRunning: Boolean

    fun start()

    /** Finish the stream and deliver the final result. */
    fun stop()

    /** Abort the stream without delivering a result. */
    fun cancel()

    /** External PCM chunk pushed by the host app (16k mono s16le, ~100 ms). */
    fun feedPcm(pcm: ByteArray) {}

    interface Listener {
        fun onFinal(text: String)

        fun onError(message: String)

        fun onPartial(text: String) {}

        /** Recording phase ended (still waiting for the final result). */
        fun onStopped() {}

        /** Normalized mic amplitude 0f..1f for waveform animation. */
        fun onAmplitude(amplitude: Float) {}
    }
}
