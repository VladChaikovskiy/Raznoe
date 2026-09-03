package com.raznoe.katana.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/** Where the backing track should be sent. */
enum class JamOutput(val label: String) {
    /** Bluetooth if present, else the amp over USB, else the phone. */
    AUTO("Авто"),
    BLUETOOTH("Bluetooth"),
    USB("USB (комбик)"),
    PHONE("Телефон"),
}

/** One audio output Android currently offers us. */
data class AudioRoute(
    val id: Int,
    val name: String,
    val kind: String,
    val bluetooth: Boolean,
    val usb: Boolean,
)

/**
 * The backing-track player.
 *
 * Application-scoped (see [Jam]) rather than owned by the ViewModel, because
 * playback has to survive the Activity: the amp is plugged into the phone, the
 * screen goes off mid-song, Android recreates the Activity, and the track has
 * to keep going. A [JamService] foreground notification keeps the process alive
 * while something is playing, and [MediaPlayer.setWakeMode] keeps the CPU up.
 *
 * The Bluetooth path is the interesting part. On a Katana Gen 3 the phone is
 * usually connected two ways at once: USB-C for MIDI (presets, knobs) and
 * Bluetooth for audio (the backing track, through the amp's own speaker). Those
 * are independent, so selecting presets keeps working while the track plays —
 * but the audio route comes and goes on its own, and that is what used to make
 * playback stop dead:
 *
 *  • Bluetooth connects a moment AFTER playback starts → the track was stuck on
 *    the phone speaker. We now re-route live when a device appears.
 *  • Bluetooth drops out for a second → playback either blasted out of the
 *    phone speaker or died. We pause, remember why, and resume by ourselves
 *    when the route comes back.
 *  • Another app takes audio focus (a call, a notification) → we pause and
 *    resume instead of being left in a half-dead state.
 */
class JamPlayer(private val app: Context) {

    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())

    // --- observable state -------------------------------------------------
    var isPlaying by mutableStateOf(false)
        private set
    var positionMs by mutableStateOf(0)
        private set
    var durationMs by mutableStateOf(0)
        private set
    var looping by mutableStateOf(false)
        private set
    var speed by mutableStateOf(1.0f)
        private set
    var volume by mutableStateOf(0.8f)
        private set
    var status by mutableStateOf("")
        private set
    var output by mutableStateOf(JamOutput.AUTO)
        private set

    /** Human-readable description of where the sound is actually going. */
    var routeLabel by mutableStateOf("—")
        private set
    var trackName by mutableStateOf("")
        private set
    var trackUri by mutableStateOf<String?>(null)
        private set

    /** True while we are holding playback waiting for a route to come back. */
    var waitingForRoute by mutableStateOf(false)
        private set

    /** Diagnostics sink (the ViewModel's action log). */
    var onLog: ((String) -> Unit)? = null

    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var pausedByRouteLoss = false
    private var pausedByFocusLoss = false
    private var retriedCurrent = false
    private var started = false

    // --- lifecycle --------------------------------------------------------

    /** Start listening for route/focus changes. Idempotent. */
    fun start() {
        if (started) return
        started = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching { audio.registerAudioDeviceCallback(deviceCallback, main) }
        }
        runCatching {
            ContextCompat.registerReceiver(
                app, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        refreshRouteLabel()
    }

    // --- transport --------------------------------------------------------

    fun play(uri: String, name: String) {
        onMain {
            releasePlayer()
            retriedCurrent = false
            trackUri = uri
            trackName = name
            positionMs = 0
            status = "Загрузка: $name…"
            openTrack(uri, name, startAtMs = 0)
        }
    }

    private fun openTrack(uri: String, name: String, startAtMs: Int) {
        val mp = MediaPlayer()
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        // Keep the CPU alive so the track does not stutter or stop with the
        // screen off. Paired with the foreground service, this is what makes
        // "put the phone down and play" actually work.
        runCatching { mp.setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK) }

        mp.setOnPreparedListener { p ->
            durationMs = runCatching { p.duration }.getOrDefault(0)
            runCatching { p.isLooping = looping }
            runCatching { p.setVolume(volume, volume) }
            applyRoute(p)
            if (startAtMs > 0) runCatching { p.seekTo(startAtMs) }
            if (requestFocus()) {
                runCatching { p.start() }
                applySpeed(p) // only valid once started on some devices
                isPlaying = true
                waitingForRoute = false
                refreshRouteLabel()
                status = "Играет: $name · $routeLabel"
                onLog?.invoke("Джем: играет «$name» → $routeLabel")
                JamService.sync(app, this)
            } else {
                isPlaying = false
                status = "Звук занят другим приложением — попробуй ещё раз"
            }
        }
        mp.setOnCompletionListener {
            if (!looping) {
                isPlaying = false
                positionMs = durationMs
                abandonFocus()
                status = "Трек закончился"
                JamService.sync(app, this)
            }
        }
        mp.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "MediaPlayer error $what/$extra")
            onLog?.invoke("Джем: ошибка воспроизведения «$name» ($what/$extra)")
            releasePlayer()
            // A route switch mid-song can kill the player; one silent retry
            // from the same position recovers it instead of dropping the jam.
            if (!retriedCurrent) {
                retriedCurrent = true
                val at = positionMs
                status = "Сбой звука — восстанавливаю…"
                main.postDelayed({ openTrack(uri, name, at) }, RETRY_DELAY_MS)
            } else {
                isPlaying = false
                abandonFocus()
                status = "Не удалось воспроизвести файл. Добавь его заново или выбери другой."
                JamService.sync(app, this)
            }
            true
        }

        player = mp
        val opened = runCatching {
            mp.setDataSource(app, Uri.parse(uri))
            mp.prepareAsync()
        }
        if (opened.isFailure) {
            isPlaying = false
            releasePlayer()
            status = "Не удалось открыть файл — возможно, потерян доступ. Добавь его заново."
            onLog?.invoke("Джем: не открылся «$name»: ${opened.exceptionOrNull()?.message}")
            JamService.sync(app, this)
        }
    }

    fun togglePlayPause() = onMain {
        val mp = player
        if (mp == null) {
            val uri = trackUri
            if (uri != null) openTrack(uri, trackName, positionMs) else status = "Выбери трек"
            return@onMain
        }
        runCatching {
            if (mp.isPlaying) {
                mp.pause()
                isPlaying = false
                status = "Пауза"
            } else {
                if (requestFocus()) {
                    mp.start()
                    isPlaying = true
                    pausedByRouteLoss = false
                    pausedByFocusLoss = false
                    waitingForRoute = false
                    status = "Играет: $trackName · $routeLabel"
                }
            }
        }.onFailure { status = "Плеер не в том состоянии — нажми ещё раз" }
        JamService.sync(app, this)
    }

    fun stop() = onMain {
        releasePlayer()
        isPlaying = false
        positionMs = 0
        pausedByRouteLoss = false
        pausedByFocusLoss = false
        waitingForRoute = false
        abandonFocus()
        JamService.sync(app, this)
    }

    fun seekTo(ms: Int) = onMain {
        val target = ms.coerceIn(0, if (durationMs > 0) durationMs else ms)
        runCatching { player?.seekTo(target) }
        positionMs = target
    }

    /** Not `setVolume`: that JVM signature is taken by the [volume] property. */
    fun changeVolume(v: Float) = onMain {
        volume = v.coerceIn(0f, 1f)
        runCatching { player?.setVolume(volume, volume) }
    }

    fun toggleLoop() = onMain {
        looping = !looping
        runCatching { player?.isLooping = looping }
    }

    fun cycleSpeed() = onMain {
        speed = when (speed) { 1.0f -> 0.75f; 0.75f -> 0.5f; 0.5f -> 1.25f; else -> 1.0f }
        val mp = player ?: return@onMain
        runCatching { if (mp.isPlaying) applySpeed(mp) }
    }

    /** Called from the UI while playing, to move the seek bar. */
    fun refreshPosition() {
        runCatching { player?.let { if (it.isPlaying) positionMs = it.currentPosition } }
    }

    private fun applySpeed(mp: MediaPlayer) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching { mp.playbackParams = mp.playbackParams.setSpeed(speed) }
    }

    // --- routing ----------------------------------------------------------

    /** Not `setOutput`: that JVM signature is taken by the [output] property. */
    fun chooseOutput(o: JamOutput) = onMain {
        output = o
        player?.let { applyRoute(it) }
        refreshRouteLabel()
        onLog?.invoke("Джем: выход → ${o.label} ($routeLabel)")
    }

    /** Everything Android currently offers as an output. */
    fun routes(): List<AudioRoute> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyList()
        return runCatching {
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { d ->
                AudioRoute(
                    id = d.id,
                    name = deviceName(d),
                    kind = kindName(d.type),
                    bluetooth = isBluetooth(d.type),
                    usb = isUsb(d.type),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** The device matching the current [output] preference, or null for "system default". */
    private fun pick(o: JamOutput): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val outs = runCatching { audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
            .getOrDefault(emptyArray<AudioDeviceInfo>())
        val bt = outs.firstOrNull { isBluetooth(it.type) }
        val usb = outs.firstOrNull { isUsb(it.type) }
        return when (o) {
            JamOutput.BLUETOOTH -> bt
            JamOutput.USB -> usb
            // Auto: the amp's own Bluetooth first — that is how a Gen 3 plays a
            // backing track through its speaker — then USB audio, then nothing
            // (let Android use the phone).
            JamOutput.AUTO -> bt ?: usb
            JamOutput.PHONE -> null
        }
    }

    private fun applyRoute(mp: MediaPlayer) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val target = if (output == JamOutput.PHONE) null else pick(output)
        runCatching { mp.setPreferredDevice(target) }
    }

    private fun refreshRouteLabel() {
        val chosen = pick(output)
        routeLabel = when {
            output == JamOutput.PHONE -> "телефон"
            chosen != null -> "${kindName(chosen.type)} · ${deviceName(chosen)}"
            output == JamOutput.BLUETOOTH -> "Bluetooth не подключён"
            output == JamOutput.USB -> "USB-аудио не видно"
            else -> "телефон (Bluetooth/USB не подключены)"
        }
    }

    private fun deviceName(d: AudioDeviceInfo): String =
        runCatching { d.productName?.toString() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: kindName(d.type)

    private fun isBluetooth(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> true
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> true
        else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
    }

    private fun isUsb(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            type == AudioDeviceInfo.TYPE_USB_ACCESSORY

    private fun kindName(type: Int): String = when {
        isBluetooth(type) -> "Bluetooth"
        isUsb(type) -> "USB"
        type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "динамик"
        type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            type == AudioDeviceInfo.TYPE_WIRED_HEADSET -> "наушники"
        else -> "тип$type"
    }

    /**
     * A route appeared or vanished. This is the callback that makes Bluetooth
     * dependable: we follow the route instead of assuming it never changes.
     */
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) {
            onMain {
                refreshRouteLabel()
                val relevant = added?.any { isBluetooth(it.type) || isUsb(it.type) } == true
                if (!relevant) return@onMain
                player?.let { applyRoute(it) }
                onLog?.invoke("Джем: появился выход → $routeLabel")
                // We paused because the route went away — pick the track back up.
                if (pausedByRouteLoss && pick(output) != null) {
                    pausedByRouteLoss = false
                    main.postDelayed({ if (!isPlaying) togglePlayPause() }, RESUME_DELAY_MS)
                } else if (isPlaying) {
                    status = "Играет: $trackName · $routeLabel"
                }
            }
        }

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) {
            onMain {
                val lostRoute = removed?.any { isBluetooth(it.type) || isUsb(it.type) } == true
                refreshRouteLabel()
                if (!lostRoute) return@onMain
                player?.let { applyRoute(it) }
                // Nothing left to play into that the user asked for: hold the
                // track rather than dumping it out of the phone speaker.
                if (isPlaying && output != JamOutput.PHONE && pick(output) == null) {
                    runCatching { player?.pause() }
                    isPlaying = false
                    pausedByRouteLoss = true
                    waitingForRoute = true
                    status = "Пауза: ${output.label} пропал. Продолжу сам, когда вернётся."
                    onLog?.invoke("Джем: выход пропал (${output.label}) — пауза до возврата")
                    JamService.sync(app, this@JamPlayer)
                }
            }
        }
    }

    /** Headphones/BT yanked out: Android asks every player to stop. */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            onMain {
                if (!isPlaying) return@onMain
                runCatching { player?.pause() }
                isPlaying = false
                pausedByRouteLoss = true
                waitingForRoute = true
                status = "Пауза: звук отключился. Продолжу, когда вернётся."
                JamService.sync(app, this@JamPlayer)
            }
        }
    }

    // --- audio focus ------------------------------------------------------

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        onMain {
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    runCatching { player?.pause() }
                    isPlaying = false
                    pausedByFocusLoss = false
                    status = "Пауза: звук забрало другое приложение"
                    JamService.sync(app, this)
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    if (isPlaying) {
                        runCatching { player?.pause() }
                        isPlaying = false
                        pausedByFocusLoss = true
                        status = "Пауза на время звонка/уведомления"
                        JamService.sync(app, this)
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                    runCatching { player?.setVolume(volume * 0.3f, volume * 0.3f) }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    runCatching { player?.setVolume(volume, volume) }
                    if (pausedByFocusLoss) {
                        pausedByFocusLoss = false
                        runCatching { player?.start() }
                        isPlaying = player?.isPlaying == true
                        if (isPlaying) status = "Играет: $trackName · $routeLabel"
                        JamService.sync(app, this)
                    }
                }
            }
        }
    }

    private fun requestFocus(): Boolean {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val result = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audio.abandonAudioFocusRequest(it) }
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(focusListener, main)
                    .build()
                focusRequest = req
                audio.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audio.requestAudioFocus(
                    focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN,
                )
            }
        }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        // A "delayed" grant still means we may start when focus arrives; treat
        // only an outright failure as a refusal.
        return result != AudioManager.AUDIOFOCUS_REQUEST_FAILED
    }

    private fun abandonFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audio.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION") audio.abandonAudioFocus(focusListener)
            }
        }
        focusRequest = null
    }

    // --- internals --------------------------------------------------------

    private fun releasePlayer() {
        val mp = player
        player = null
        runCatching { mp?.setOnErrorListener(null) }
        runCatching { mp?.setOnPreparedListener(null) }
        runCatching { mp?.setOnCompletionListener(null) }
        runCatching { mp?.reset() }
        runCatching { mp?.release() }
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    private companion object {
        const val TAG = "JamPlayer"
        const val RETRY_DELAY_MS = 400L
        const val RESUME_DELAY_MS = 700L
    }
}

/** Application-scoped [JamPlayer]; the service and the ViewModel share one. */
object Jam {
    @Volatile private var instance: JamPlayer? = null

    fun get(context: Context): JamPlayer =
        instance ?: synchronized(this) {
            instance ?: JamPlayer(context.applicationContext).also { it.start(); instance = it }
        }

    /** The existing player, if one was ever created (used by the service). */
    fun peek(): JamPlayer? = instance
}
