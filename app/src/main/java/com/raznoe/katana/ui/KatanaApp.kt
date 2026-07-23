package com.raznoe.katana.ui

import android.hardware.usb.UsbDevice
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raznoe.katana.DeviceInfo
import com.raznoe.katana.KatanaViewModel
import com.raznoe.katana.protocol.KatanaParam
import com.raznoe.katana.protocol.KatanaParams
import com.raznoe.katana.protocol.ParamKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KatanaApp(vm: KatanaViewModel, onConnectRequest: (UsbDevice) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Усилитель", "Эффекты", "Библиотека", "Консоль")

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("Katana Ctl", style = MaterialTheme.typography.titleLarge)
                    Text(
                        vm.status,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            })
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ConnectionBar(vm, onConnectRequest)
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, title ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                }
            }
            when (tab) {
                0 -> AmpTab(vm)
                1 -> EffectsTab(vm)
                2 -> LibraryTab(vm)
                else -> ConsoleTab(vm)
            }
        }
    }
}

@Composable
private fun ConnectionBar(vm: KatanaViewModel, onConnectRequest: (UsbDevice) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.connected) {
                Text("● ${vm.connectedLabel}", color = MaterialTheme.colorScheme.primary)
                if (vm.identityInfo.isNotEmpty()) {
                    Text(
                        "ID: ${vm.identityInfo}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.readCurrentState() }) { Text("Прочитать") }
                    Button(onClick = { vm.disconnect() }) { Text("Отключить") }
                }
            } else {
                OutlinedButton(onClick = { vm.refreshDevices() }) { Text("Обновить список") }
                Text(
                    "Важно: включай комбик, удерживая кнопку [BOOSTER], — так активируется " +
                        "режим USB-MIDI. Затем подключи кабель USB-C.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                vm.devices.forEach { d: DeviceInfo ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            d.label,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                        )
                        Button(onClick = { onConnectRequest(d.device) }) { Text("Подключить") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AmpTab(vm: KatanaViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Каналы") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KatanaParams.CHANNELS.forEachIndexed { i, (name, _) ->
                    FilterChip(
                        selected = vm.currentChannel == i,
                        onClick = { vm.selectChannel(i) },
                        label = { Text(name) },
                    )
                }
            }
        }

        KatanaParams.BY_CATEGORY[KatanaParams.AMP_SECTION]?.let { params ->
            SectionCard(KatanaParams.AMP_SECTION) {
                params.forEach { p -> ParamControl(vm, p) }
            }
        }

        ProfileBanner()
    }
}

@Composable
private fun EffectsTab(vm: KatanaViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        KatanaParams.EFFECT_SECTIONS.forEach { section ->
            val params = KatanaParams.BY_CATEGORY[section] ?: return@forEach
            SectionCard(section) {
                params.forEach { p -> ParamControl(vm, p) }
                if (section == "Mod" || section == "FX") {
                    Text(
                        "Параметры конкретного типа эффекта редактируются через вкладку " +
                            "«Консоль» (полная карта под-параметров ещё уточняется).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
        ProfileBanner()
    }
}

@Composable
private fun ProfileBanner() {
    Text(
        "Профиль адресов: Katana MkII (model 0x33). Для Gen 3 часть адресов может " +
            "отличаться — параметры с «(?)» не подтверждены. Проверяй и уточняй через " +
            "вкладку «Консоль» (чтение блока + сравнение).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary,
    )
}

@Composable
private fun ParamControl(vm: KatanaViewModel, p: KatanaParam) {
    val value = vm.paramValues[p.id] ?: p.default
    val mark = if (!p.verified) "  (?)" else ""
    when (p.kind) {
        ParamKind.TOGGLE -> Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(p.label + mark)
            Switch(checked = value != 0, onCheckedChange = { vm.setParam(p, if (it) 1 else 0) })
        }

        ParamKind.ENUM -> Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(p.label + mark)
            EnumDropdown(p, value) { idx -> vm.setParam(p, p.valueOfIndex(idx)) }
        }

        ParamKind.CONTINUOUS -> Column(Modifier.padding(vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(p.label + mark)
                Text("$value")
            }
            Slider(
                value = value.toFloat(),
                onValueChange = { vm.setParam(p, it.toInt()) },
                valueRange = p.min.toFloat()..p.max.toFloat(),
            )
        }
    }
}

@Composable
private fun EnumDropdown(p: KatanaParam, value: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val index = p.indexOfValue(value)
    val current = p.options.getOrElse(index) { "?" }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(current) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            p.options.forEachIndexed { i, label ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { open = false; onSelect(i) },
                )
            }
        }
    }
}

@Composable
private fun ConsoleTab(vm: KatanaViewModel) {
    var hex by remember { mutableStateOf("F0 41 00 00 00 00 33 11 60 00 00 30 00 00 00 0A 06 F7") }
    var result by remember { mutableStateOf("") }
    var addr by remember { mutableStateOf("60 00 00 30") }
    var size by remember { mutableStateOf("16") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Отправить сырой SysEx") {
            OutlinedTextField(
                value = hex, onValueChange = { hex = it },
                label = { Text("hex-байты") }, modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { result = vm.sendRawHex(hex) }) { Text("Отправить") }
            if (result.isNotEmpty()) Text(result, style = MaterialTheme.typography.bodySmall)
        }

        SectionCard("Прочитать блок (мэппинг Gen 3)") {
            Text(
                "Считай блок → покрути ручку на комбике физически → считай снова → сравни RX. " +
                    "Изменившийся байт = адрес параметра.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = addr, onValueChange = { addr = it },
                label = { Text("адрес (4 байта hex)") }, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = size, onValueChange = { size = it },
                label = { Text("сколько байт") }, modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { result = vm.readBlockHex(addr, size.toIntOrNull() ?: 16) }) {
                Text("Запросить")
            }
        }

        SectionCard("Лог (TX/RX)") {
            OutlinedButton(onClick = { vm.clearLog() }) { Text("Очистить") }
            Column(
                Modifier.fillMaxWidth().heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                vm.log.takeLast(200).forEach { line ->
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryTab(vm: KatanaViewModel) {
    var name by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Сохранить текущий тон") {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("название пресета") }, modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { if (name.isNotBlank()) { vm.capturePatch(name); name = "" } }) {
                Text("Сохранить")
            }
        }

        SectionCard("Мои пресеты") {
            if (vm.patches.isEmpty()) Text("Пока пусто.", style = MaterialTheme.typography.bodySmall)
            vm.patches.forEach { patch ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(patch.name, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { vm.applyPatch(patch) }) { Text("Загрузить") }
                    OutlinedButton(
                        onClick = { vm.deletePatch(patch.name) },
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Text("✕") }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
