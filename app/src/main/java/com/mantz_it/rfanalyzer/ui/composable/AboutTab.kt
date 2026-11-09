// SPDX-License-Identifier: GPL-3.0-or-later
// D325f SDR changes, Logan Zarov, 2026-10-23. See docs/CHANGES.md.
/**
 * <h1>RF Analyzer - About Tab</h1>
 *
 * Module:      AboutTab.kt
 * Description: A Tab in the Control Drawer. Gives access to the manual and the purchase button.
 *
 * @author Dennis Mantz
 *
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */
package com.mantz_it.rfanalyzer.ui.composable
import com.mantz_it.rfanalyzer.R
import com.mantz_it.rfanalyzer.automation.localString

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import com.mantz_it.rfanalyzer.source.LgModemSource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import com.mantz_it.rfanalyzer.automation.LocalizedText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mantz_it.rfanalyzer.ui.RFAnalyzerTheme

/** Actions exposed by the RF Decoder About tab. */
data class AboutTabActions(
    val onAboutClicked: () -> Unit,
    val onManualClicked: () -> Unit
)

@Composable
fun AboutTabComposable(
    appVersion: String,
    appBuildType: String,
    aboutTabActions: AboutTabActions,
) {
    ScrollableColumnWithFadingEdge {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp)
                .background(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(24.dp)
                )
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Radio,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(42.dp)
            )
            Text(
                "D325f SDR",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                "Сборка: Logan Zarov",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                "SDR receiver with live spectrum and waterfall, AM/NFM/WFM/USB/LSB/CW, IQ recording and demodulated audio.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(
                localString(R.string.l10n_version, appVersion),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 14.dp)
            )
        }

        OutlinedBox(label = "Справка · профили приёма") {
            Column(Modifier.padding(12.dp)) {
                Text("Диапазоны настройки PLL, МГц", fontWeight = FontWeight.SemiBold)
                Text("Scroll the table horizontally. Ranges refer to the modem input frequency; sensitivity depends on the B1/B8 SAW filter and RF path.", style = MaterialTheme.typography.bodySmall)
                Column(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 10.dp)) {
                    Row {
                        listOf("Профиль", "×10", "×20", "×40").forEachIndexed { index, title ->
                            Text(title, Modifier.width(if (index == 0) 72.dp else 150.dp), fontWeight = FontWeight.Bold)
                        }
                    }
                    remember { LgModemSource.receptionProfiles() }.forEach { row ->
                        Row(Modifier.padding(vertical = 5.dp)) {
                            row.forEachIndexed { index, cell ->
                                Text(cell, Modifier.width(if (index == 0) 72.dp else 150.dp), fontSize = 13.sp)
                            }
                        }
                    }
                }
                Text("Auto chooses the PLL profile by frequency. Profile 500 is not used because stable PLL lock has not been confirmed.", style = MaterialTheme.typography.bodySmall)
                Text("Below the BT receive limit: modem frequency = (2402 + BT channel) MHz minus receive frequency. At or above the limit, reception is direct.", style = MaterialTheme.typography.bodySmall)
                Text("5 MHz: native 7.68 MS/s; 2 MHz: FIR /3, 2.56 MS/s; 1 MHz: FIR /6, 1.28 MS/s. Manual raw IQ recording uses 7.68 MS/s; automated recording uses the selected rate. Freeze pauses the spectrum and waterfall while audio remains available.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = aboutTabActions.onAboutClicked,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(62.dp)
        ) {
            Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(28.dp))
            Text(
                "Open-source licenses",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
    }
}

@Preview
@Composable
fun AboutTabPreview() {
    val snackbarHostState = remember { SnackbarHostState() }
    CompositionLocalProvider(LocalSnackbarHostState provides snackbarHostState) {
        RFAnalyzerTheme {
            AboutTabComposable(
                appVersion = "1.0.0",
                appBuildType = "release",
                aboutTabActions = AboutTabActions({}, {})
            )
        }
    }
}
