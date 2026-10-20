package app.dsh.mobile.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 原生音乐播放（v1.3.40）。
 *
 * 用户诉求：agent（跑在 App 内的 Termux 环境里）点歌时，只借助系统音频硬件播放，
 * 播放器本体属于 App 侧而不是额外装一堆 Termux 音频栈。
 *
 * 实现直接用平台 [MediaPlayer]：
 *  - 解码/输出走 Android 的音频硬件抽象（AudioTrack / 音频 HAL），不需要 ALSA/PulseAudio；
 *  - 申请 AUDIOFOCUS_GAIN + USAGE_MEDIA，其它 App 来电/导航时按系统规则让出；
 *  - 引擎本来就跑在常驻前台服务里，App 退到后台音乐仍可继续；
 *  - 支持平台解码器能吃的格式（MP3/AAC/FLAC/OGG/WAV…）与本机路径、http(s) URL。
 *
 * 局限（要如实告诉 agent/用户）：平台 MediaPlayer 不支持 opus-in-webm、ape、dsd 等小众格式；
 * 这类需要转码或改用 Termux 里的 mpv（OpenSL/AAudio 输出）。
 */
object AudioPlayer {
    private const val TAG = "AudioPlayer"

    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var sourceRef: String = ""
    private var titleRef: String = ""
    private var artistRef: String = ""
    private var looping = false
    private var state: String = "idle"   // idle | preparing | playing | paused | stopped | error
    private var lastError: String? = null

    private val main = Handler(Looper.getMainLooper())

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun <T> awaitMain(timeoutMs: Long, block: (CountDownLatch) -> T): T? {
        val latch = CountDownLatch(1)
        var result: T? = null
        onMain { result = block(latch) }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return result
    }

    private fun audioFocus(ctx: Context): Boolean = runCatching {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> runCatching { player?.takeIf { it.isPlaying }?.pause() }
                    }
                }
                .build()
            focusRequest = req
            am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }.getOrDefault(false)

    private fun abandonFocus(ctx: Context) {
        runCatching {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        }
        focusRequest = null
    }

    fun stop(ctx: Context) {
        awaitMain(1500) { latch ->
            runCatching {
                player?.let { p ->
                    runCatching { p.stop() }
                    runCatching { p.release() }
                }
            }
            player = null
            state = "stopped"
            abandonFocus(ctx)
            latch.countDown()
        }
    }

    fun play(
        ctx: Context,
        source: String,
        title: String = "",
        artist: String = "",
        loop: Boolean = false,
    ): JSONObject {
        stop(ctx)
        sourceRef = source
        titleRef = title
        artistRef = artist
        looping = loop
        lastError = null
        state = "preparing"

        val latch = CountDownLatch(1)
        onMain {
            runCatching {
                // 本地路径先做存在性检查，给出可读错误而不是 MediaPlayer 的晦涩 error
                if (!source.startsWith("http://") && !source.startsWith("https://") && !source.startsWith("content://")) {
                    val f = File(source)
                    if (!f.isFile) {
                        state = "error"
                        lastError = "file not found: $source"
                        latch.countDown()
                        return@onMain
                    }
                }
                val p = MediaPlayer()
                p.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                p.isLooping = loop
                p.setOnPreparedListener { mp ->
                    if (!audioFocus(ctx)) {
                        state = "error"
                        lastError = "audio focus denied (another app is playing?)"
                        runCatching { mp.release() }
                        player = null
                        latch.countDown()
                        return@setOnPreparedListener
                    }
                    runCatching { mp.start() }
                    state = "playing"
                    latch.countDown()
                }
                p.setOnErrorListener { _, what, extra ->
                    state = "error"
                    lastError = "MediaPlayer error what=$what extra=$extra (unsupported format or bad source)"
                    runCatching { p.release() }
                    player = null
                    latch.countDown()
                    true
                }
                p.setOnCompletionListener {
                    if (!looping) {
                        state = "stopped"
                        abandonFocus(ctx)
                    }
                }
                player = p
                p.setDataSource(source)
                p.prepareAsync()
            }.onFailure {
                state = "error"
                lastError = (it.message ?: it.toString())
                latch.countDown()
            }
        }
        latch.await(8, TimeUnit.SECONDS)
        return status()
    }

    fun pause(ctx: Context): JSONObject {
        awaitMain(1500) { latch ->
            runCatching { player?.takeIf { it.isPlaying }?.pause() }
            if (state == "playing") state = "paused"
            abandonFocus(ctx)
            latch.countDown()
        }
        return status()
    }

    fun resume(ctx: Context): JSONObject {
        awaitMain(1500) { latch ->
            runCatching {
                val p = player
                if (p != null) {
                    if (audioFocus(ctx)) {
                        p.start()
                        state = "playing"
                    } else {
                        state = "error"
                        lastError = "audio focus denied"
                    }
                } else {
                    state = "error"
                    lastError = "nothing loaded; call play first"
                }
            }
            latch.countDown()
        }
        return status()
    }

    fun status(): JSONObject {
        val p = player
        return JSONObject()
            .put("ok", state != "error")
            .put("state", state)
            .put("playing", runCatching { p?.isPlaying == true }.getOrDefault(false))
            .put("position_ms", runCatching { p?.currentPosition ?: 0 }.getOrDefault(0))
            .put("duration_ms", runCatching { p?.duration ?: 0 }.getOrDefault(0))
            .put("source", sourceRef)
            .put("title", titleRef)
            .put("artist", artistRef)
            .put("loop", looping)
            .put("error", lastError ?: JSONObject.NULL)
    }
}
