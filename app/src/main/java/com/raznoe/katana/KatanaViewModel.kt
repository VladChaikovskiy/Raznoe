package com.raznoe.katana

import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.raznoe.katana.model.Patch
import com.raznoe.katana.model.PatchStore
import com.raznoe.katana.model.Track
import com.raznoe.katana.model.TrackStore
import com.raznoe.katana.protocol.KatanaParam
import com.raznoe.katana.protocol.KatanaParams
import com.raznoe.katana.protocol.KatanaSysEx
import com.raznoe.katana.protocol.ParamKind
import com.raznoe.katana.usb.KatanaController
import com.raznoe.katana.usb.UsbMidiConnection

/** Roland Corporation USB vendor id. */
const val ROLAND_VENDOR_ID = 0x0582

data class DeviceInfo(val device: UsbDevice, val label: String)

class KatanaViewModel(app: Application) : AndroidViewModel(app) {

    private val app = app
    private val usbManager = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private val patchStore = PatchStore(app)
    private val trackStore = TrackStore(app)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var connection: UsbMidiConnection? = null
    private var controller: KatanaController? = null

    // --- UI state ---------------------------------------------------------
    var status by mutableStateOf("Не подключено")
        private set
    var connected by mutableStateOf(false)
        private set
    var connectedLabel by mutableStateOf("")
        private set
    var identityInfo by mutableStateOf("")
        private set
    var currentChannel by mutableStateOf(0)
        private set
    var txCount by mutableStateOf(0)
        private set
    var rxCount by mutableStateOf(0)
        private set
    var noResponse by mutableStateOf(false)
        private set
    var gotData by mutableStateOf(false)
        private set

    val devices = mutableStateListOf<DeviceInfo>()
    val paramValues = mutableStateMapOf<String, Int>()
    val log = mutableStateListOf<String>()
    val actionLog = mutableStateListOf<String>()
    val patches = mutableStateListOf<Patch>()

    // --- Jam player state -------------------------------------------------
    val tracks = mutableStateListOf<Track>()
    var currentTrack by mutableStateOf<Int?>(null)
        private set
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
    var mp3Volume by mutableStateOf(0.8f)
        private set
    var jamStatus by mutableStateOf("")
        private set
    var activePreset by mutableStateOf("")
        private set
    /** true => route MP3 to the amp's USB-audio out (mix with guitar in the combo). */
    var jamThroughAmp by mutableStateOf(true)
        private set
    private var player: MediaPlayer? = null

    fun chooseJamOutput(throughAmp: Boolean) {
        jamThroughAmp = throughAmp
        // Re-apply routing live if a track is playing.
        player?.let { applyPreferredOutput(it) }
    }

    /** Names of the current audio output routes (for the UI/log). */
    fun audioOutputs(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return "?"
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .joinToString(", ") { audioTypeName(it.type) }
            .ifEmpty { "нет" }
    }

    /**
     * The amp's audio output as Android sees it: USB audio (amp in Generic USB
     * mode) or Bluetooth A2DP (BT-DUAL adaptor). In Vendor USB mode (power-on
     * with [BOOSTER], needed for MIDI) the amp needs BOSS's proprietary driver,
     * which Android does not have — so no USB-audio device appears and MP3 can
     * only reach the amp via Generic mode, BT-DUAL, or the AUX IN jack.
     */
    private fun ampOutput(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val outs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        } ?: outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
    }

    /** True if an amp audio route (USB or BT-DUAL) is available right now. */
    fun ampAudioAvailable(): Boolean = ampOutput() != null

    /** Force MP3 output to the amp's audio route when requested and available. */
    private fun applyPreferredOutput(mp: MediaPlayer) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val target = if (jamThroughAmp) ampOutput() else null
        runCatching { mp.setPreferredDevice(target) }
    }

    private fun audioTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB(комбик)"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "динамик"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "наушники"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
        else -> "тип$type"
    }
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    private fun requestAudioFocus() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setWillPauseWhenDucked(false)
                    .build()
                focusRequest = req
                audioManager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN,
                )
            }
        }
    }

    private fun abandonAudioFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION") audioManager.abandonAudioFocus(null)
            }
        }
        focusRequest = null
    }

    init {
        KatanaParams.ALL.forEach { paramValues[it.id] = it.default }
        refreshDevices()
        refreshPatches()
        tracks.addAll(trackStore.list())
    }

    // --- Device discovery / connection -----------------------------------
    fun refreshDevices() {
        devices.clear()
        usbManager.deviceList.values
            .filter { it.vendorId == ROLAND_VENDOR_ID }
            .forEach {
                devices.add(
                    DeviceInfo(
                        it,
                        "${it.productName ?: "Roland/BOSS"}  " +
                            "(VID %04X / PID %04X)".format(it.vendorId, it.productId),
                    ),
                )
            }
        if (devices.isEmpty()) {
            status = "Устройство Roland не найдено. Включи комбик, удерживая [BOOSTER], и подключи кабель."
        }
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun connect(device: UsbDevice) {
        disconnect()
        val conn = UsbMidiConnection(usbManager, device)
        val ctl = KatanaController(conn)
        ctl.onTraffic = { dir, sysex ->
            onMain { if (dir == "RX") rxCount++ else txCount++ }
            appendLog("$dir  ${KatanaSysEx.toHex(sysex)}")
        }
        ctl.onIncoming = { incoming -> onMain { gotData = true; applyIncoming(incoming) } }
        ctl.onInfo = { msg -> appendLog(msg) }
        ctl.onSelectors = { sel ->
            logAction("gen3sel", "Gen3 FX-BOX слоты: booster=${sel[0]} mod=${sel[1]} " +
                "fx=${sel[2]} delay=${sel[3]} reverb=${sel[4]}")
        }
        ctl.onIdentity = { bytes ->
            val hex = bytes.joinToString(" ") { "%02X".format(it) }
            onMain {
                identityInfo = hex
                appendLog("IDENTITY  $hex")
            }
        }

        val error = conn.open(onSysEx = { sysex -> ctl.handleInbound(sysex) })
        if (error != null) {
            status = "Не удалось подключиться: $error"
            connected = false
            return
        }
        connection = conn
        controller = ctl
        connected = true
        connectedLabel = device.productName ?: "BOSS Katana"
        status = "Подключено: $connectedLabel"
        txCount = 0; rxCount = 0; noResponse = false; gotData = false
        appendLog("— подключено (PID %04X) —".format(device.productId))
        appendLog("USB: ${conn.diagnostics}")
        appendLog("— рукопожатие + автоопределение диалекта —")
        ctl.begin()
        // After the handshake + model-id sweep, if we still have no real data
        // the amp is either not in USB-MIDI mode or needs a physical knob nudge.
        mainHandler.postDelayed({
            if (connected && !gotData) {
                noResponse = true
                appendLog(
                    "⚠ Нет данных от комбика. 1) Проверь, что он включён с зажатым [BOOSTER] " +
                        "(режим USB-MIDI). 2) Покрути любую ручку на самом комбике — приложение " +
                        "выучит правильный заголовок Gen 3 из его ответа.",
                )
            }
        }, 6000)
    }

    fun disconnect() {
        controller?.shutdown()
        controller = null
        connection?.close()
        connection = null
        if (connected) appendLog("— отключено —")
        connected = false
        connectedLabel = ""
        status = "Не подключено"
    }

    // --- Action log (what the user pressed, address used, sent or not) ----
    private var lastActionKey = ""

    private fun logAction(key: String, text: String) = onMain {
        if (key.isNotEmpty() && key == lastActionKey && actionLog.isNotEmpty()) {
            actionLog[actionLog.size - 1] = text
        } else {
            actionLog.add(text)
            if (actionLog.size > 600) actionLog.removeAt(0)
        }
        lastActionKey = key
    }

    private fun addrHex(a: IntArray) = a.joinToString(" ") { "%02X".format(it and 0xFF) }

    fun clearActionLog() { actionLog.clear(); lastActionKey = "" }

    fun actionLogText(): String = buildString {
        append("Katana Ctl — журнал действий\n")
        append("Подключено: $connected ($connectedLabel)  ID: ${identityInfo.ifEmpty { "—" }}\n")
        append("Профиль: ${KatanaSysEx.generation}  заголовок ${KatanaSysEx.headerHex()}\n")
        append("TX=$txCount RX=$rxCount\n----\n")
        append(actionLog.joinToString("\n"))
    }

    // --- Controls ---------------------------------------------------------
    fun setParam(param: KatanaParam, value: Int) {
        val stored = if (param.kind == ParamKind.ENUM) value else value.coerceIn(param.min, param.max)
        paramValues[param.id] = stored
        controller?.setParam(param, stored)
        val gen3 = KatanaSysEx.generation == KatanaSysEx.Gen.GEN3
        val valTxt = when (param.kind) {
            ParamKind.TOGGLE -> if (stored != 0) "ON" else "OFF"
            ParamKind.ENUM -> param.options.getOrElse(param.indexOfValue(stored)) { "$stored" }
            ParamKind.CONTINUOUS -> "$stored"
        }
        val ctl = controller
        val where = if (ctl == null) "(нет связи)"
        else "→ ${addrHex(ctl.resolveAddress(param))}${if (gen3) " G3" else ""}"
        logAction(param.id, "${param.category}/${param.label}: $valTxt  $where")
    }

    fun selectChannel(index: Int) {
        val (name, dataByte) = KatanaParams.CHANNELS.getOrElse(index) { return }
        currentChannel = index
        controller?.selectChannel(dataByte)
        logAction("channel", "Канал → $name  ${if (controller == null) "(нет связи)" else "→ 00 01 00 00"}")
    }

    fun readCurrentState() {
        controller?.readAll()
    }

    /** Experimental new-gen (Gen 3 / Katana:GO dialect) amp command from the console. */
    fun sendNewGenAmp(knob: Int, value: Int): String {
        val ctl = controller ?: return "Не подключено"
        val frame = KatanaSysEx.buildNewGenAmpSet(knob, value)
        ctl.sendSysEx(frame)
        return "→ ${KatanaSysEx.toHex(frame)}"
    }

    fun sendRawHex(hex: String): String {
        val ctl = controller ?: return "Не подключено"
        return try {
            val bytes = KatanaSysEx.fromHex(hex)
            if (bytes.isEmpty()) return "Нечего отправлять"
            ctl.sendSysEx(bytes)
            "Отправлено ${bytes.size} байт"
        } catch (e: Exception) {
            "Ошибка hex: ${e.message}"
        }
    }

    fun readBlockHex(addressHex: String, size: Int): String {
        val ctl = controller ?: return "Не подключено"
        return try {
            val addr = KatanaSysEx.fromHex(addressHex).map { it.toInt() and 0xFF }.toIntArray()
            if (addr.size != 4) return "Адрес должен быть из 4 байт"
            ctl.readBlock(addr, size)
            "Запрошено $size байт"
        } catch (e: Exception) {
            "Ошибка адреса: ${e.message}"
        }
    }

    /**
     * Fold an inbound DT1 (which may be a whole block from an RQ1 read or a
     * single-parameter knob echo) back onto our parameter values. For each
     * param whose address falls inside the reported block, pull its byte(s).
     */
    private fun applyIncoming(incoming: KatanaSysEx.Incoming) {
        val addr = incoming.address
        val data = incoming.data
        if (data.isEmpty()) return
        for (p in KatanaParams.ALL) {
            // Match against both the MkII and Gen 3 address of the parameter.
            for (pa in listOfNotNull(p.address, p.addrGen3)) {
                if (pa[0] != addr[0] || pa[1] != addr[1] || pa[2] != addr[2]) continue
                val offset = pa[3] - addr[3]
                if (offset < 0 || offset >= data.size) continue
                paramValues[p.id] = if (p.word && offset + 1 < data.size) {
                    ((data[offset] and 0x7F) shl 7) or (data[offset + 1] and 0x7F)
                } else {
                    data[offset] and 0x7F
                }
            }
        }
    }

    // --- Patches (librarian) ---------------------------------------------
    fun refreshPatches() {
        patches.clear()
        patches.addAll(patchStore.list())
    }

    fun capturePatch(name: String) {
        val snapshot = KatanaParams.ALL.associate { it.id to (paramValues[it.id] ?: it.default) }
        patchStore.save(Patch(name = name, values = snapshot))
        refreshPatches()
    }

    fun applyPatch(patch: Patch) {
        patch.values.forEach { (id, value) ->
            KatanaParams.BY_ID[id]?.let { setParam(it, value) }
        }
        activePreset = patch.name
        appendLog("— применён пресет '${patch.name}' —")
        logAction("", "Пресет загружен: ${patch.name}  ${if (controller == null) "(нет связи)" else "(отправлен)"}")
    }

    fun deletePatch(name: String) {
        patchStore.delete(name)
        refreshPatches()
    }

    // --- Jam player (MP3 backing tracks) ---------------------------------
    fun addTrack(uri: Uri) {
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "track"
        if (tracks.none { it.uri == uri.toString() }) {
            tracks.add(Track(uri.toString(), name))
            trackStore.saveAll(tracks.toList())
            jamStatus = "Добавлен: $name"
        } else {
            jamStatus = "Уже в списке: $name"
        }
        logAction("", "Джем: добавлен трек «$name»")
    }

    fun changeMp3Volume(v: Float) {
        mp3Volume = v.coerceIn(0f, 1f)
        runCatching { player?.setVolume(mp3Volume, mp3Volume) }
    }

    fun removeTrack(index: Int) {
        if (index !in tracks.indices) return
        if (currentTrack == index) stopPlayback()
        tracks.removeAt(index)
        trackStore.saveAll(tracks.toList())
        if (currentTrack != null && currentTrack!! >= tracks.size) currentTrack = null
    }

    fun playTrack(index: Int) {
        if (index !in tracks.indices) return
        releasePlayer()
        val t = tracks[index]
        // Note: don't use MediaPlayer().apply { } here — inside `apply` the
        // receiver is the MediaPlayer, whose read-only `isPlaying` would shadow
        // our own state property. Configure via an explicit local instead.
        val mp = MediaPlayer()
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        mp.setOnPreparedListener { p ->
            durationMs = p.duration
            p.isLooping = looping
            requestAudioFocus()
            applyPreferredOutput(p)
            runCatching { p.setVolume(mp3Volume, mp3Volume) }
            p.start()
            applySpeed(p) // after start() — setting speed pre-start throws on some devices
            isPlaying = true
            val amp = ampOutput()
            val out = when {
                jamThroughAmp && amp != null -> "комбик (${audioTypeName(amp.type)})"
                amp != null -> "динамик (комбик доступен — включи «через комбик»)"
                else -> "динамик · комбик не виден как аудио (нужен Generic-режим/BT-DUAL/AUX — см. «Как вывести звук в комбик»)"
            }
            jamStatus = "Играет: ${t.name} · $out"
            logAction("", "Джем: играет «${t.name}» (${durationMs / 1000}с, громкость ${(mp3Volume * 100).toInt()}%, выход: $out; все выходы: ${audioOutputs()})")
        }
        mp.setOnCompletionListener {
            if (!looping) { isPlaying = false; positionMs = durationMs; abandonAudioFocus() }
        }
        mp.setOnErrorListener { _, what, extra ->
            isPlaying = false
            jamStatus = "Ошибка воспроизведения ($what/$extra). Попробуй другой файл."
            logAction("", "Джем: ОШИБКА воспроизведения «${t.name}» (код $what/$extra)")
            true
        }
        player = mp
        currentTrack = index
        positionMs = 0
        jamStatus = "Загрузка: ${t.name}…"
        logAction("", "Джем: запуск «${t.name}»")
        runCatching {
            mp.setDataSource(app, Uri.parse(t.uri))
            mp.prepareAsync()
        }.onFailure {
            isPlaying = false
            jamStatus = "Не удалось открыть файл: ${it.message}"
            logAction("", "Джем: ошибка открытия «${t.name}»: ${it.message} " +
                "(возможно, потерян доступ к файлу — добавь его заново)")
        }
    }

    fun togglePlayPause() {
        val mp = player
        if (mp == null) { currentTrack?.let { playTrack(it) } ?: tracks.indices.firstOrNull()?.let { playTrack(it) }; return }
        if (mp.isPlaying) { mp.pause(); isPlaying = false } else { mp.start(); isPlaying = true }
    }

    fun stopPlayback() {
        releasePlayer(); isPlaying = false; positionMs = 0; abandonAudioFocus()
    }

    fun seekTo(ms: Int) {
        player?.seekTo(ms.coerceIn(0, durationMs)); positionMs = ms
    }

    fun toggleLoop() {
        looping = !looping; player?.isLooping = looping
    }

    fun cycleSpeed() {
        speed = when (speed) { 1.0f -> 0.75f; 0.75f -> 0.5f; 0.5f -> 1.25f; else -> 1.0f }
        val mp = player ?: return
        if (mp.isPlaying) applySpeed(mp)
    }

    /** Called from the UI to refresh the seek position while playing. */
    fun refreshPosition() {
        player?.let { if (it.isPlaying) positionMs = it.currentPosition }
    }

    private fun applySpeed(mp: MediaPlayer) {
        runCatching { mp.playbackParams = mp.playbackParams.setSpeed(speed) }
    }

    private fun queryDisplayName(uri: Uri): String? =
        runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()

    private fun releasePlayer() {
        runCatching { player?.release() }
        player = null
    }

    // --- Log --------------------------------------------------------------
    fun clearLog() = log.clear()

    /** Full log as shareable text (for sending diagnostics). */
    fun logText(): String = buildString {
        append("Katana Ctl — диагностика\n")
        append("Подключено: $connected  ($connectedLabel)\n")
        append("ID: ${identityInfo.ifEmpty { "—" }}\n")
        append("TX=$txCount  RX=$rxCount  noResponse=$noResponse\n")
        append("Профиль modelId=0x%02X\n".format(KatanaSysEx.modelId))
        append("----\n")
        append(log.joinToString("\n"))
    }

    private fun appendLog(line: String) = onMain {
        log.add(line)
        if (log.size > MAX_LOG_LINES) log.removeAt(0)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    override fun onCleared() {
        releasePlayer()
        disconnect()
        super.onCleared()
    }

    companion object {
        private const val MAX_LOG_LINES = 400
    }
}
