// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
package com.mantz_it.rfanalyzer.satellites

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rtbishop.look4sat.core.domain.predict.*
import com.rtbishop.look4sat.core.domain.utility.DataParser
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.*

@Composable
fun SatelliteWindow() {
    val context = LocalContext.current
    val ru = context.resources.configuration.locales[0].language == "ru"
    fun tr(r: String, e: String) = if (ru) r else e
    var opened by remember { mutableStateOf(false) }
    Column {
        Text(tr("Спутники · Look4Sat", "Satellites · Look4Sat"), style = MaterialTheme.typography.titleLarge)
        Text(tr("Орбиты, пролёты и наведение антенны", "Orbits, passes and antenna pointing"))
        Button(onClick = { opened = true }) { Text(tr("Открыть окно", "Open window")) }
    }
    if (!opened) return
    Dialog(onDismissRequest = { opened = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            SatelliteContent({ opened = false }, ru)
        }
    }
}

@Composable
private fun SatelliteContent(close: () -> Unit, ru: Boolean) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("satellite_window", 0) }
    val file = remember { File(context.filesDir, "satellite_orbits.txt") }
    val scope = rememberCoroutineScope()
    fun tr(r: String, e: String) = if (ru) r else e
    var latitude by remember { mutableStateOf(prefs.getString("latitude", "") ?: "") }
    var longitude by remember { mutableStateOf(prefs.getString("longitude", "") ?: "") }
    var query by remember { mutableStateOf("") }
    var satellites by remember { mutableStateOf(emptyList<OrbitalData>()) }
    var selected by remember { mutableStateOf<OrbitalData?>(null) }
    var favourites by remember { mutableStateOf(prefs.getStringSet("favourites", emptySet())!!.toSet()) }
    var tab by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var passes by remember { mutableStateOf(emptyList<SatellitePass>()) }
    var track by remember { mutableStateOf(emptyList<SkyPoint>()) }
    var live by remember { mutableStateOf<OrbitalPos?>(null) }
    var calculation by remember { mutableStateOf<Job?>(null) }
    val formatter = remember { SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()) }
    fun time(ms: Long) = formatter.format(java.util.Date(ms))
    fun station(): GeoPos {
        val lat = latitude.replace(',', '.').toDoubleOrNull()
        val lon = longitude.replace(',', '.').toDoubleOrNull()
        require(lat != null && lat.isFinite() && lat in -90.0..90.0 && lon != null && lon.isFinite() && lon in -180.0..180.0) {
            tr("Укажите широту −90…90 и долготу −180…180", "Enter latitude −90…90 and longitude −180…180")
        }
        return GeoPos(lat, lon)
    }
    suspend fun parse(text: String): List<OrbitalData> {
        val parser = DataParser(Dispatchers.Default)
        val data = if (text.lineSequence().firstOrNull()?.contains("NORAD_CAT_ID") == true)
            parser.parseCSVStream(text.byteInputStream()) else parser.parseTLEStream(text.byteInputStream())
        require(data.isNotEmpty()) { tr("В файле нет распознанных орбит TLE/OMM CSV", "No valid TLE/OMM CSV orbits in file") }
        return data.filter { it.meanmo.isFinite() && it.meanmo > 0 && it.eccn in 0.0..<1.0 }.distinctBy { it.catnum }.also { require(it.isNotEmpty()) { "No valid orbital elements" } }
    }
    fun select(data: OrbitalData) {
        calculation?.cancel(); selected = data; passes = emptyList(); track = emptyList()
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.use { stream ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = stream.read(buffer); if (n < 0) break
                            require(output.size() + n <= 4 * 1024 * 1024) { "Maximum file size: 4 MiB" }
                            output.write(buffer, 0, n)
                        }
                        output.toString("UTF-8").removePrefix("\uFEFF")
                    }
                }
                val parsed = parse(text)
                withContext(Dispatchers.IO) { android.util.AtomicFile(file).let { atomic ->
                    val out = atomic.startWrite()
                    try { out.write(text.toByteArray()); atomic.finishWrite(out) }
                    catch (e: Exception) { atomic.failWrite(out); throw e }
                } }
                satellites = parsed; select(parsed.first()); message = tr("Орбиты загружены", "Orbits imported")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "Import failed" }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) {
        if (file.exists()) {
            busy = true
            try { satellites = parse(withContext(Dispatchers.IO) { file.readText() }); satellites.firstOrNull()?.let { select(it) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "Import failed" }
            finally { busy = false }
        }
    }
    LaunchedEffect(selected, latitude, longitude) {
        live = null
        val data = selected ?: return@LaunchedEffect
        val observer = runCatching { station() }.getOrNull() ?: return@LaunchedEffect
        prefs.edit().putString("latitude", latitude).putString("longitude", longitude).apply()
        val sat = data.getObject()
        while (isActive) {
            try {
                val position = withContext(Dispatchers.Default) { sat.getPosition(observer, System.currentTimeMillis()) }
                check(position.azimuth.isFinite() && position.elevation.isFinite()) { "Invalid orbital position" }
                live = position
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "Invalid orbital position"; break }
            delay(2000)
        }
    }
    DisposableEffect(Unit) { onDispose { calculation?.cancel() } }
    LazyColumn(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tr("Спутники", "Satellites"), style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = close) { Text(tr("Закрыть", "Close")) }
            }
            Text("Look4Sat · SGP4/SDP4", style = MaterialTheme.typography.labelMedium)
            Text(tr("Время: ${java.util.TimeZone.getDefault().id}. Орбиты рассчитываются офлайн.",
                "Time: ${java.util.TimeZone.getDefault().id}. Offline orbit calculations."))
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(latitude, { latitude = it; calculation?.cancel(); passes = emptyList(); track = emptyList() }, label = { Text(tr("Широта", "Latitude")) }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(longitude, { longitude = it; calculation?.cancel(); passes = emptyList(); track = emptyList() }, label = { Text(tr("Долгота", "Longitude")) }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            Button(onClick = { importer.launch(arrayOf("*/*")) }, enabled = !busy) { Text(tr("Импорт TLE / OMM CSV", "Import TLE / OMM CSV")) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotEmpty()) Text(message)
        }
        item {
            TabRow(tab) { listOf(tr("Пролёты", "Passes"), tr("Небо", "Sky"), tr("Избранное", "Favourites")).forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
            } }
            OutlinedTextField(query, { query = it }, label = { Text(tr("Название / NORAD", "Name / NORAD")) }, modifier = Modifier.fillMaxWidth())
        }
        items(satellites.filter { (tab != 2 || it.catnum.toString() in favourites) && (it.name.contains(query, true) || it.catnum.toString().contains(query)) }.take(if (query.isBlank()) 5 else 30), key = { it.catnum }) { data ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { select(data) }, modifier = Modifier.weight(1f)) { Text("${if (selected?.catnum == data.catnum) "● " else ""}${data.name} · ${data.catnum}") }
                TextButton(onClick = {
                    val id = data.catnum.toString(); favourites = if (id in favourites) favourites - id else favourites + id
                    prefs.edit().putStringSet("favourites", favourites).apply()
                }) { Text(if (data.catnum.toString() in favourites) "★" else "☆") }
            }
        }
        item {
            if (satellites.isEmpty()) Text(tr("Импортируйте свежий файл орбит из Look4Sat или CelesTrak.", "Import a fresh orbit file from Look4Sat or CelesTrak."))
            selected?.let { data ->
                Text(data.name, style = MaterialTheme.typography.titleLarge)
                live?.let { pos ->
                    val azimuth = (Math.toDegrees(pos.azimuth) + 360) % 360
                    val elevation = Math.toDegrees(pos.elevation)
                    Text(tr("Азимут %.1f° · Угол места %.1f°".format(azimuth, elevation),
                        "Azimuth %.1f° · Elevation %.1f°".format(azimuth, elevation)), style = MaterialTheme.typography.titleMedium)
                    Text(if (elevation >= 0) tr("Над горизонтом", "Above horizon") else tr("Под горизонтом", "Below horizon"))
                    Text(tr("Азимут отсчитывается от истинного севера по часовой стрелке. GPS и компас телефона не используются.",
                        "Azimuth is clockwise from true north. Phone GPS and compass are not used."))
                } ?: Text(tr("Для наведения укажите координаты вручную", "Enter station coordinates manually for pointing"))
                val age = abs(OrbitalData.timeToDaynum(System.currentTimeMillis()) - data.epochDaynum)
                Text(tr("Возраст орбит: %.1f суток".format(age), "Orbit age: %.1f days".format(age)), color = if (age > 7) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                Button(enabled = !busy, onClick = {
                    try {
                        val observer = station()
                        prefs.edit().putString("latitude", latitude).putString("longitude", longitude).apply()
                        calculation?.cancel()
                        calculation = scope.launch {
                            busy = true; passes = emptyList(); track = emptyList()
                            try {
                                val result = withContext(Dispatchers.Default) { SatellitePrediction.passes(data, observer, System.currentTimeMillis()) }
                                val points = result.firstOrNull()?.let { withContext(Dispatchers.Default) { SatellitePrediction.track(data, observer, it) } } ?: emptyList()
                                passes = result; track = points
                                message = if (result.isEmpty()) tr("За 24 часа пролётов не найдено", "No passes found in 24 hours") else ""
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { message = e.message ?: "Prediction failed" }
                            finally { busy = false }
                        }
                    } catch (e: Exception) { message = e.message ?: "Invalid station" }
                }) { Text(tr("Рассчитать 24 часа", "Predict 24 hours")) }

            }
        }
        if (tab == 1) item {
            Text(tr("Траектория первого пролёта: север сверху, восток справа", "First pass track: north up, east right"))
            Canvas(Modifier.fillMaxWidth().height(250.dp)) {
                val r = min(size.width, size.height) / 2 - 12f
                val center = Offset(size.width / 2, size.height / 2)
                for (fraction in listOf(1f, 2f/3, 1f/3)) drawCircle(Color.Gray, r * fraction, center, style = Stroke(1f))
                drawLine(Color.Gray, center - Offset(r, 0f), center + Offset(r, 0f))
                drawLine(Color.Gray, center - Offset(0f, r), center + Offset(0f, r))
                fun point(p: SkyPoint): Offset { val distance = r * (1 - p.elevation / (kotlin.math.PI / 2)).coerceIn(0.0, 1.0).toFloat(); return center + Offset((sin(p.azimuth) * distance).toFloat(), (-cos(p.azimuth) * distance).toFloat()) }
                track.zipWithNext().forEach { (a,b) -> drawLine(Color(0xFF43E5C4), point(a), point(b), strokeWidth = 3f) }
                track.firstOrNull()?.let { drawCircle(Color.Yellow, 5f, point(it)) }
                live?.takeIf { it.elevation >= 0 }?.let { drawCircle(Color.White, 6f, point(SkyPoint(it.azimuth, it.elevation))) }
            }
        }
        items(passes) { pass ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text("${time(pass.start)} → ${time(pass.end)}")
                Text(tr("Максимальная высота: %.1f°".format(pass.maximum), "Maximum elevation: %.1f°".format(pass.maximum)))
                if (pass.partial) Text(tr("Неполный интервал: уже виден или не заходит за 24 часа", "Partial interval: already visible or no set within 24 hours"))
            } }
        }
    }
}
