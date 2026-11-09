// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
package com.mantz_it.rfanalyzer

import com.mantz_it.rfanalyzer.satellites.SatellitePrediction
import com.rtbishop.look4sat.core.domain.predict.*
import com.rtbishop.look4sat.core.domain.utility.DataParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SatellitePredictionTest {
    private val tle = """ISS (ZARYA)
1 25544U 98067A   24001.50000000  .00010000  00000-0  18000-3 0  9990
2 25544  51.6400 100.0000 0005000  50.0000 310.0000 15.50000000430000
"""
    @Test fun importedOrbitPredictsFinitePassesWithinWindow() = runBlocking {
        val data = DataParser(Dispatchers.Default).parseTLEStream(tle.byteInputStream()).single()
        assertEquals(25544, data.catnum)
        assertEquals(0.00018, data.bstar, 1e-10)
        val now = 1704110400000L
        val station = GeoPos(51.67,39.21)
        val passes = withTimeout(10000) { SatellitePrediction.passes(data, station, now) }
        assertTrue(passes.isNotEmpty())
        passes.forEach {
            assertTrue(it.start >= now && it.end <= now + 86400000L && it.end > it.start)
            assertTrue(it.maximum > 0 && it.maximum <= 90)
            if (!it.partial) {
                assertTrue(kotlin.math.abs(data.getObject().getPosition(station,it.start).elevation) < 0.01)
                assertTrue(kotlin.math.abs(data.getObject().getPosition(station,it.end).elevation) < 0.01)
            }
        }
        assertEquals(101, SatellitePrediction.track(data,station,passes.first()).size)
    }
    @Test fun geostationarySearchIsBoundedAndNotSchedulableAsFullPass() = runBlocking {
        val data = OrbitalData("GEO test",24001.5,1.0027,0.0001,0.01,0.0,0.0,0.0,99999,0.0)
        val now=1704110400000L
        val position=data.getObject().getPosition(GeoPos(0.0,0.0),now)
        val station=GeoPos(0.0,Math.toDegrees(position.longitude).let { if(it>180) it-360 else it })
        val passes=withTimeout(10000) { SatellitePrediction.passes(data,station,now) }
        assertEquals(1,passes.size)
        assertTrue(passes.single().partial)
    }
}
