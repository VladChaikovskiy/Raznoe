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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raznoe.katana.DeviceInfo
import com.raznoe.katana.KatanaViewModel
import com.raznoe.katana.model.FactoryPresets
import com.raznoe.katana.model.Patch
import com.raznoe.katana.protocol.KatanaParam
import com.raznoe.katana.protocol.KatanaParams
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
                5 -> LogScreen(vm)
                else -> ConsoleScreen(vm)
            }
        }
        BottomNav(screen) { screen = it }
    }
}

@Composable
private fun BottomNav(selected: Int, onSelect: (Int) -> Unit) {
    val items = listOf("Патч", "Тумблеры", "Пресеты", "Джем", "Библиотека", "Лог", "Консоль")
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
    Text(
        "K A T A N A",
        color = Nux.TextLo,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
    )
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
                "Что уходит в комбик — видно на вкладке «Лог».",
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

@Composable
private fun PresetsScreen(vm: KatanaViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DeviceTitle()
        Text("Пресеты (мои версии)", color = Nux.TextHi, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(
            "Стартовые тоны в духе известных названий. Это мои версии, не оригинальные JNs. " +
                "Нажми «Загрузить» — параметры уйдут на комбик.",
            color = Nux.TextLo, fontSize = 12.sp,
        )
        FactoryPresets.ALL.forEach { p ->
            val active = vm.activePreset == p.name
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
                    Pill(if (active) "Активен" else "Загрузить", selected = active, accent = Nux.Orange) {
                        vm.applyPatch(p)
                    }
                }
            }
        }
    }
}

@Composable
private fun JamScreen(vm: KatanaViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.addTrack(it) }
    }
    LaunchedEffect(vm.isPlaying) {
        while (vm.isPlaying) { vm.refreshPosition(); delay(400) }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DeviceTitle()
        Text("Джем — минусовки", color = Nux.TextHi, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(
            "Треки играют через динамик/выход телефона — играй под них на гитаре через комбик.",
            color = Nux.TextLo, fontSize = 12.sp,
        )

        Panel(accent = Nux.Orange) {
            val idx = vm.currentTrack
            val name = idx?.let { vm.tracks.getOrNull(it)?.name } ?: "Ничего не выбрано"
            Text(name, color = Nux.TextHi, fontWeight = FontWeight.SemiBold, maxLines = 2)
            val dur = if (vm.durationMs > 0) vm.durationMs else 1
            Slider(
                value = vm.positionMs.coerceIn(0, dur).toFloat(),
                onValueChange = { vm.seekTo(it.toInt()) },
                valueRange = 0f..dur.toFloat(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmtTime(vm.positionMs), color = Nux.TextLo, fontSize = 12.sp)
                Text(fmtTime(vm.durationMs), color = Nux.TextLo, fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill(if (vm.isPlaying) "Пауза" else "Играть", selected = vm.isPlaying, accent = Nux.Orange) {
                    vm.togglePlayPause()
                }
                Pill("Луп", selected = vm.looping, accent = Nux.Orange) { vm.toggleLoop() }
                Pill("${vm.speed}x", selected = false, accent = Nux.Orange) { vm.cycleSpeed() }
            }
            if (vm.jamStatus.isNotEmpty()) {
                Text(vm.jamStatus, color = Nux.TextLo, fontSize = 12.sp)
            }
        }

        Panel(accent = Nux.Pink) {
            Text("Куда играет минусовка", color = Nux.TextHi, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Через комбик (USB)", selected = vm.jamThroughAmp, accent = Nux.Orange) {
                    vm.chooseJamOutput(true)
                }
                Pill("Через телефон", selected = !vm.jamThroughAmp, accent = Nux.Orange) {
                    vm.chooseJamOutput(false)
                }
            }
            Text(
                "«Через комбик» — MP3 идёт в USB-аудио Katana и играет вместе с гитарой " +
                    "из динамика комбика. Нужно, чтобы комбик был подключён по USB и телефон " +
                    "его видел. Доступные выходы: ${vm.audioOutputs()}",
                color = Nux.TextLo, fontSize = 11.sp,
            )
        }

        Panel {
            Text("Громкость", color = Nux.TextHi, fontWeight = FontWeight.SemiBold)
            Text("Минусовка (MP3): ${(vm.mp3Volume * 100).toInt()}%", color = Nux.TextLo, fontSize = 12.sp)
            Slider(
                value = vm.mp3Volume,
                onValueChange = { vm.changeMp3Volume(it) },
                valueRange = 0f..1f,
            )
            val gv = vm.paramValues[KatanaParams.VOLUME.id] ?: 0
            Text("Гитара (усилитель): $gv", color = Nux.TextLo, fontSize = 12.sp)
            Slider(
                value = gv.toFloat(),
                onValueChange = { vm.setParam(KatanaParams.VOLUME, it.toInt()) },
                valueRange = 0f..100f,
            )
            Text(
                "Минусовка играет через телефон, гитару регулирует громкость усилителя.",
                color = Nux.TextLo, fontSize = 11.sp,
            )
        }

        Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Мои треки", color = Nux.TextHi, fontWeight = FontWeight.SemiBold)
                Pill("Добавить MP3", selected = true, accent = Nux.Orange) {
                    picker.launch(arrayOf("audio/*"))
                }
            }
            if (vm.tracks.isEmpty()) Text("Пусто — добавь трек.", color = Nux.TextLo, fontSize = 12.sp)
            vm.tracks.forEachIndexed { i, t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        t.name,
                        color = if (vm.currentTrack == i) Nux.Orange else Nux.TextHi,
                        modifier = Modifier.weight(1f).clickable { vm.playTrack(i) },
                        maxLines = 1,
                    )
                    Pill("▶", selected = false, accent = Nux.Orange) { vm.playTrack(i) }
                    Box(Modifier.padding(start = 8.dp)) {
                        Pill("✕", selected = false, accent = Nux.Amp) { vm.removeTrack(i) }
                    }
                }
            }
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
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DeviceTitle()
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
private fun LogScreen(vm: KatanaViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DeviceTitle()
        Text(
            "Журнал действий: всё, что ты нажимаешь, и сработало ли это. " +
                "Пройдись по вкладкам, нажми кнопки/ручки/тумблеры — потом жми «Копировать всё» и пришли мне.",
            color = Nux.TextLo, fontSize = 12.sp,
        )
        Panel(accent = Nux.Orange) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(if (copied) "Скопировано ✓" else "Копировать всё", selected = true, accent = Nux.Orange) {
                    clipboard.setText(AnnotatedString(vm.actionLogText()))
                    copied = true
                }
                Pill("Поделиться", selected = false, accent = Nux.Orange) {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Katana Ctl — журнал действий")
                        putExtra(Intent.EXTRA_TEXT, vm.actionLogText())
                    }
                    context.startActivity(Intent.createChooser(send, "Поделиться журналом"))
                }
                Pill("Очистить", selected = false, accent = Nux.Orange) {
                    vm.clearActionLog(); copied = false
                }
            }
            Text(
                "Записей: ${vm.actionLog.size}   Связь: ${if (vm.connected) "есть" else "нет"}   " +
                    "TX=${vm.txCount} RX=${vm.rxCount}",
                color = Nux.TextLo, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            )
        }
        Panel {
            if (vm.actionLog.isEmpty()) {
                Text(
                    "Пока пусто. Нажми что-нибудь на любой вкладке — здесь появится запись.",
                    color = Nux.TextLo, fontSize = 12.sp,
                )
            } else {
                Column(
                    Modifier.fillMaxWidth()
                        .heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    vm.actionLog.forEach { line ->
                        val ok = !line.contains("нет связи") && !line.contains("ошибка")
                        Text(
                            line,
                            color = if (ok) Nux.TextHi else Nux.Pink,
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConsoleScreen(vm: KatanaViewModel) {
    var hex by remember { mutableStateOf("F0 41 00 00 00 00 33 11 60 00 00 30 00 00 00 0A 06 F7") }
    var result by remember { mutableStateOf("") }
    var addr by remember { mutableStateOf("60 00 00 30") }
    var size by remember { mutableStateOf("16") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        var testMsg by remember { mutableStateOf("") }
        DeviceTitle()
        Panel(accent = Nux.Pink) {
            Text("Тест усилителя Gen 3", color = Nux.TextHi, fontWeight = FontWeight.SemiBold)
            Text(
                "Кнопки шлют реальные адреса Gen 3 (Gain/EQ). Жми и слушай комбик — " +
                    "звук должен меняться. Это те же адреса, что и у ручек на вкладке «Патч».",
                color = Nux.TextLo, fontSize = 12.sp,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Gain 100", selected = true, accent = Nux.Pink) { vm.setParam(KatanaParams.GAIN, 100); testMsg = "Gain → 100" }
                Pill("Gain 0", selected = false, accent = Nux.Pink) { vm.setParam(KatanaParams.GAIN, 0); testMsg = "Gain → 0" }
                Pill("Vol 100", selected = true, accent = Nux.Pink) { vm.setParam(KatanaParams.VOLUME, 100); testMsg = "Volume → 100" }
                Pill("Vol 20", selected = false, accent = Nux.Pink) { vm.setParam(KatanaParams.VOLUME, 20); testMsg = "Volume → 20" }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Bass 100", selected = false, accent = Nux.Pink) { vm.setParam(KatanaParams.BASS, 100); testMsg = "Bass → 100" }
                Pill("Bass 0", selected = false, accent = Nux.Pink) { vm.setParam(KatanaParams.BASS, 0); testMsg = "Bass → 0" }
                Pill("Treble 100", selected = false, accent = Nux.Pink) { vm.setParam(KatanaParams.TREBLE, 100); testMsg = "Treble → 100" }
                Pill("Mid 100", selected = false, accent = Nux.Pink) { vm.setParam(KatanaParams.MIDDLE, 100); testMsg = "Middle → 100" }
            }
            if (testMsg.isNotEmpty()) {
                Text(testMsg, color = Nux.TextHi, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
        Panel {
            Text("Отправить сырой SysEx", color = Nux.TextHi, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(value = hex, onValueChange = { hex = it },
                label = { Text("hex") }, modifier = Modifier.fillMaxWidth())
            Pill("Отправить", selected = true, accent = Nux.Orange) { result = vm.sendRawHex(hex) }
            if (result.isNotEmpty()) Text(result, color = Nux.TextLo, fontSize = 12.sp)
        }
        Panel {
            Text("Прочитать блок (мэппинг Gen 3)", color = Nux.TextHi, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(value = addr, onValueChange = { addr = it },
                label = { Text("адрес 4 байта") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = size, onValueChange = { size = it },
                label = { Text("байт") }, modifier = Modifier.fillMaxWidth())
            Pill("Запросить", selected = true, accent = Nux.Orange) {
                result = vm.readBlockHex(addr, size.toIntOrNull() ?: 16)
            }
        }
        val context = LocalContext.current
        Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Лог TX/RX", color = Nux.TextHi, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Поделиться", selected = true, accent = Nux.Orange) {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Katana Ctl log")
                            putExtra(Intent.EXTRA_TEXT, vm.logText())
                        }
                        context.startActivity(Intent.createChooser(send, "Поделиться логом"))
                    }
                    Pill("Очистить", selected = false, accent = Nux.Orange) { vm.clearLog() }
                }
            }
            Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                vm.log.takeLast(200).forEach { line ->
                    Text(line, color = Nux.TextHi, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        maxLines = 1, modifier = Modifier.horizontalScroll(rememberScrollState()))
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
