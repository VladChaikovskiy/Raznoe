package com.raznoe.katana.ui

import android.content.Intent
import android.hardware.usb.UsbDevice
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.raznoe.katana.DIAG_BLOCKS
import com.raznoe.katana.DeviceInfo
import com.raznoe.katana.KatanaViewModel
import com.raznoe.katana.audio.JamOutput
import com.raznoe.katana.model.FactoryPresets
import com.raznoe.katana.model.MusicLibrary
import com.raznoe.katana.model.Patch
import com.raznoe.katana.model.Track
import com.raznoe.katana.model.Tracks
import com.raznoe.katana.protocol.KatanaParam
import com.raznoe.katana.protocol.KatanaParams
import com.raznoe.katana.protocol.KatanaSysEx
import com.raznoe.katana.protocol.ParamKind
import kotlinx.coroutines.delay

private data class Block(val key: String, val short: String, val color: Color, val swId: String?)

private val CHAIN = listOf(
    Block("Noise Suppressor", "GATE", Nux.Gate, "ns_sw"),
    Block("Booster", "BST", Nux.Boost, "boost_sw"),
    Block("Усилитель", "AMP", Nux.Amp, null),
    Block("Mod", "MOD", Nux.Mod, "mod_sw"),
    Block("FX", "FX", Nux.Fx, "fx_sw"),
    Block("Delay", "DLY", Nux.Delay, "delay_sw"),
    Block("Reverb", "RVB", Nux.Reverb, "reverb_sw"),
)

@Composable
fun KatanaApp(vm: KatanaViewModel, onConnectRequest: (UsbDevice) -> Unit) {
    var screen by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(Nux.Bg)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (screen) {
                0 -> PatchScreen(vm, onConnectRequest)
                1 -> TogglesScreen(vm)
                2 -> PresetsScreen(vm)
                3 -> JamScreen(vm)
                4 -> LibraryScreen(vm)
                else -> DiagnosticsScreen(vm)
            }
        }
        BottomNav(screen) { screen = it }
    }
}

@Composable
private fun BottomNav(selected: Int, onSelect: (Int) -> Unit) {
    val items = listOf("Патч", "Тумблеры", "Пресеты", "Джем", "Библиотека", "Диагностика")
    Row(
        Modifier.fillMaxWidth().background(Nux.Panel)
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items.forEachIndexed { i, label ->
            val c = if (i == selected) Nux.Orange else Nux.TextLo
            Text(
                label, color = c, fontSize = 12.sp,
                fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clickable { onSelect(i) }.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun DeviceTitle() {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(
            "K A T A N A",
            color = Nux.TextLo,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
        Text("by Vlad_i_c", color = Nux.Orange, fontSize = 11.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PatchScreen(vm: KatanaViewModel, onConnectRequest: (UsbDevice) -> Unit) {
    var selectedBlock by remember { mutableIntStateOf(2) } // AMP
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        DeviceTitle()
        CrashBanner(vm)
        ConnectionStrip(vm, onConnectRequest)

        // Channels
        Panel {
            Text("Каналы", color = Nux.TextLo, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KatanaParams.CHANNELS.forEachIndexed { i, (_, _) ->
                    RoundButton(if (i == 0) "P" else "$i", vm.currentChannel == i) {
                        vm.selectChannel(i)
                    }
                }
            }
        }

        // Signal chain
        Panel {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CHAIN.forEachIndexed { i, b ->
                    val on = b.swId?.let { (vm.paramValues[it] ?: 0) != 0 } ?: true
                    ChainChip(b, selected = i == selectedBlock, on = on) { selectedBlock = i }
                }
            }
        }

        BlockEditor(vm, CHAIN[selectedBlock])

        Text(
            "Gen 3: усилитель, вкл/выкл эффектов и параметры бустера/дилея/ревера/гейта " +
                "используют реальные адреса Gen 3 (эффекты — с учётом слота FX-BOX). " +
                "Параметры Mod/FX (тип и настройки) на Gen 3 устроены сложнее и пока в работе. " +
                "Что уходит в комбик и что он отвечает — на вкладке «Диагностика».",
            color = Nux.TextLo, fontSize = 12.sp,
        )
    }
}

@Composable
private fun ChainChip(b: Block, selected: Boolean, on: Boolean, onClick: () -> Unit) {
    val border = if (selected) b.color else Nux.Stroke
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Nux.PanelHi)
                .border(2.dp, border, RoundedCornerShape(12.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(b.short, color = if (on) b.color else Nux.TextLo, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Box(
            Modifier.padding(top = 4.dp).size(7.dp).clip(RoundedCornerShape(4.dp))
                .background(if (on) b.color else Nux.Stroke),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockEditor(vm: KatanaViewModel, block: Block) {
    val params = KatanaParams.BY_CATEGORY[block.key].orEmpty()
    Panel(accent = block.color) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(block.key.uppercase(), color = block.color, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            block.swId?.let { sw ->
                val on = (vm.paramValues[sw] ?: 0) != 0
                OnOffPills(on, block.color) { vm.setParam(KatanaParams.BY_ID[sw]!!, if (it) 1 else 0) }
            }
        }

        // enums (type selectors) and secondary toggles
        params.filter { it.id != block.swId }.forEach { p ->
            when (p.kind) {
                ParamKind.ENUM -> {
                    val v = vm.paramValues[p.id] ?: p.default
                    Text(p.label + mark(p), color = Nux.TextLo, fontSize = 13.sp)
                    ChipRow(p.options, p.indexOfValue(v), block.color) { idx ->
                        vm.setParam(p, p.valueOfIndex(idx))
                    }
                }
                ParamKind.TOGGLE -> {
                    val on = (vm.paramValues[p.id] ?: 0) != 0
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(p.label + mark(p), color = Nux.TextHi)
                        OnOffPills(on, block.color) { vm.setParam(p, if (it) 1 else 0) }
                    }
                }
                ParamKind.CONTINUOUS -> {}
            }
        }

        // continuous params as knobs
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            params.filter { it.kind == ParamKind.CONTINUOUS }.forEach { p ->
                val v = vm.paramValues[p.id] ?: p.default
                Knob(p.label + mark(p), v, p.min, p.max, block.color) { vm.setParam(p, it) }
            }
        }
    }
}

private fun mark(p: KatanaParam) = if (!p.verified) " (?)" else ""

/**
 * Shown once after the app has gone down, with the trace ready to copy. The
 * phone is in a rehearsal room with no logcat attached, so this is the only way
 * a crash ever gets reported.
 */
@Composable
private fun CrashBanner(vm: KatanaViewModel) {
    val crash = vm.lastCrash ?: return
    val clipboard = LocalClipboardManager.current
    Panel(accent = Nux.Amp) {
        Text("⚠ В прошлый раз приложение упало", color = Nux.Amp, fontWeight = FontWeight.SemiBold)
        Text(
            crash.lineSequence().take(6).joinToString("\n"),
            color = Nux.TextLo, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Скопировать", selected = true, accent = Nux.Amp) {
                clipboard.setText(AnnotatedString(crash))
            }
            Pill("Скрыть", selected = false, accent = Nux.Amp) { vm.dismissCrashReport() }
        }
    }
}

@Composable
private fun ConnectionStrip(vm: KatanaViewModel, onConnectRequest: (UsbDevice) -> Unit) {
    Panel {
        if (vm.connected) {
            Text("● ${vm.connectedLabel}", color = Nux.Orange, fontWeight = FontWeight.SemiBold)
            if (vm.identityInfo.isNotEmpty()) {
                Text("ID: ${vm.identityInfo}", color = Nux.TextLo, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace)
            }
            Text(
                "Связь: TX ${vm.txCount} · RX ${vm.rxCount}" +
                    if (vm.gotData) "  ✓ данные идут" else "",
                color = if (vm.gotData) Nux.Gate else Nux.TextLo, fontSize = 12.sp,
            )
            if (vm.noResponse && !vm.gotData) {
                Text(
                    "⚠ Нет данных. 1) Включи комбик, УДЕРЖИВАЯ [BOOSTER] (режим USB-MIDI). " +
                        "2) Покрути любую ручку на комбике — приложение выучит заголовок Gen 3.",
                    color = Nux.Amp, fontSize = 12.sp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("Прочитать", selected = false, accent = Nux.Orange) { vm.readCurrentState() }
                Pill("Отключить", selected = true, accent = Nux.Orange) { vm.disconnect() }
            }
        } else {
            Text(
                "Включи Katana, удерживая [BOOSTER] (режим USB-MIDI), подключи кабель USB-C.",
                color = Nux.TextLo, fontSize = 12.sp,
            )
            Pill("Обновить список", selected = false, accent = Nux.Orange) { vm.refreshDevices() }
            vm.devices.forEach { d: DeviceInfo ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(d.label, color = Nux.TextHi, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Pill("Подключить", selected = true, accent = Nux.Orange) { onConnectRequest(d.device) }
                }
            }
        }
    }
}

@Composable
private fun TogglesScreen(vm: KatanaViewModel) {
    val toggles = KatanaParams.ALL.filter { it.kind == ParamKind.TOGGLE }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DeviceTitle()
        Text("Тумблеры", color = Nux.TextHi, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(
            "Все переключатели вкл/выкл в одном месте (блоки эффектов, Solo, Noise Suppressor).",
            color = Nux.TextLo, fontSize = 12.sp,
        )
        Panel {
            toggles.forEach { p ->
                val on = (vm.paramValues[p.id] ?: 0) != 0
                val accent = blockColorFor(p.category)
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.label + mark(p), color = Nux.TextHi)
                        Text(p.category, color = Nux.TextLo, fontSize = 11.sp)
                    }
                    OnOffPills(on, accent) { vm.setParam(p, if (it) 1 else 0) }
                }
            }
        }
    }
}

private fun blockColorFor(category: String): Color = CHAIN.firstOrNull { it.key == category }?.color ?: Nux.Orange

/** Short block name ("GATE", "DLY"), so a pill does not have to hold "Noise Suppressor". */
private fun blockShortFor(category: String): String =
    CHAIN.firstOrNull { it.key == category }?.short ?: category

@Composable
private fun PresetsScreen(vm: KatanaViewModel) {
    // Which preset has its tuner open. Loading a preset opens its tuner, so
    // the knobs that fix the tone are right there instead of on another tab.
    var tuning by remember { mutableStateOf("") }
    var showScope by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DeviceTitle()
        Text("Пресеты", color = Nux.TextHi, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(
            "★ — оригинальные демо-патчи BOSS/JuCaNeRy, остальные — мои версии. " +
                "Жми «Загрузить», а если звук не тот — открой «▾ Подстроить» под пресетом " +
                "и правь ручками прямо здесь: слышно сразу.",
            color = Nux.TextLo, fontSize = 12.sp,
        )
        if (vm.presetStatus.isNotEmpty()) {
            Text(
                vm.presetStatus,
                color = if (vm.presetLoading != null) Nux.Orange else Nux.Gate,
                fontSize = 12.sp,
            )
        }
        FactoryPresets.ALL.forEach { p ->
            val active = vm.activePreset == p.name
            val loading = vm.presetLoading == p.name
            Panel(accent = if (active) Nux.Gate else Nux.Stroke) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            (if (active) "▶ " else "") + p.name,
                            color = if (active) Nux.Gate else Nux.TextHi,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (p.note.isNotEmpty()) Text(p.note, color = Nux.TextLo, fontSize = 11.sp)
                    }
                    val label = when {
                        loading -> "…"
                        active -> "Активен"
                        else -> "Загрузить"
                    }
                    Pill(label, selected = active || loading, accent = Nux.Orange) {
                        vm.applyPatch(p)
                        tuning = p.name
                    }
                }
                Pill(
                    if (tuning == p.name) "▴ Свернуть" else "▾ Подстроить",
                    selected = tuning == p.name,
                    accent = Nux.Amp,
                ) {
                    tuning = if (tuning == p.name) "" else p.name
                }
                if (tuning == p.name) PresetTuner(vm, p)
            }
        }

        // The per-section switches are a diagnostic, not a control anybody
        // needs day to day, so they sit at the very bottom out of the way.
        Panel(accent = Nux.Stroke) {
            Pill(
                if (showScope) "▴ Скрыть диагностику" else "▾ Диагностика: что пресет меняет",
                selected = showScope,
                accent = Nux.Stroke,
            ) {
                showScope = !showScope
            }
            if (showScope) {
                Text(
                    "Если тон всё равно не тот — выключи «Усилитель»: пресет перестанет " +
                        "трогать тип усилителя и EQ и поставит только эффекты. Станет нормально " +
                        "— значит адреса блока усилителя у Gen 3 всё ещё не те, скажи мне.",
                    color = Nux.TextLo, fontSize = 11.sp,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Усилитель (тип, gain, EQ, Level)", color = Nux.TextHi, fontSize = 13.sp,
                        modifier = Modifier.weight(1f))
                    OnOffPills(on = vm.writeAmpBlock, accent = Nux.Amp) { vm.allowAmpBlock(it) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Эффекты (бустер, дилей, ревер)", color = Nux.TextHi, fontSize = 13.sp,
                        modifier = Modifier.weight(1f))
                    OnOffPills(on = vm.writeEffects, accent = Nux.Boost) { vm.allowEffects(it) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Шумодав", color = Nux.TextHi, fontSize = 13.sp,
                        modifier = Modifier.weight(1f))
                    OnOffPills(on = vm.writeGate, accent = Nux.Gate) { vm.allowGate(it) }
                }
            }
        }
    }
}

/**
 * The knobs that fix a preset, under the preset itself.
 *
 * A preset that lands wrong used to mean a trip to the Патч tab to hunt for
 * the right block, or waiting for me to change a value in code and rebuild.
 * These edit the live tone: turning a knob sends it to the amp immediately, so
 * a boomy clean patch is a two-second fix, and "Сохранить как свой" keeps the
 * result in the library.
 *
 * Amp type is first and shows its wire index, because which index is which
 * character on Gen 3 is still unconfirmed — if 1 is not Clean here, that is
 * worth knowing and the number is what tells me.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetTuner(vm: KatanaViewModel, preset: Patch) {
    var savedAs by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Nux.Stroke))

        val type = vm.paramValues[KatanaParams.AMP_TYPE.id] ?: 1
        Text("Тип усилителя", color = Nux.TextLo, fontSize = 12.sp)
        ChipRow(
            KatanaParams.AMP_TYPES.mapIndexed { i, name -> "$i·$name" },
            KatanaParams.AMP_TYPE.indexOfValue(type),
            Nux.Amp,
        ) { idx -> vm.setParam(KatanaParams.AMP_TYPE, KatanaParams.AMP_TYPE.valueOfIndex(idx)) }

        // Gain, EQ and Level: everything the reported problems were about.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TUNER_KNOBS.forEach { param ->
                val v = vm.paramValues[param.id] ?: param.default
                Knob(param.label, v, param.min, param.max, blockColorFor(param.category)) {
                    vm.setParam(param, it)
                }
            }
        }

        Text("Блоки", color = Nux.TextLo, fontSize = 12.sp)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TUNER_SWITCHES.forEach { param ->
                val on = (vm.paramValues[param.id] ?: 0) != 0
                Pill(blockShortFor(param.category), selected = on,
                    accent = blockColorFor(param.category)) {
                    vm.setParam(param, if (on) 0 else 1)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Pill("Сбросить к пресету", selected = false, accent = Nux.Orange) {
                vm.applyPatch(preset)
            }
            Pill("Сохранить как свой", selected = true, accent = Nux.Gate) {
                val name = vm.saveTunedPreset(preset.name)
                savedAs = "Сохранён в «Библиотеку» как «$name»"
            }
        }
        if (savedAs.isNotEmpty()) {
            Text(savedAs, color = Nux.Gate, fontSize = 11.sp)
        }
    }
}

/** Gain, EQ and Level — the controls a preset actually gets fixed with. */
private val TUNER_KNOBS = listOfNotNull(
    KatanaParams.BY_ID["gain"],
    KatanaParams.BY_ID["bass"],
    KatanaParams.BY_ID["middle"],
    KatanaParams.BY_ID["treble"],
    KatanaParams.BY_ID["presence"],
    KatanaParams.BY_ID["volume"],
    KatanaParams.BY_ID["reverb_level"],
    KatanaParams.BY_ID["delay_level"],
)

/** The block on/off switches worth reaching without leaving this tab. */
private val TUNER_SWITCHES = listOfNotNull(
    KatanaParams.BY_ID["boost_sw"],
    KatanaParams.BY_ID["delay_sw"],
    KatanaParams.BY_ID["reverb_sw"],
    KatanaParams.BY_ID["ns_sw"],
)

@Composable
private fun JamScreen(vm: KatanaViewModel) {
    val jam = vm.jam
    var search by remember { mutableStateOf("") }
    var showMixer by remember { mutableStateOf(false) }
    // Asking for the persistable grant is what makes a hand-picked file still
    // open after a restart; the stock OpenDocument contract does not.
    val picker = rememberLauncherForActivityResult(PickAudio()) { uris ->
        vm.addTracks(uris)
    }
    val musicPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> vm.scanLibrary(granted) }
    LaunchedEffect(jam.isPlaying) {
        while (jam.isPlaying) { jam.refreshPosition(); delay(400) }
    }
    // Whether a Bluetooth/USB output exists is queried from AudioManager rather
    // than held as state, so tick the screen to pick up a device that connects
    // while this tab is open.
    var routeTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2000); routeTick++ } }

    val shown = Tracks.filter(vm.tracks, search)

    // Fixed search bar on top, fixed player at the bottom, everything else in a
    // lazy list between them. The previous layout put the panels in a plain
    // Column, so on a phone screen the search field and the whole track list
    // were pushed off the bottom with no way to scroll to them — the tab looked
    // like it had no music at all.
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("поиск") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Pill("⟳", selected = vm.scanningLibrary, accent = Nux.Orange) {
                vm.scanLibrary(vm.hasMusicPermission())
            }
            Pill("+", selected = false, accent = Nux.Orange) { picker.launch(Unit) }
            Pill("🔊", selected = showMixer, accent = Nux.Orange) { showMixer = !showMixer }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Music access first: without it the list is empty and nothing
            // else on this tab matters.
            if (!vm.musicAccess) {
                item {
                    Box(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Panel(accent = Nux.Amp) {
                            Text(
                                "Дай доступ к музыке — и все треки с телефона появятся здесь сами.",
                                color = Nux.TextHi, fontSize = 12.sp,
                            )
                            Pill("Разрешить доступ к музыке", selected = true, accent = Nux.Orange) {
                                musicPermission.launch(MusicLibrary.permission())
                            }
                        }
                    }
                }
            }

            if (showMixer) {
                item {
                    Box(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        MixerPanel(vm, routeTick)
                    }
                }
            }

            item {
                Text(
                    if (vm.libraryStatus.isEmpty()) "Треков: ${shown.size}"
                    else "${vm.libraryStatus} · показано: ${shown.size}",
                    color = Nux.TextLo, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            if (shown.isEmpty()) {
                item {
                    Text(
                        if (vm.musicAccess) {
                            "Ничего не найдено. Нажми ⟳ или добавь файл кнопкой «+»."
                        } else {
                            "Список пуст, пока нет доступа к музыке."
                        },
                        color = Nux.TextLo, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }

            items(shown, key = { it.uri }) { t ->
                TrackRow(
                    track = t,
                    playing = jam.trackUri == t.uri,
                    onPlay = { vm.playTrack(t) },
                    onRemove = if (t.fromLibrary) null else ({ vm.removeTrack(t) }),
                )
            }
        }

        MiniPlayer(vm)
    }
}

/** One row of the track list: cover, title, artist and length. */
@Composable
private fun TrackRow(
    track: Track,
    playing: Boolean,
    onPlay: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onPlay)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Cover(track.artUri, 52.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    track.name,
                    color = if (playing) Nux.Orange else Nux.TextHi,
                    fontSize = 16.sp,
                    fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
                Text(
                    Tracks.subtitle(track).ifEmpty { "Неизвестно" },
                    color = Nux.TextLo, fontSize = 13.sp, maxLines = 1,
                )
            }
            // Only hand-picked files can be removed; a library track would just
            // come back on the next scan.
            onRemove?.let { remove ->
                Pill("✕", selected = false, accent = Nux.Amp, onClick = remove)
            }
        }
        Box(Modifier.fillMaxWidth().padding(start = 76.dp).height(1.dp).background(Nux.Stroke))
    }
}

/** Album art, with a music-note placeholder when the file has none. */
@Composable
private fun Cover(artUri: String?, size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(Nux.PanelHi),
        contentAlignment = Alignment.Center,
    ) {
        if (artUri.isNullOrEmpty()) {
            Text("♪", color = Nux.TextLo, fontSize = (size.value / 2.4f).sp)
        } else {
            AsyncImage(
                model = artUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The player bar pinned to the bottom of the Jam tab: cover, what is playing,
 * and prev/play/next. Tapping it opens the seek bar, loop and speed, so the
 * bar stays small without losing anything.
 */
@Composable
private fun MiniPlayer(vm: KatanaViewModel) {
    val jam = vm.jam
    val track = vm.playingTrack
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(Nux.Panel)) {
        // Progress as a hairline, so the bar costs almost no height.
        val fraction = if (jam.durationMs > 0) {
            (jam.positionMs.toFloat() / jam.durationMs).coerceIn(0f, 1f)
        } else {
            0f
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(Nux.Stroke)) {
            if (fraction > 0f) {
                Box(Modifier.fillMaxWidth(fraction).height(2.dp).background(Nux.Orange))
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Cover(track?.artUri, 44.dp)
            Column(
                Modifier.weight(1f).clickable { expanded = !expanded },
            ) {
                Text(
                    track?.name ?: jam.trackName.ifEmpty { "Ничего не выбрано" },
                    color = Nux.TextHi, fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp, maxLines = 1,
                )
                Text(
                    if (jam.waitingForRoute || jam.status.isNotEmpty()) jam.status
                    else Tracks.subtitle(track ?: Track("", "")).ifEmpty { "—" },
                    color = if (jam.waitingForRoute) Nux.Amp else Nux.TextLo,
                    fontSize = 11.sp, maxLines = 1,
                )
            }
            TransportButton("⏮") { vm.playPrev() }
            TransportButton(if (jam.isPlaying) "⏸" else "▶") { jam.togglePlayPause() }
            TransportButton("⏭") { vm.playNext() }
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                val dur = if (jam.durationMs > 0) jam.durationMs else 1
                Slider(
                    value = jam.positionMs.coerceIn(0, dur).toFloat(),
                    onValueChange = { jam.seekTo(it.toInt()) },
                    valueRange = 0f..dur.toFloat(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(fmtTime(jam.positionMs), color = Nux.TextLo, fontSize = 11.sp)
                    Text(fmtTime(jam.durationMs), color = Nux.TextLo, fontSize = 11.sp)
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Pill("⏹ Стоп", selected = false, accent = Nux.Orange) { jam.stop() }
                    Pill("Луп", selected = jam.looping, accent = Nux.Orange) { jam.toggleLoop() }
                    Pill("${jam.speed}x", selected = false, accent = Nux.Orange) { jam.cycleSpeed() }
                }
            }
        }
    }
}

@Composable
private fun TransportButton(glyph: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = Nux.TextHi, fontSize = 20.sp)
    }
}

/** Volumes and where the sound goes — the two things set once per session. */
@Composable
private fun MixerPanel(vm: KatanaViewModel, routeTick: Int) {
    val jam = vm.jam
    Panel(accent = if (jam.output == JamOutput.BLUETOOTH) Nux.Mod else Nux.Stroke) {
        Text("Куда играет минусовка", color = Nux.TextLo, fontSize = 12.sp)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            JamOutput.entries.forEach { o ->
                Pill(o.label, selected = jam.output == o, accent = Nux.Orange) {
                    jam.chooseOutput(o)
                }
            }
        }
        key(routeTick) {
            Text("Сейчас: ${jam.routeLabel}", color = Nux.TextHi, fontSize = 12.sp)
            val external = jam.routes().filter { it.bluetooth || it.usb }
            if (external.isEmpty()) {
                Text(
                    "Bluetooth и USB-аудио не подключены — играет в телефон. " +
                        "Подключи комбик в настройках Bluetooth телефона.",
                    color = Nux.TextLo, fontSize = 11.sp,
                )
            } else {
                external.forEach { r ->
                    Text("• ${r.kind}: ${r.name}", color = Nux.TextLo, fontSize = 11.sp)
                }
            }
        }

        Text("Громкость минусовки: ${(jam.volume * 100).toInt()}%",
            color = Nux.TextLo, fontSize = 12.sp)
        Slider(value = jam.volume, onValueChange = { jam.changeVolume(it) }, valueRange = 0f..1f)

        val gv = vm.paramValues[KatanaParams.VOLUME.id] ?: 0
        Text("Громкость комбика (гитара): $gv", color = Nux.TextLo, fontSize = 12.sp)
        Slider(
            value = gv.toFloat(),
            onValueChange = { vm.setParam(KatanaParams.VOLUME, it.toInt()) },
            valueRange = 0f..100f,
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Блокировать кнопки телефона", color = Nux.TextLo, fontSize = 12.sp,
                modifier = Modifier.weight(1f))
            OnOffPills(on = vm.lockHardwareKeys, accent = Nux.Orange) { vm.setKeyLock(it) }
        }
    }
}

/**
 * Reads the amp back so a wrong address can be found instead of guessed at.
 *
 * The presets can be perfect and still land wrong, because the Gen 3 address
 * map is only partly confirmed — a value written to the wrong byte changes
 * something nobody asked for. There is exactly one way to tell those two cases
 * apart without the amp in front of me: read a block, turn a knob on the amp
 * itself, read again, and see which byte moved. That byte's address is the
 * truth, and it goes straight into KatanaParams.
 */
@Composable
private fun DiagnosticsScreen(vm: KatanaViewModel) {
    val clipboard = LocalClipboardManager.current
    var typeReport by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DeviceTitle()
        Text("Диагностика адресов", color = Nux.TextHi, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        val link = if (!vm.connected) {
            "Не подключено — подключи комбик на вкладке «Патч»"
        } else {
            val data = if (vm.gotData) "✓ данные идут" else "⚠ данных нет"
            "● ${vm.connectedLabel} · TX ${vm.txCount} · RX ${vm.rxCount} · $data"
        }
        Text(link, color = if (vm.gotData) Nux.Gate else Nux.Amp, fontSize = 12.sp)

        // If this says MKII on a Gen 3 amp, every write goes to a MkII address
        // — which on Gen 3 is some other parameter. That single line explains
        // "presets do nothing" and "the tone is wrong" at once.
        Panel(accent = Nux.Orange) {
            Text("Профиль (диалект команд)", color = Nux.TextHi, fontWeight = FontWeight.SemiBold)
            Text(vm.profileLabel, color = Nux.Orange, fontSize = 13.sp,
                fontFamily = FontFamily.Monospace)
            Text(
                "Должно быть GEN3. Если стоит MKII — комбик не ответил на Identity, и все " +
                    "команды уходят по адресам MkII, то есть в чужие параметры. Поставь Gen 3 " +
                    "вручную и попробуй пресет снова.",
                color = Nux.TextLo, fontSize = 11.sp,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Gen 3", selected = false, accent = Nux.Orange) {
                    vm.forceProfile(KatanaSysEx.Gen.GEN3)
                }
                Pill("MkII", selected = false, accent = Nux.Orange) {
                    vm.forceProfile(KatanaSysEx.Gen.MKII)
                }
                Pill("Katana:GO", selected = false, accent = Nux.Orange) {
                    vm.forceProfile(KatanaSysEx.Gen.GO)
                }
            }
        }

        Panel(accent = Nux.Gate) {
            Text("Найти реальный адрес ручки", color = Nux.TextHi, fontWeight = FontWeight.SemiBold)
            Text(
                "1) «Прочитать всё» → 2) «Снимок» → 3) покрути ОДНУ ручку на самом комбике " +
                    "→ 4) «Прочитать всё» → 5) «Сравнить». Изменившийся байт и есть адрес этой ручки.",
                color = Nux.TextLo, fontSize = 11.sp,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Прочитать всё", selected = true, accent = Nux.Gate) {
                    vm.readCurrentState()
                }
                Pill("Снимок", selected = false, accent = Nux.Gate) { vm.snapshotBlocks() }
                Pill("Сравнить", selected = false, accent = Nux.Gate) { vm.compareBlocks() }
            }
            if (vm.diffReport.isNotEmpty()) {
                Text(vm.diffReport, color = Nux.TextHi, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace)
            }
        }

        Panel {
            Text("Прочитать отдельный блок", color = Nux.TextHi, fontWeight = FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DIAG_BLOCKS.forEach { b ->
                    Pill(b.label, selected = false, accent = Nux.Orange) { vm.readNamedBlock(b) }
                }
            }
        }

        // Which index is which amp character is the open question behind the
        // "clean sounds wrong" reports: our list is the MkII order, and Gen 3
        // may not agree. Sending one index at a time settles it by ear.
        Panel(accent = Nux.Amp) {
            Text("Проверка типов усилителя", color = Nux.TextHi, fontWeight = FontWeight.SemiBold)
            Text(
                "Жми по очереди и слушай, что реально включается. Скажи мне, какой номер " +
                    "даёт Clean, какой Crunch и т.д. — впишу правильный порядок.",
                color = Nux.TextLo, fontSize = 11.sp,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in 0..7) {
                    val label = KatanaParams.AMP_TYPES.getOrNull(i)?.let { "$i·$it" } ?: "$i·?"
                    Pill(label, selected = false, accent = Nux.Amp) {
                        typeReport = vm.sendAmpTypeRaw(i)
                    }
                }
            }
            if (typeReport.isNotEmpty()) {
                Text(typeReport, color = Nux.TextLo, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace)
            }
        }

        Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Что ответил комбик (${vm.blocks.size})", color = Nux.TextHi,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Pill("Копировать", selected = true, accent = Nux.Orange) {
                    clipboard.setText(AnnotatedString(vm.diagnosticsText()))
                }
            }
            if (vm.blocks.isEmpty()) {
                Text("Пусто. Подключи комбик и нажми «Прочитать всё».",
                    color = Nux.TextLo, fontSize = 12.sp)
            }
            vm.blocks.entries.sortedBy { it.key }.forEach { (addr, bytes) ->
                Text(addr, color = Nux.Orange, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text(
                    bytes.joinToString(" ") { "%02X".format(it) },
                    color = Nux.TextHi, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                )
                Text(
                    bytes.joinToString(" ") { "%3d".format(it) },
                    color = Nux.TextLo, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                )
            }
        }

        Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Журнал действий", color = Nux.TextHi, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                Pill("Копировать", selected = false, accent = Nux.Orange) {
                    clipboard.setText(AnnotatedString(vm.actionLogText()))
                }
            }
            Text(
                "Здесь видно, что уходит на комбик и по какому адресу — включая результат " +
                    "загрузки пресета.",
                color = Nux.TextLo, fontSize = 11.sp,
            )
        }
    }
}

private fun fmtTime(ms: Int): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

@Composable
private fun LibraryScreen(vm: KatanaViewModel) {
    var name by remember { mutableStateOf("") }
    var rawName by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DeviceTitle()

        // This is the way to get a Librarian tone into the app exactly as it
        // sounds. It stores the amp's own bytes rather than our reading of
        // them, so recall is faithful even where our address labels are wrong.
        Panel(accent = Nux.Gate) {
            Text("Снять тон с комбика", color = Nux.TextHi, fontWeight = FontWeight.SemiBold)
            Text(
                "Настрой звук как надо — в Librarian или ручками на самом комбике — и нажми " +
                    "«Снять». Приложение прочитает байты комбика как есть и потом вернёт их " +
                    "точно такими же. Это работает, даже пока мы не знаем, что означает " +
                    "каждый адрес, поэтому звук будет ровно тот.",
                color = Nux.TextLo, fontSize = 11.sp,
            )
            OutlinedTextField(
                value = rawName,
                onValueChange = { rawName = it },
                label = { Text("название тона") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Pill(
                if (vm.capturing) "Читаю комбик…" else "Снять тон с комбика",
                selected = true,
                accent = Nux.Gate,
            ) {
                if (!vm.capturing) {
                    vm.captureFromAmp(rawName)
                    rawName = ""
                }
            }
            if (vm.captureStatus.isNotEmpty()) {
                Text(vm.captureStatus, color = Nux.TextHi, fontSize = 11.sp)
            }
            if (vm.rawPatches.isEmpty()) {
                Text("Снятых тонов пока нет.", color = Nux.TextLo, fontSize = 12.sp)
            }
            vm.rawPatches.forEach { rp ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(rp.name, color = Nux.TextHi, fontSize = 14.sp)
                        Text(rp.note, color = Nux.TextLo, fontSize = 10.sp)
                    }
                    Pill("Вернуть", selected = false, accent = Nux.Gate) { vm.applyRawPatch(rp) }
                    Box(Modifier.padding(start = 8.dp)) {
                        Pill("✕", selected = false, accent = Nux.Amp) { vm.deleteRawPatch(rp.name) }
                    }
                }
            }
        }

        Panel {
            Text("Сохранить текущий тон", color = Nux.TextHi, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(value = name, onValueChange = { name = it },
                label = { Text("название") }, modifier = Modifier.fillMaxWidth())
            Pill("Сохранить", selected = true, accent = Nux.Orange) {
                if (name.isNotBlank()) { vm.capturePatch(name); name = "" }
            }
        }
        Panel {
            Text("Мои пресеты", color = Nux.TextHi, fontWeight = FontWeight.SemiBold)
            if (vm.patches.isEmpty()) Text("Пока пусто.", color = Nux.TextLo, fontSize = 12.sp)
            vm.patches.forEach { patch: Patch ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(patch.name, color = Nux.TextHi, modifier = Modifier.weight(1f))
                    Pill("Загрузить", selected = false, accent = Nux.Orange) { vm.applyPatch(patch) }
                    Box(Modifier.padding(start = 8.dp)) {
                        Pill("✕", selected = false, accent = Nux.Amp) { vm.deletePatch(patch.name) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Panel(accent: Color = Nux.Stroke, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Nux.Panel)
            .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}
