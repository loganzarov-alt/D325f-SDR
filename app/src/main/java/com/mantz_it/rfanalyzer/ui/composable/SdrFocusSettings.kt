// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
// D325f SDR changes, Logan Zarov, 2026-10-23. See docs/CHANGES.md.
package com.mantz_it.rfanalyzer.ui.composable

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import com.mantz_it.rfanalyzer.automation.LocalizedText as Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mantz_it.rfanalyzer.source.SdrFocusController

@Composable
fun SdrFocusSettings() {
    val context = LocalContext.current
    val controller = remember { SdrFocusController.get(context) }
    var mode by remember { mutableStateOf(controller.mode) }
    OutlinedBox(label = "Производительность") {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Выключено", "Очистка RAM + CPU", "Заморозка фона и System UI + CPU").forEachIndexed { index, label ->
                TextButton(onClick = { mode = index; controller.setMode(index) }) {
                    RadioButton(selected = mode == index, onClick = null)
                    Text(label)
                }
            }
            Text(when (mode) {
                2 -> "Pauses background apps, launcher, Google services and the notification shade. Display, audio and reception services continue. Freezing does not free RAM and may cause black screens."
                1 -> "Frees RAM used by background apps. The notification shade works; Android may restart services."
                else -> "No memory cleanup, freezing or CPU mode change."
            }, style = MaterialTheme.typography.bodySmall)
            Text("The selected mode applies at the next reception start. Disabling takes effect immediately. Stopping, leaving the app or losing its connection releases frozen processes and restores CPU mode.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
