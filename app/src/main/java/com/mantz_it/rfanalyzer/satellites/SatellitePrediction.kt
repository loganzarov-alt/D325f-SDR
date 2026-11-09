// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
package com.mantz_it.rfanalyzer.satellites

import com.rtbishop.look4sat.core.domain.predict.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class SatellitePass(val start: Long, val end: Long, val maximum: Double, val partial: Boolean)
data class SkyPoint(val azimuth: Double, val elevation: Double)

/** Bounded search: no unbounded horizon loop for GEO or circumpolar objects. */
object SatellitePrediction {
    suspend fun passes(data: OrbitalData, station: GeoPos, now: Long): List<SatellitePass> {
        require(station.latitude.isFinite() && station.latitude in -90.0..90.0)
        require(station.longitude.isFinite() && station.longitude in -180.0..180.0)
        require(data.meanmo.isFinite() && data.meanmo > 0 && data.eccn in 0.0..<1.0)
        val satellite = data.getObject()
        val end = now + 24 * 3600_000L
        val result = mutableListOf<SatellitePass>()
        var previousTime = now
        var previous = satellite.getPosition(station, now).elevation
        var start = if (previous > 0) now else 0L
        var maximum = previous
        var partial = previous > 0
        fun crossing(a: Long, b: Long, rising: Boolean): Long {
            var low = a; var high = b
            while (high - low > 500) {
                val mid = low + (high - low) / 2
                if ((satellite.getPosition(station, mid).elevation > 0) == rising) high = mid else low = mid
            }
            return high
        }
        var time = now + 15_000L
        while (time <= end) {
            currentCoroutineContext().ensureActive()
            val elevation = satellite.getPosition(station, time).elevation
            check(elevation.isFinite()) { "Invalid orbital position" }
            if (previous <= 0 && elevation > 0) {
                start = crossing(previousTime, time, true); maximum = elevation; partial = false
            }
            if (elevation > maximum) maximum = elevation
            if (previous > 0 && elevation <= 0) {
                result += SatellitePass(start, crossing(previousTime, time, false), Math.toDegrees(maximum), partial)
                start = 0; maximum = 0.0
            }
            previous = elevation; previousTime = time; time += 15_000L
        }
        if (start != 0L) result += SatellitePass(start, end, Math.toDegrees(maximum), true)
        return result
    }

    suspend fun track(data: OrbitalData, station: GeoPos, pass: SatellitePass): List<SkyPoint> {
        val sat = data.getObject()
        return (0..100).map { index ->
            currentCoroutineContext().ensureActive()
            val p = sat.getPosition(station, pass.start + (pass.end - pass.start) * index / 100)
            SkyPoint(p.azimuth, p.elevation)
        }
    }
}
