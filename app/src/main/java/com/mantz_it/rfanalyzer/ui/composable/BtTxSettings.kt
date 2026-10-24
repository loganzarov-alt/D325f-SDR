// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
// D325f SDR changes, Logan Zarov, 2026-10-23. See docs/CHANGES.md.
package com.mantz_it.rfanalyzer.ui.composable

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.mantz_it.rfanalyzer.source.BtCwController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BtTxSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var channelText by remember { mutableStateOf("30") }
    var power by remember { mutableIntStateOf(9) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { BtCwController.get(context).power } }.onSuccess { power = it; channelText = BtCwController.getChannel().toString() }
    }
    OutlinedBox(label = "BT TX · гетеродин") {
        Column(Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = channelText,
                onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) channelText = it },
                label = { Text("BT-канал (0–78)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = !busy
            )
            val selectedChannel = channelText.toIntOrNull()
            Text(if (selectedChannel != null && selectedChannel in 0..78)
                "Частота TX: ${2402 + selectedChannel} МГц" else "Введите канал 0–78")
            Text("Смена канала — при остановленном приёме.", style = MaterialTheme.typography.bodySmall)
            Button(enabled = !busy && selectedChannel != null && selectedChannel in 0..78, onClick = {
                val next = selectedChannel ?: return@Button
                busy = true
                scope.launch {
                    status = try {
                        withContext(Dispatchers.IO) { BtCwController.get(context).setChannel(next) }
                        "Сохранён канал $next · ${2402 + next} МГц"
                    } catch (e: Exception) { e.message ?: "Ошибка изменения канала" }
                    busy = false
                }
            }) { Text("Применить канал") }
            Text("Мощность TX: уровень $power / 9")

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = power > 0 && !busy, onClick = {
                    val next = power - 1; busy = true
                    scope.launch {
                        try { withContext(Dispatchers.IO) { BtCwController.get(context).setPower(next) }; power = next }
                        catch (e: Exception) { status = "TX: ${e.message}" }
                        busy = false
                    }
                }) { Text("TX −") }
                Button(enabled = power < 9 && !busy, onClick = {
                    val next = power + 1; busy = true
                    scope.launch {
                        try { withContext(Dispatchers.IO) { BtCwController.get(context).setPower(next) }; power = next }
                        catch (e: Exception) { status = "TX: ${e.message}" }
                        busy = false
                    }
                }) { Text("TX +") }
            }
            Button(onClick = { com.mantz_it.rfanalyzer.source.BtRxLimit.show(context) }) { Text("Верхняя граница BT RX, МГц") }
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}
