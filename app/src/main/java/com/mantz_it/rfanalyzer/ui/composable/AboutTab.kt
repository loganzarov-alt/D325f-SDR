// D325f SDR changes, Logan Zarov, 2026-10-23. See docs/CHANGES.md.
package com.mantz_it.rfanalyzer.ui.composable

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
import androidx.compose.material3.Text
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
                "SDR-приёмник со спектром и водопадом в реальном времени, " +
                    "режимами AM/NFM/WFM/USB/LSB/CW, записью IQ и демодулированного аудио.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(
                "Версия $appVersion",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 14.dp)
            )
        }

        OutlinedBox(label = "Справка · профили приёма") {
            Column(Modifier.padding(12.dp)) {
                Text("Диапазоны настройки PLL, МГц", fontWeight = FontWeight.SemiBold)
                Text("Таблица прокручивается по горизонтали. Диапазоны относятся к частоте входа модема; чувствительность зависит от SAW B1/B8 и тракта.", style = MaterialTheme.typography.bodySmall)
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
                Text("Auto выбирает профиль по частоте. Профиль 500 не используется: устойчивый захват PLL не подтверждён.", style = MaterialTheme.typography.bodySmall)
                Text("BT RX: верхняя граница задаётся в настройках BT-гетеродина (по умолчанию 800 МГц). Ниже границы: частота модема = (2402 + канал BT) МГц минус частота приёма. На границе и выше: прямой приём. Изменение применяется после остановки и запуска RX.", style = MaterialTheme.typography.bodySmall)
                Text("Полосы: 5 МГц — исходные 7,68 MS/s; 2 МГц — FIR /3, 2,56 MS/s; 1 МГц — FIR /6, 1,28 MS/s. Raw IQ записывается до фильтрации, 7,68 MS/s. Freeze останавливает спектр и водопад; демодулятор остаётся доступен.", style = MaterialTheme.typography.bodySmall)
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
