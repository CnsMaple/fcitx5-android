package org.fcitx.fcitx5.android.input.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.sqrt

/**
 * 16 kHz mono PCM16 capture loop emitting ~100 ms chunks with RMS amplitude.
 * Runs in the IME process: while the keyboard is visible the process is
 * foreground and therefore eligible for microphone access on Android 12+.
 */
class AudioCapture {

    fun start(
        scope: CoroutineScope,
        context: Context,
        onChunk: (pcm: ByteArray, amplitude: Float) -> Unit,
        onError: (Throwable) -> Unit,
    ): Job = scope.launch(Dispatchers.IO) {
        val sampleRate = 16000
        val frameBytes = sampleRate * 2 / 10 // 100 ms
        @SuppressLint("MissingPermission")
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                frameBytes * 8,
            )
        } catch (e: Exception) {
            onError(e)
            return@launch
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Timber.w("AudioRecord not initialized (state=${recorder.state})")
            recorder.release()
            onError(IllegalStateException("AudioRecord init failed"))
            return@launch
        }
        try {
            recorder.startRecording()
            val buffer = ByteArray(frameBytes)
            while (isActive) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read == 0) continue
                if (read < 0) {
                    onError(IllegalStateException("AudioRecord read failed: $read"))
                    break
                }
                val chunk = buffer.copyOf(read)
                var sum = 0.0
                var i = 0
                while (i + 1 < read) {
                    val s = ((chunk[i].toInt() and 0xFF) or (chunk[i + 1].toInt() shl 8)).toShort()
                    sum += s.toDouble() * s
                    i += 2
                }
                val rms = sqrt(sum / (read / 2)) / 32768.0
                val amplitude = (rms * 4f).toFloat().coerceIn(0f, 1f)
                onChunk(chunk, amplitude)
            }
        } catch (e: Exception) {
            onError(e)
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
    }
}
