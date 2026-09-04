package com.raznoe.katana

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.raznoe.katana.audio.Jam
import com.raznoe.katana.audio.JamPlayer
import com.raznoe.katana.model.FactoryPresets
import com.raznoe.katana.model.MusicLibrary
import com.raznoe.katana.model.Patch
import com.raznoe.katana.model.PatchStore
import com.raznoe.katana.model.RawPatch
import com.raznoe.katana.model.RawPatchStore
import com.raznoe.katana.model.Track
import com.raznoe.katana.model.TrackStore
import com.raznoe.katana.model.Tracks
import com.raznoe.katana.protocol.KatanaParam
import com.raznoe.katana.protocol.KatanaParams
import com.raznoe.katana.protocol.KatanaSysEx
import com.raznoe.katana.protocol.ParamKind
import com.raznoe.katana.usb.KatanaController
import com.raznoe.katana.usb.UsbMidiConnection
import java.util.concurrent.atomic.AtomicInteger

/** Roland Corporation USB vendor id. */
const val ROLAND_VENDOR_ID = 0x0582

data class DeviceInfo(val device: UsbDevice, val label: String)

/** A block worth reading back from the amp on the diagnostics screen. */
data class DiagBlock(val label: String, val address: IntArray, val size: Int) {
    override fun equals(other: Any?) = other is DiagBlock && other.label == label
    override fun hashCode() = label.hashCode()
}

/**
 * The blocks that decide the tone, in both address spaces.
 *
 * Reading these back is how a wrong address gets found: turn a knob on the amp,
 * read again, see which byte moved. Both generations are listed because which
 * one an amp answers on is itself the question — if the MkII panel block comes
 * back with data, that is the space in use, whatever the profile says.
 */
val DIAG_BLOCKS = listOf(
    DiagBlock("MkII: панель+усилитель", intArrayOf(0x00, 0x00, 0x04, 0x00), 0x2A),
    DiagBlock("MkII: preamp-модель", intArrayOf(0x60, 0x00, 0x00, 0x50), 16),
    DiagBlock("MkII: бустер", intArrayOf(0x60, 0x00, 0x00, 0x30), 16),
    DiagBlock("MkII: шумодав", intArrayOf(0x60, 0x00, 0x06, 0x63), 4),
    DiagBlock("Gen3: усилитель", intArrayOf(0x20, 0x00, 0x06, 0x00), 16),
    DiagBlock("Gen3: вкл/выкл эффектов", intArrayOf(0x20, 0x00, 0x08, 0x00), 16),
    DiagBlock("Gen3: слоты FX-BOX", intArrayOf(0x20, 0x00, 0x04, 0x00), 8),
    DiagBlock("Gen3: шумодав", intArrayOf(0x20, 0x00, 0x58, 0x00), 4),
)

class KatanaViewModel(app: Application) : AndroidViewModel(app) {

    private val app = app
    private val usbManager = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private val patchStore = PatchStore(app)
    private val trackStore = TrackStore(app)
    private val rawStore = RawPatchStore(app)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var connection: UsbMidiConnection? = null
    private var controller: KatanaController? = null

    /** Backing-track player. Application-scoped so playback outlives the UI. */
    val jam: JamPlayer = Jam.get(app)

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
    /** Name of the preset currently being written to the amp, if any. */
    var presetLoading by mutableStateOf<String?>(null)
        private set
    /** Result of the last preset load, for the Пресеты screen. */
    var presetStatus by mutableStateOf("")
        private set
    /** Stack trace of the last crash, so a field failure can be reported. */
    var lastCrash by mutableStateOf<String?>(null)
        private set

    val devices = mutableStateListOf<DeviceInfo>()
    val paramValues = mutableStateMapOf<String, Int>()
    // Diagnostic logs: plain, capped, thread-safe — NOT Compose state. Nothing
    // in the UI reads them, so per-packet snapshot writes were pure main-thread
    // load (the cause of "works then freezes"). appendLog/logAction never touch
    // the main thread now.
    private val log = ArrayDeque<String>()
    private val actionLog = ArrayDeque<String>()
    // TX/RX counters are hit on the USB threads for every packet; increment
    // lock-free and push to Compose state at most a few times/sec.
    private val txAtomic = AtomicInteger(0)
    private val rxAtomic = AtomicInteger(0)
    @Volatile private var countsPostScheduled = false
    val patches = mutableStateListOf<Patch>()

    // --- Jam player state -------------------------------------------------
    /** What the Jam tab shows: the phone's library plus hand-picked files. */
    val tracks = mutableStateListOf<Track>()

    /** Hand-picked files (persisted); library tracks are re-found on each scan. */
    private val pickedTracks = mutableListOf<Track>()
    private var libraryTracks: List<Track> = emptyList()

    /** True once the user has granted access to the phone's music. */
    var musicAccess by mutableStateOf(false)
        private set
    var libraryStatus by mutableStateOf("")
        private set
    var scanningLibrary by mutableStateOf(false)
        private set

    var activePreset by mutableStateOf("")
        private set

    // --- What a preset is allowed to write -------------------------------
    // The preset values are provably right; whether they LAND right depends on
    // the Gen 3 address map, which is only partly confirmed. Splitting the
    // write by section turns "the presets ruin the tone" into a question that
    // can be answered by ear in one tap: switch the amp block off, load a
    // preset, and if the tone is sane then the fault is in the amp addresses,
    // not in the preset.
    var writeAmpBlock by mutableStateOf(true)
        private set
    var writeEffects by mutableStateOf(true)
        private set
    var writeGate by mutableStateOf(true)
        private set

    // Not setWriteX: a `var writeX` already occupies that JVM signature.
    fun allowAmpBlock(on: Boolean) { writeAmpBlock = on }
    fun allowEffects(on: Boolean) { writeEffects = on }
    fun allowGate(on: Boolean) { writeGate = on }

    /** Which sections of a patch may be sent, per the switches above. */
    private fun allowedForPreset(param: KatanaParam): Boolean = when (param.category) {
        KatanaParams.AMP_SECTION -> writeAmpBlock
        KatanaParams.NS_SECTION -> writeGate
        else -> writeEffects
    }
    /**
     * true => the Activity swallows hardware volume/media key events. Guards
     * against phantom key presses induced by ground-loop noise when an analog
     * AUX cable and USB are connected at the same time.
     */
    var lockHardwareKeys by mutableStateOf(false)
        private set

    fun setKeyLock(on: Boolean) { lockHardwareKeys = on }

    init {
        KatanaParams.ALL.forEach { paramValues[it.id] = it.default }
        refreshDevices()
        refreshPatches()
        refreshRawPatches()
        pickedTracks.addAll(trackStore.list().filterNot { it.fromLibrary })
        rebuildTrackList()
        jam.onLog = { line -> logAction("", line) }
        lastCrash = KatanaApplication.lastCrash(app)
        // Pull the phone's music in straight away when access is already
        // granted, so the Jam tab is populated before it is even opened.
        scanLibrary(hasMusicPermission())
    }

    fun hasMusicPermission(): Boolean = runCatching {
        ContextCompat.checkSelfPermission(app, MusicLibrary.permission()) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun dismissCrashReport() {
        KatanaApplication.clearCrash(app)
        lastCrash = null
    }

    // --- Device discovery / connection -----------------------------------
    fun refreshDevices() {
        devices.clear()
        val found = runCatching { usbManager.deviceList.values.toList() }.getOrDefault(emptyList())
        found.filter { it.vendorId == ROLAND_VENDOR_ID }
            .forEach {
                devices.add(
                    DeviceInfo(
                        it,
                        "${it.productName ?: "Roland/BOSS"}  " +
                            "(VID %04X / PID %04X)".format(it.vendorId, it.productId),
                    ),
                )
            }
        if (devices.isEmpty() && !connected) {
            status = "Устройство Roland не найдено. Включи комбик, удерживая [BOOSTER], и подключи кабель."
        }
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    // --- Auto-reconnect ---------------------------------------------------
    // A USB-C phone connector under a guitar cable's worth of tugging drops the
    // link regularly, and the app used to just sit there saying "отключён"
    // until you noticed and re-tapped Подключить. Now it comes back by itself.
    private var lastDevice: UsbDevice? = null
    private var autoReconnect = true
    private var reconnectAttempts = 0
    private val reconnectRunnable = Runnable { attemptReconnect() }
    private val noResponseRunnable = Runnable {
        if (connected && !gotData) {
            noResponse = true
            appendLog(
                "⚠ Нет данных от комбика. 1) Проверь, что он включён с зажатым [BOOSTER] " +
                    "(режим USB-MIDI). 2) Покрути любую ручку на самом комбике — приложение " +
                    "выучит правильный заголовок Gen 3 из его ответа.",
            )
        }
    }

    fun connect(device: UsbDevice) {
        teardown()
        autoReconnect = true
        lastDevice = device
        val conn = UsbMidiConnection(usbManager, device)
        val ctl = KatanaController(conn)
        ctl.onTraffic = { dir, sysex ->
            bumpCount(dir == "RX")
            appendLog("$dir  ${KatanaSysEx.toHex(sysex)}")
        }
        ctl.onIncoming = { incoming ->
            onMain {
                if (!gotData) { gotData = true; reconnectAttempts = 0 }
                applyIncoming(incoming)
            }
        }
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

        conn.onDisconnect = {
            // Reader thread died (USB glitch/unplug). Tear the connection down
            // FULLY off the main thread, so we don't leave a half-dead pipe
            // (sender alive, reader dead, interface still claimed) that would
            // make the app look "stuck" and block a future reconnect.
            onMain {
                if (connection === conn) {
                    val dead = controller
                    controller = null; connection = null
                    connected = false
                    status = "Комбик отключён (проверь кабель)"
                    appendLog("— соединение потеряно —")
                    Thread { runCatching { dead?.shutdown() }; runCatching { conn.close() } }
                        .apply { isDaemon = true }.start()
                    scheduleReconnect()
                }
            }
        }
        val error = conn.open(onSysEx = { sysex -> ctl.handleInbound(sysex) })
        if (error != null) {
            status = "Не удалось подключиться: $error"
            connected = false
            scheduleReconnect()
            return
        }
        connection = conn
        controller = ctl
        connected = true
        connectedLabel = device.productName ?: "BOSS Katana"
        status = "Подключено: $connectedLabel"
        txAtomic.set(0); rxAtomic.set(0); txCount = 0; rxCount = 0
        noResponse = false; gotData = false
        appendLog("— подключено (PID %04X) —".format(device.productId))
        appendLog("USB: ${conn.diagnostics}")
        appendLog("— рукопожатие + автоопределение диалекта —")
        ctl.begin()
        // After the handshake, if we still have no real data the amp is either
        // not in USB-MIDI mode or needs a physical knob nudge.
        mainHandler.removeCallbacks(noResponseRunnable)
        mainHandler.postDelayed(noResponseRunnable, NO_RESPONSE_MS)
    }

    private fun scheduleReconnect() {
        if (!autoReconnect) return
        if (lastDevice == null) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            status = "Связь потеряна. Проверь кабель и нажми «Обновить список»."
            return
        }
        reconnectAttempts++
        val delay = RECONNECT_STEP_MS * reconnectAttempts
        status = "Переподключение через ${delay / 1000} с (попытка $reconnectAttempts)…"
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, delay)
    }

    private fun attemptReconnect() {
        if (!autoReconnect || connected) return
        val wanted = lastDevice ?: return
        refreshDevices()
        // Re-plugging gives the amp a new UsbDevice instance, so match on the
        // stable device name rather than on object identity.
        val fresh = devices.firstOrNull { it.device.deviceName == wanted.deviceName }?.device
        when {
            fresh == null -> scheduleReconnect()
            !usbManager.hasPermission(fresh) -> {
                status = "Комбик найден, но нужно разрешение USB — нажми «Подключить»."
                appendLog("— переподключение: нет разрешения USB —")
            }
            else -> {
                appendLog("— автопереподключение (попытка $reconnectAttempts) —")
                connect(fresh)
            }
        }
    }

    /** User-initiated disconnect: stop trying to come back. */
    fun disconnect() {
        autoReconnect = false
        reconnectAttempts = 0
        mainHandler.removeCallbacks(reconnectRunnable)
        val was = connected
        teardown()
        if (was) appendLog("— отключено —")
        status = "Не подключено"
    }

    /** Close the link without touching the auto-reconnect decision. */
    private fun teardown() {
        mainHandler.removeCallbacks(noResponseRunnable)
        controller?.shutdown()
        controller = null
        connection?.close()
        connection = null
        connected = false
        connectedLabel = ""
    }

    // --- Action log (what the user pressed, address used, sent or not) ----
    private var lastActionKey = ""

    private fun logAction(key: String, text: String) = synchronized(actionLog) {
        if (key.isNotEmpty() && key == lastActionKey && actionLog.isNotEmpty()) {
            actionLog[actionLog.size - 1] = text
        } else {
            actionLog.addLast(text)
            while (actionLog.size > 600) actionLog.removeFirst()
        }
        lastActionKey = key
    }

    private fun addrHex(a: IntArray) = a.joinToString(" ") { "%02X".format(it and 0xFF) }

    fun clearActionLog() = synchronized(actionLog) { actionLog.clear(); lastActionKey = "" }

    fun actionLogText(): String = buildString {
        append("$APP_NAME — журнал действий\n")
        append("Подключено: $connected ($connectedLabel)  ID: ${identityInfo.ifEmpty { "—" }}\n")
        append("Профиль: ${KatanaSysEx.generation}  заголовок ${KatanaSysEx.headerHex()}\n")
        append("TX=${txAtomic.get()} RX=${rxAtomic.get()}\n----\n")
        append(synchronized(actionLog) { actionLog.joinToString("\n") })
    }

    /** Push USB-thread packet counters to Compose state a few times/sec. */
    private fun bumpCount(rx: Boolean) {
        if (rx) rxAtomic.incrementAndGet() else txAtomic.incrementAndGet()
        if (!countsPostScheduled) {
            countsPostScheduled = true
            mainHandler.postDelayed({
                countsPostScheduled = false
                txCount = txAtomic.get(); rxCount = rxAtomic.get()
            }, 300)
        }
    }

    // --- Controls ---------------------------------------------------------
    fun setParam(param: KatanaParam, value: Int) {
        val stored = KatanaParams.sanitize(param, value)
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

    /** Current dialect, for the diagnostics screen. */
    val profileLabel: String
        get() = "${KatanaSysEx.generation}  ${KatanaSysEx.headerHex()}"

    /**
     * Which amp-type field the amp turned out to expose. The two use
     * incompatible codes, so this decides what a character selection sends.
     */
    val ampTypeSpaceLabel: String
        get() = if (controller?.ampTypeIsPreampSpace == true) {
            "расширенное (28 моделей GT-100)"
        } else {
            "панельное (5 характеров)"
        }

    /** Pick the dialect by hand when auto-detection did not fire. */
    fun forceProfile(gen: KatanaSysEx.Gen) {
        KatanaSysEx.forceGeneration(gen)
        appendLog("— профиль вручную: $gen ${KatanaSysEx.headerHex()} —")
        logAction("", "Профиль вручную: $gen (${KatanaSysEx.headerHex()})")
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

    // --- Diagnostics: raw blocks the amp reported ------------------------
    /**
     * Every block of bytes the amp has reported, keyed by the address it came
     * from. This is the raw truth about the Gen 3 address map, which is still
     * partly guessed: the presets can be perfect and still land wrong if a
     * value goes to the wrong address, and reading the amp back is the only way
     * to tell the two apart.
     */
    val blocks = mutableStateMapOf<String, List<Int>>()
    private var blockSnapshot: Map<String, List<Int>> = emptyMap()
    var diffReport by mutableStateOf("")
        private set

    /** Remember the current bytes, so a physical knob turn can be spotted. */
    fun snapshotBlocks() {
        blockSnapshot = blocks.mapValues { it.value.toList() }
        diffReport = "Снимок сделан (${blockSnapshot.size} блоков). Теперь покрути " +
            "ручку на самом комбике, нажми «Прочитать» и потом «Сравнить»."
    }

    /** Report every byte that changed since [snapshotBlocks], with its address. */
    fun compareBlocks() {
        if (blockSnapshot.isEmpty()) {
            diffReport = "Сначала нажми «Снимок»."
            return
        }
        val report = StringBuilder()
        for ((key, after) in blocks.entries.sortedBy { it.key }) {
            val before = blockSnapshot[key] ?: continue
            for (i in after.indices) {
                val old = before.getOrNull(i) ?: continue
                if (old == after[i]) continue
                val base = runCatching { KatanaSysEx.fromHex(key).map { it.toInt() and 0xFF }.toIntArray() }
                    .getOrNull() ?: continue
                if (base.size != 4) continue
                val full = addrHex(KatanaSysEx.addrPlus(base, i))
                report.append("$full : $old → ${after[i]}   (блок $key, байт +$i)\n")
            }
        }
        diffReport = if (report.isEmpty()) {
            "Изменений нет. Покрути ручку на комбике и нажми «Прочитать», потом «Сравнить»."
        } else {
            "Изменились байты:\n$report"
        }
    }

    fun readNamedBlock(block: DiagBlock) {
        val ctl = controller
        if (ctl == null) { diffReport = "Нет связи с комбиком"; return }
        ctl.readBlock(block.address, block.size)
        diffReport = "Запрошен ${block.label} (${addrHex(block.address)}, ${block.size} байт)"
    }

    /**
     * Write an amp-type value directly, bypassing the preset machinery, so the
     * mapping between our list and what the amp actually does can be heard one
     * index at a time.
     */
    fun sendAmpTypeRaw(index: Int): String {
        val ctl = controller ?: return "Нет связи"
        ctl.setParam(KatanaParams.AMP_TYPE, index)
        return "Отправлен тип $index → ${addrHex(ctl.resolveAddress(KatanaParams.AMP_TYPE))}"
    }

    fun diagnosticsText(): String = buildString {
        append("$APP_NAME — диагностика адресов\n")
        append("Профиль: ${KatanaSysEx.generation}  заголовок ${KatanaSysEx.headerHex()}\n")
        append("ID: ${identityInfo.ifEmpty { "—" }}  TX=${txAtomic.get()} RX=${rxAtomic.get()}\n")
        append("Блоков получено: ${blocks.size}\n----\n")
        for ((key, bytes) in blocks.entries.sortedBy { it.key }) {
            append("$key : ${bytes.joinToString(" ") { "%02X".format(it) }}\n")
            append("          дес: ${bytes.joinToString(" ")}\n")
        }
        if (diffReport.isNotEmpty()) append("----\n$diffReport\n")
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
        blocks[addrHex(addr)] = data.map { it and 0x7F }
        for (p in KatanaParams.ALL) {
            // Match against both the MkII and Gen 3 address of the parameter.
            for (pa in listOfNotNull(p.address, p.addrGen3)) {
                if (pa[0] != addr[0] || pa[1] != addr[1] || pa[2] != addr[2]) continue
                val offset = pa[3] - addr[3]
                if (offset < 0 || offset >= data.size) continue
                val v = if (p.word && offset + 1 < data.size) {
                    ((data[offset] and 0x7F) shl 7) or (data[offset + 1] and 0x7F)
                } else {
                    data[offset] and 0x7F
                }
                // Skip no-op writes: the amp echoes back every value we send, so
                // most inbound frames match what we already have — updating the
                // snapshot map anyway would trigger needless recomposition.
                if (paramValues[p.id] != v) paramValues[p.id] = v
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

    /**
     * Load a patch: knobs and types first, on/off switches last, as one atomic
     * batch (see [KatanaController.applyPreset]). The whole parameter set is
     * always sent — a patch never inherits anything from the previous tone.
     */
    fun applyPatch(patch: Patch) {
        val entries = FactoryPresets.loadOrder(patch.values).filter { allowedForPreset(it.first) }
        if (entries.isEmpty()) {
            presetStatus = "Нечего отправлять: все разделы выключены"
            return
        }
        // Reflect it locally first so the UI is right even with no cable.
        entries.forEach { (p, v) -> paramValues[p.id] = v }
        activePreset = patch.name
        val ctl = controller
        val skipped = KatanaParams.ALL.size - entries.size
        if (ctl == null) {
            presetStatus = "«${patch.name}»: нет связи — значения только в приложении"
            logAction("", "Пресет '${patch.name}': нет связи (${entries.size} параметров локально)")
            return
        }
        presetLoading = patch.name
        presetStatus = "Загружаю «${patch.name}»…"
        appendLog("— применяю пресет '${patch.name}' (${entries.size} параметров) —")
        ctl.applyPreset(entries) { sent, total ->
            onMain {
                presetLoading = null
                presetStatus = if (sent == total) {
                    "Загружен: «${patch.name}» ($sent сообщений)"
                } else {
                    "«${patch.name}» прерван на $sent из $total — загрузи ещё раз"
                }
                logAction("", "Пресет '${patch.name}': отправлено $sent из $total сообщений" +
                    if (skipped > 0) " (пропущено параметров: $skipped)" else "")
            }
        }
    }

    /**
     * Keep the tone as it stands right now under a name based on [presetName].
     *
     * The per-preset knobs edit the live tone, which lasts until the next
     * preset load; this is how a fix that worked survives. Returns the name it
     * was saved under.
     */
    fun saveTunedPreset(presetName: String): String {
        val base = presetName.removePrefix("★ ").trim().ifBlank { "Мой тон" }
        var name = "$base (мой)"
        var n = 2
        while (patches.any { it.name == name }) {
            name = "$base (мой $n)"
            n++
        }
        capturePatch(name)
        logAction("", "Сохранён свой вариант пресета: «$name»")
        return name
    }

    fun deletePatch(name: String) {
        patchStore.delete(name)
        refreshPatches()
    }

    // --- Raw tones captured from the amp ---------------------------------
    // The one way to get a tone back exactly as it sounded without knowing
    // what each address means: read the amp's live area, keep the bytes, write
    // the same bytes to the same addresses later. Our labels for those bytes
    // can be wrong and the recall is still faithful.
    val rawPatches = mutableStateListOf<RawPatch>()
    var capturing by mutableStateOf(false)
        private set
    var captureStatus by mutableStateOf("")
        private set

    private val captureRunnable = Runnable { finishCapture() }
    private var captureName = ""

    fun refreshRawPatches() {
        rawPatches.clear()
        rawPatches.addAll(rawStore.list())
    }

    /**
     * Read the amp's live area and store it under [name].
     *
     * The read is a burst of requests whose replies arrive over the next
     * second or two, so the capture is taken on a timer once they have landed.
     */
    fun captureFromAmp(name: String) {
        val ctl = controller
        if (ctl == null) {
            captureStatus = "Нет связи с комбиком"
            return
        }
        captureName = name.trim().ifBlank { "Тон с комбика" }
        capturing = true
        captureStatus = "Читаю комбик…"
        blocks.clear()
        ctl.readAll()
        mainHandler.removeCallbacks(captureRunnable)
        mainHandler.postDelayed(captureRunnable, CAPTURE_WAIT_MS)
    }

    private fun finishCapture() {
        capturing = false
        val live = RawPatch.liveAreaOnly(blocks.mapValues { it.value.toList() })
        if (live.isEmpty()) {
            captureStatus = "Комбик не ответил — проверь связь на вкладке «Патч» " +
                "и профиль в «Диагностике»"
            return
        }
        var name = captureName
        var n = 2
        while (rawPatches.any { it.name == name }) { name = "$captureName $n"; n++ }
        val patch = RawPatch(
            name = name,
            blocks = live,
            note = "Снято с комбика · ${live.size} блоков, ${live.values.sumOf { it.size }} байт",
        )
        rawStore.save(patch)
        refreshRawPatches()
        captureStatus = "Сохранено: «$name» (${patch.byteCount} байт)"
        logAction("", "Снят тон с комбика: «$name», ${live.size} блоков")
    }

    /** Write a captured tone back, byte for byte. */
    fun applyRawPatch(patch: RawPatch) {
        val ctl = controller
        if (ctl == null) {
            captureStatus = "Нет связи с комбиком"
            return
        }
        var sent = 0
        for ((addrHex, bytes) in RawPatch.writeOrder(patch.blocks)) {
            val addr = runCatching {
                KatanaSysEx.fromHex(addrHex).map { it.toInt() and 0xFF }.toIntArray()
            }.getOrNull() ?: continue
            if (addr.size != 4) continue
            ctl.writeBlock(addr, bytes.map { it and 0x7F }.toIntArray())
            sent += bytes.size
        }
        activePreset = patch.name
        captureStatus = "Отправлен тон «${patch.name}»: $sent байт"
        logAction("", "Записан снятый тон «${patch.name}»: $sent байт")
    }

    fun deleteRawPatch(name: String) {
        rawStore.delete(name)
        refreshRawPatches()
    }

    // --- Jam tracks -------------------------------------------------------

    /**
     * Read the phone's music library.
     *
     * The cursor walks every audio file, so it runs on its own thread — on a
     * phone with a few thousand tracks doing this on the main thread is a
     * visible freeze.
     */
    fun scanLibrary(hasPermission: Boolean) {
        musicAccess = hasPermission
        if (!hasPermission) {
            libraryTracks = emptyList()
            libraryStatus = "Нет доступа к музыке телефона"
            rebuildTrackList()
            return
        }
        if (scanningLibrary) return
        scanningLibrary = true
        libraryStatus = "Читаю музыку телефона…"
        Thread {
            val found = runCatching { MusicLibrary.query(app) }
            onMain {
                scanningLibrary = false
                libraryTracks = found.getOrDefault(emptyList())
                libraryStatus = when {
                    found.isFailure -> "Не удалось прочитать медиатеку: ${found.exceptionOrNull()?.message}"
                    libraryTracks.isEmpty() -> "Музыка на телефоне не найдена — добавь файл кнопкой «+ файл»"
                    else -> "Музыка телефона: ${libraryTracks.size}"
                }
                rebuildTrackList()
                logAction("", "Джем: медиатека — ${libraryTracks.size} треков")
            }
        }.apply { isDaemon = true }.start()
    }

    private fun rebuildTrackList() {
        val merged = Tracks.merge(libraryTracks, pickedTracks)
        tracks.clear()
        tracks.addAll(merged)
    }

    /**
     * Add a file chosen through the system picker.
     *
     * The picker asks for a PERSISTABLE grant (see [com.raznoe.katana.ui.PickAudio]),
     * so taking it here succeeds and the track still opens after a restart. It
     * used to throw and be swallowed, which is why hand-added tracks "lost
     * access to the file" later on.
     */
    fun addTracks(uris: List<Uri>) {
        var added = 0
        var failed = 0
        for (uri in uris) {
            val held = runCatching {
                app.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            if (held.isFailure) {
                failed++
                logAction("", "Джем: не удалось закрепить доступ к $uri: ${held.exceptionOrNull()?.message}")
            }
            val key = uri.toString()
            if (pickedTracks.any { it.uri == key } || libraryTracks.any { it.uri == key }) continue
            val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "трек"
            pickedTracks.add(Track(uri = key, name = name))
            added++
        }
        runCatching { trackStore.saveAll(pickedTracks.toList()) }
        rebuildTrackList()
        libraryStatus = when {
            uris.isEmpty() -> "Ничего не выбрано"
            added == 0 -> "Уже в списке"
            failed > 0 -> "Добавлено: $added (у $failed не закрепился доступ)"
            else -> "Добавлено: $added"
        }
        logAction("", "Джем: добавлено файлов — $added из ${uris.size}")
    }

    /** Hand-picked tracks can be removed; library tracks come back on rescan. */
    fun removeTrack(track: Track) {
        if (track.fromLibrary) return
        if (jam.trackUri == track.uri) jam.stop()
        pickedTracks.removeAll { it.uri == track.uri }
        runCatching { trackStore.saveAll(pickedTracks.toList()) }
        rebuildTrackList()
    }

    fun playTrack(track: Track) = jam.play(track.uri, track.name)

    /** The track the player currently holds, as an entry of [tracks]. */
    val playingTrack: Track?
        get() = jam.trackUri?.let { uri -> tracks.firstOrNull { it.uri == uri } }

    fun playNext() = step(+1)
    fun playPrev() = step(-1)

    /** Move [delta] tracks through the visible list, wrapping at the ends. */
    private fun step(delta: Int) {
        if (tracks.isEmpty()) return
        val current = jam.trackUri?.let { uri -> tracks.indexOfFirst { it.uri == uri } } ?: -1
        val next = if (current < 0) 0 else (current + delta).mod(tracks.size)
        playTrack(tracks[next])
    }

    private fun queryDisplayName(uri: Uri): String? =
        runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()

    // --- Log --------------------------------------------------------------
    fun clearLog() = synchronized(log) { log.clear() }

    /** Full log as shareable text (for sending diagnostics). */
    fun logText(): String = buildString {
        append("$APP_NAME — диагностика\n")
        append("Подключено: $connected  ($connectedLabel)\n")
        append("ID: ${identityInfo.ifEmpty { "—" }}\n")
        append("TX=${txAtomic.get()}  RX=${rxAtomic.get()}  noResponse=$noResponse\n")
        append("Профиль modelId=0x%02X\n".format(KatanaSysEx.modelId))
        append("Звук: выход=${jam.output.label} маршрут=${jam.routeLabel}\n")
        lastCrash?.let { append("--- последний сбой ---\n$it\n") }
        append("----\n")
        append(synchronized(log) { log.joinToString("\n") })
    }

    // Off the main thread, cheap, capped — called for every USB packet.
    private fun appendLog(line: String) = synchronized(log) {
        log.addLast(line)
        while (log.size > MAX_LOG_LINES) log.removeFirst()
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    override fun onCleared() {
        // The player deliberately outlives the ViewModel: the Activity being
        // recreated (rotation, USB attach intent) must not cut the track off.
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(noResponseRunnable)
        mainHandler.removeCallbacks(captureRunnable)
        autoReconnect = false
        teardown()
        super.onCleared()
    }

    companion object {
        const val APP_NAME = "Katana by Vlad_i_c"
        private const val MAX_LOG_LINES = 400
        private const val NO_RESPONSE_MS = 6_000L
        private const val RECONNECT_STEP_MS = 1_500L
        private const val MAX_RECONNECT_ATTEMPTS = 5

        /** How long to let the amp's replies land before taking a capture. */
        private const val CAPTURE_WAIT_MS = 2_500L
    }
}
