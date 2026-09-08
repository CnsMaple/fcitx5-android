package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.fcitx.fcitx5.android.common.ipc.IVoiceInputCallback
import org.fcitx.fcitx5.android.common.ipc.IVoiceInputProvider
import org.fcitx.fcitx5.android.common.ipc.VoiceInputIpc
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.plugin.cloudvoice.LocalVoiceProvider
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber

/**
 * Client side of the cloud voice input (push-to-talk). Audio is captured in
 * THIS (IME) process — while the keyboard is visible the process is foreground
 * and eligible for microphone access on Android 12+ — then pushed to the
 * provider plugin via feedAudio. The plugin only runs the ASR protocol.
 */
class CloudVoiceClient(private val service: FcitxInputMethodService) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var provider: IVoiceInputProvider? = null
    private var connection: ServiceConnection? = null
    private var audioJob: Job? = null

    /** chunks captured before the provider finished binding (~2 s max) */
    private val pendingLock = Any()
    private val pending = ArrayDeque<ByteArray>()

    @Volatile
    private var sessionActive = false

    /** set when the user released the space key before binding finished */
    @Volatile
    private var stopRequested = false

    @Volatile
    private var gotAudio = false

    private val audioWatchdog = Runnable {
        if (sessionActive && !gotAudio) {
            sessionActive = false
            service.withKeyboardWindow { hideVoiceOverlay() }
            service.toast("未收到音频：请确认小企鹅输入法已被授予麦克风权限")
            unbind()
        }
    }

    val isSessionActive: Boolean get() = sessionActive

    private val callback = object : IVoiceInputCallback.Stub() {
        override fun onReady() {
            // overlay is already shown by startHold()
        }

        override fun onVolumeLevel(rms: Int) {
            // external providers may push amplitude; ours captures locally
            gotAudio = true
            mainHandler.removeCallbacks(audioWatchdog)
            val amplitude = (rms / 100f).coerceIn(0f, 1f)
            mainHandler.post {
                service.withKeyboardWindow {
                    startVoiceOverlayWave()
                    updateVoiceOverlayAmplitude(amplitude)
                }
            }
        }

        override fun onPartialResult(text: String?) {
            val t = text ?: return
            mainHandler.post {
                service.currentInputConnection?.setComposingText(t, 1)
            }
        }

        override fun onSegmentFinal(text: String?) {
            val t = text ?: return
            mainHandler.post {
                val ic = service.currentInputConnection ?: return@post
                // replace the composing region with the final text (a plain
                // commit after finishComposingText would duplicate the partial)
                if (t.isNotBlank()) ic.setComposingText(t, 1)
                ic.finishComposingText()
            }
        }

        override fun onSessionEnded() {
            sessionActive = false
            mainHandler.removeCallbacks(audioWatchdog)
            mainHandler.post {
                service.withKeyboardWindow { hideVoiceOverlay() }
                unbind()
            }
        }

        override fun onError(code: Int, message: String?) {
            Timber.w("voice provider error $code: $message")
            sessionActive = false
            mainHandler.removeCallbacks(audioWatchdog)
            mainHandler.post {
                service.withKeyboardWindow { hideVoiceOverlay() }
                service.toast(message ?: "Voice input error ($code)")
                unbind()
            }
        }
    }

    /** Push-to-talk: space held down (long-press fired). Shows the overlay instantly. */
    fun startHold() {
        if (sessionActive) return
        if ((service as Context).checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            service.toast("语音输入需要麦克风权限：请在主程序「插件 → 云语音」中授权")
            return
        }
        sessionActive = true
        stopRequested = false
        gotAudio = false
        mainHandler.removeCallbacks(audioWatchdog)
        mainHandler.postDelayed(audioWatchdog, 4000)
        service.withKeyboardWindow { showVoiceOverlay() }
        startSession()
        startCapture()
    }

    /** Push-to-talk: space released. Hides the overlay instantly and commits the audio. */
    fun stopHold() {
        if (!sessionActive) return
        stopRequested = true
        mainHandler.removeCallbacks(audioWatchdog)
        stopCapture()
        service.withKeyboardWindow { hideVoiceOverlay() }
        // if already bound, ask the provider for the final result right away;
        // otherwise onServiceConnected handles the early release
        runCatching { provider?.stopSession() }
    }

    private fun startCapture() {
        audioJob?.cancel()
        audioJob = AudioCapture().start(
            scope = scope,
            context = service,
            onChunk = { pcm, amplitude ->
                gotAudio = true
                mainHandler.removeCallbacks(audioWatchdog)
                mainHandler.post {
                    service.withKeyboardWindow {
                        startVoiceOverlayWave()
                        updateVoiceOverlayAmplitude(amplitude)
                    }
                }
                val p = provider
                if (p != null) {
                    runCatching { p.feedAudio(pcm, 0, pcm.size, 0L) }
                } else {
                    synchronized(pendingLock) {
                        pending.addLast(pcm)
                        while (pending.size > 20) pending.removeFirst()
                    }
                }
            },
            onError = { t ->
                Timber.w(t, "audio capture failed")
                mainHandler.post {
                    service.toast("录音失败：${t.message}")
                    stopAndHide()
                }
            },
        )
    }

    private fun stopCapture() {
        audioJob?.cancel()
        audioJob = null
        synchronized(pendingLock) { pending.clear() }
    }

    private fun startSession() {
        val p = LocalVoiceProvider(service as Context)
        provider = p
        val backlog = synchronized(pendingLock) {
            val l = pending.toList(); pending.clear(); l
        }
        fun fail(msg: String) {
            sessionActive = false
            stopCapture()
            service.withKeyboardWindow { hideVoiceOverlay() }
            service.toast(msg)
            unbind()
        }
        if (stopRequested) {
            stopRequested = false
            if (!runCatching { p.isAvailable }.getOrDefault(false)) {
                sessionActive = false
                service.toast("云语音未配置：请在「设置 → 云语音」中配置")
                unbind()
                return
            }
            runCatching { p.startSession(callback) }
            backlog.forEach { runCatching { p.feedAudio(it, 0, it.size, 0L) } }
            runCatching { p.stopSession() }
            return
        }
        if (!runCatching { p.isAvailable }.getOrDefault(false)) {
            fail("云语音未配置：请在「设置 → 云语音」中配置"); return
        }
        val started = runCatching { p.startSession(callback) }.isSuccess
        if (!started) { fail("启动语音会话失败"); return }
        backlog.forEach { runCatching { p.feedAudio(it, 0, it.size, 0L) } }
    }

    fun stopAndHide() {
        if (sessionActive) {
            sessionActive = false
            runCatching { provider?.cancelSession() }
        }
        mainHandler.removeCallbacks(audioWatchdog)
        stopCapture()
        service.withKeyboardWindow { hideVoiceOverlay() }
        unbind()
    }

    private fun unbind() {
        provider = null
    }
}
private const val SELF_PLUGIN_PACKAGE = "org.fcitx.fcitx5.android.plugin.cloudvoice"
