// D325f SDR changes, Logan Zarov, 2026-10-23. See docs/CHANGES.md.
package com.mantz_it.rfanalyzer.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.mantz_it.rfanalyzer.analyzer.ReceiverFocus
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mantz_it.rfanalyzer.R
import com.mantz_it.rfanalyzer.source.LgModemSource

/**
 * <h1>RF Analyzer - Source Tab</h1>
 *
 * Module:      SourceTab.kt
 * Description: The main Tab of the Control Drawer. Contains all Source-related
 * settings as well as the Start/Stop Button to start the Analyzer Service.
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


enum class SourceType(val displayName: String, val availableSampleRates: List<Long>) {
    LGMODEM("LG D325 · SDR", listOf(7680000, 2560000, 1280000)),
    FILESOURCE("IQ File", emptyList());

    companion object {
        // v20058 stored LG=0, HackRF=1, RTL-SDR=2, File=3.
        fun migrateLegacyOrdinal(old: Int): Int = if (old == 3) FILESOURCE.ordinal else LGMODEM.ordinal
    }
}

enum class FilesourceFileFormat(val displayName: String, val description: String, val bytesPerSample: Int) {
    HACKRF("CI8 (legacy)", "8-bit signed interleaved IQ", 2),
    RTLSDR("CU8", "8-bit unsigned interleaved IQ", 2),
    CI16("CI16", "16-bit signed little-endian IQ", 4),
    CI8("CI8", "8-bit signed interleaved IQ", 2);

    val nativeFormat: Int get() = when (this) {
        HACKRF, CI8 -> 0
        RTLSDR -> 1
        CI16 -> 2
    }
}

data class SourceTabActions(
    val onStartStopClicked: () -> Unit,
    val onSourceTypeChanged: (newType: SourceType) -> Unit,
    val onFrequencyChanged: (newFrequency: Long) -> Unit,
    val onSampleRateChanged: (newSampleRate: Long) -> Unit,
    val onAutomaticSampleRateAdjustmentChanged: (Boolean) -> Unit,
    val onLgRfBandChanged: (Int) -> Unit,
    val onLgTuneModeChanged: (Int) -> Unit,
    val onLgUarfcnChanged: (Int) -> Unit,
    val onLgPllProfileChanged: (Int) -> Unit,
    val onLgPllMultiplierChanged: (Int) -> Unit,
    val onLgLnaStateChanged: (Int) -> Unit,
    val onLgDvgaOffsetChanged: (Int) -> Unit,
    val onLgFullResetClicked: () -> Unit,
    val onFilesourceFileFormatChanged: (fileFormat: FilesourceFileFormat) -> Unit,
    val onOpenFileClicked: () -> Unit,
    val onViewRecordingsClicked: () -> Unit,
    val onFilesourceRepeatChanged: (Boolean) -> Unit,
    val onSuppressDcSpikeChanged: (Boolean) -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceTabComposable(
    sourceType: SourceType,
    sourceName: String,
    analyzerRunning: Boolean,
    analyzerStartPending: Boolean,
    frequency: Long,
    sampleRate: Long,
    automaticSampleRateAdjustment: Boolean,
    lgRfBand: Int,
    lgTuneMode: Int,
    lgUarfcn: Int,
    lgPllProfile: Int,
    lgPllMultiplier: Int,
    lgLnaState: Int,
    lgDvgaOffset: Int,
    minimumFrequency: Long,
    maximumFrequency: Long,
    minimumSampleRate: Long,
    maximumSampleRate: Long,
    filesourceFilename: String,
    filesourceFileFormat: FilesourceFileFormat,
    filesourceRepeatEnabled: Boolean,
    suppressDcSpike: Boolean,
    sourceTabActions: SourceTabActions
) {
    ScrollableColumnWithFadingEdge {
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedEnumDropDown(
                label = "Signal Source",
                selectedEnum = sourceType,
                enumClass = SourceType::class,
                getDisplayName = { it.displayName },
                onSelectionChanged = sourceTabActions.onSourceTypeChanged,
                enabled = !analyzerRunning && !analyzerStartPending,
                modifier = Modifier.weight(0.618f),
                helpSubPath = "sdr-source.html#selecting-a-source"
            )
            Button(
                onClick = sourceTabActions.onStartStopClicked,
                enabled = !analyzerStartPending,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .padding(start = 8.dp, end = 2.dp, top = 11.dp)
                    .height(60.dp)
                    .align(Alignment.CenterVertically)
                    .weight(0.382f)
            ) {
                if(analyzerRunning) {
                    Icon(
                        painter = painterResource(R.drawable.stop_circle),
                        contentDescription = "Stop",
                        modifier = Modifier.size(40.dp)
                    )
                } else if (analyzerStartPending) {
                    CircularProgressIndicator(color = Color.White)
                } else {
                    Icon(
                        painter = painterResource(R.drawable.play_circle),
                        contentDescription = "Start",
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
        }
        OutlinedSwitch(
            label = "Suppress DC spike",
            helpText = "Removes the center-bin source DC spike from the display. Raw IQ recordings remain byte-for-byte untouched.",
            isChecked = suppressDcSpike,
            onCheckedChange = sourceTabActions.onSuppressDcSpikeChanged
        )
        when (sourceType) {

            SourceType.LGMODEM -> {
                OutlinedListDropDown(
                    items = SourceType.LGMODEM.availableSampleRates,
                    getDisplayName = { LgModemSource.bandwidthLabel(it) },
                    selectedItem = sampleRate,
                    onSelectionChanged = sourceTabActions.onSampleRateChanged,
                    label = "Sample rate / полоса",
                    enabled = !analyzerRunning
                )
                OutlinedListDropDown(
                    items = listOf(LgModemSource.RF_BAND_B1, LgModemSource.RF_BAND_B8),
                    getDisplayName = { if (it == LgModemSource.RF_BAND_B1) "B1 · 2100 MHz SAW" else "B8 · 900 MHz SAW" },
                    selectedItem = if (lgRfBand == LgModemSource.RF_BAND_B1) LgModemSource.RF_BAND_B1 else LgModemSource.RF_BAND_B8,
                    onSelectionChanged = sourceTabActions.onLgRfBandChanged,
                    label = "Вход / SAW",
                    enabled = !analyzerRunning
                )
                    FrequencyChooser(
                        label = "Частота приёма",
                        unit = "Hz",
                        currentFrequency = frequency,
                        onFrequencyChanged = sourceTabActions.onFrequencyChanged,
                        minFrequency = LgModemSource.MIN_FREQUENCY,
                        maxFrequency = LgModemSource.MAX_FREQUENCY,
                        enabled = true,
                        digitCount = 1,
                        insertMode = false,
                        helpSubPath = null
                    )
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedListDropDown(
                        items = listOf(0, 2, 3, 4, 5, 6, 7, 8, 9, 10),
                        getDisplayName = { listOf("Auto", "unused", "750", "875", "1000", "1500", "1750", "2000", "3000", "3500", "4000")[it] },
                        selectedItem = lgPllProfile.coerceIn(0, 10),
                        onSelectionChanged = sourceTabActions.onLgPllProfileChanged,
                        label = "Профиль PLL",
                        enabled = !analyzerRunning && lgTuneMode == LgModemSource.TUNE_MODE_EXACT,
                        modifier = Modifier.weight(1f).padding(end = 3.dp)
                    )
                    OutlinedListDropDown(
                        items = listOf(10, 20, 40),
                        getDisplayName = { "×$it" },
                        selectedItem = if (lgPllMultiplier in listOf(10, 20, 40)) lgPllMultiplier else 20,
                        onSelectionChanged = sourceTabActions.onLgPllMultiplierChanged,
                        label = "Множитель",
                        enabled = !analyzerRunning && lgTuneMode == LgModemSource.TUNE_MODE_EXACT && lgPllProfile != LgModemSource.PROFILE_AUTO,
                        modifier = Modifier.weight(1f).padding(start = 3.dp)
                    )
                }
                OutlinedListDropDown(
                    items = (0..5).toList(),
                    getDisplayName = { if (it == 0) "0 · maximum measured" else it.toString() },
                    selectedItem = lgLnaState.coerceIn(0, 5),
                    onSelectionChanged = sourceTabActions.onLgLnaStateChanged,
                    label = "Усиление LNA"
                )
                OutlinedSlider(
                    label = "Калибровка DVGA",
                    unit = "steps",
                    minValue = -512f,
                    maxValue = 511f,
                    value = lgDvgaOffset.coerceIn(-512, 511).toFloat(),
                    decimalPlaces = 0,
                    onValueChanged = { sourceTabActions.onLgDvgaOffsetChanged(it.toInt()) }
                )
                Text("BT TX и режим производительности — в настройках", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        sourceTabActions.onLgLnaStateChanged(0)
                        sourceTabActions.onLgDvgaOffsetChanged(0)
                    }) { Text("Сброс усиления") }
                    OutlinedButton(onClick = sourceTabActions.onLgFullResetClicked, enabled = !analyzerRunning) {
                        Text("Сброс модема")
                    }
                }
            }
            
            
            SourceType.FILESOURCE -> {
                Text("File: " + filesourceFilename.ifEmpty { "(no File selected)" }, fontSize = 12.sp, lineHeight = 13.sp)
                Row {
                    Button(
                        onClick = sourceTabActions.onOpenFileClicked,
                        shape = MaterialTheme.shapes.small,
                        enabled = !analyzerRunning,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .align(Alignment.CenterVertically)
                    ) {
                        Text("Open File", modifier = Modifier.padding(horizontal = 10.dp))
                        Icon(painter = painterResource(R.drawable.folder_open), "Open IQ File")
                    }
                    Button(
                        onClick = sourceTabActions.onViewRecordingsClicked,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .padding(horizontal = 6.dp)
                            .weight(1f)
                    ) {
                        Row {
                            Text("View Recordings", modifier = Modifier.padding(horizontal = 16.dp))
                            Icon(imageVector = Icons.Default.Menu, contentDescription = "View Recordings")
                        }
                    }
                }
                FrequencyChooser(
                    label = "Tune-Frequency",
                    unit = "Hz",
                    currentFrequency = frequency,
                    onFrequencyChanged = sourceTabActions.onFrequencyChanged,
                    minFrequency = 0,
                    maxFrequency = 10000000000L,
                    enabled = !analyzerRunning,
                    helpSubPath = "sdr-source.html#tune-frequency_2"
                )
                FrequencyChooser(
                    label = "Sample Rate",
                    unit = "Sps",
                    digitCount = 9,
                    currentFrequency = sampleRate,
                    onFrequencyChanged = sourceTabActions.onSampleRateChanged,
                    enabled = !analyzerRunning,
                    helpSubPath = "sdr-source.html#sample-rate_2"
                )
                Row {
                    OutlinedEnumDropDown(
                        label = "File Format",
                        selectedEnum = filesourceFileFormat,
                        enumClass = FilesourceFileFormat::class,
                        getDisplayName = { it.displayName },
                        onSelectionChanged = sourceTabActions.onFilesourceFileFormatChanged,
                        enabled = !analyzerRunning,
                        helpSubPath = "sdr-source.html#file-format",
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 3.dp)
                    )
                    OutlinedSwitch(
                        label = "Repeat",
                        helpText = "Automatically rewind file after reaching the end",
                        isChecked = filesourceRepeatEnabled,
                        onCheckedChange = sourceTabActions.onFilesourceRepeatChanged,
                        helpSubPath = "sdr-source.html#repeat",
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
@Preview
fun SourceTabPreview() {
    CompositionLocalProvider(LocalShowHelp provides {}) {
        SourceTabComposable(
            sourceType = SourceType.LGMODEM,
            sourceName = "Test-Source",
            analyzerRunning = true,
            analyzerStartPending = false,
            frequency = 30000000L,
            sampleRate = 1000000,
            automaticSampleRateAdjustment = true,
            lgRfBand = LgModemSource.RF_BAND_B8,
            lgTuneMode = LgModemSource.TUNE_MODE_EXACT,
            lgUarfcn = 240,
            lgPllProfile = 4,
            lgPllMultiplier = 20,
            lgLnaState = 0,
            lgDvgaOffset = 0,
            minimumFrequency = 100000,
            maximumFrequency = 1000000000,
            minimumSampleRate = 1000000,
            maximumSampleRate = 10000000,
            filesourceFilename = "20250101_test_HACKRF_100MHz_2Msps.iq",
            filesourceFileFormat = FilesourceFileFormat.HACKRF,
            filesourceRepeatEnabled = true,
            suppressDcSpike = true,
            sourceTabActions = SourceTabActions(
                onSourceTypeChanged = { },
                onStartStopClicked = { },
                onFrequencyChanged = { },
                onSampleRateChanged = { },
                onAutomaticSampleRateAdjustmentChanged = { },
                onLgRfBandChanged = {},
                onLgTuneModeChanged = {},
                onLgUarfcnChanged = {},
                onLgPllProfileChanged = {},
                onLgPllMultiplierChanged = {},
                onLgLnaStateChanged = {},
                onLgDvgaOffsetChanged = {},
                onLgFullResetClicked = {},
                onFilesourceFileFormatChanged = {},
                onOpenFileClicked = { },
                onViewRecordingsClicked = { },
                onFilesourceRepeatChanged = {},
                onSuppressDcSpikeChanged = {},
            ),
        )
    }
}
