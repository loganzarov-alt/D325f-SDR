// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
package com.mantz_it.rfanalyzer

import com.mantz_it.rfanalyzer.analyzer.ReceiverFocus
import com.mantz_it.rfanalyzer.analyzer.Scheduler
import com.mantz_it.rfanalyzer.source.IQSourceInterface
import com.mantz_it.rfanalyzer.source.SamplePacket
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class RecordingDisplayTest {
    @Test fun recordingFeedsDisplayAndPreservesRawBytes() = exercise(false, false)
    @Test fun manualFreezeDoesNotStopRecording() = exercise(true, false)
    @Test fun uncheckingFreezeResumesDisplayDuringRecording() = exercise(true, true)

    private fun exercise(frozen: Boolean, resumeMidway: Boolean) {
        ReceiverFocus.setPaused(frozen)
        val packets = List(4) { n -> ByteArray(32) { (n + 1).toByte() } }
        val expected = packets.flatMap { it.toList() }.toByteArray()
        val returned = AtomicInteger()
        val displayed = AtomicInteger()
        var next = 0
        val source = Proxy.newProxyInstance(IQSourceInterface::class.java.classLoader,
            arrayOf(IQSourceInterface::class.java)) { _, method, args ->
            when (method.name) {
                "getPacketSize" -> 32
                "getBytesPerSample" -> 2
                "getSampleRate" -> 7680000
                "getPacket" -> {
                    if (resumeMidway && next == 2) ReceiverFocus.resume()
                    packets.getOrNull(next++)
                }
                "fillPacketIntoSamplePacket" -> {
                    val raw = args!![0] as ByteArray
                    check(raw.all { it != 0.toByte() }) { "Packet recycled before DSP finished" }
                    displayed.incrementAndGet()
                    val samples = args[1] as SamplePacket
                    samples.setSize(samples.capacity())
                    samples.capacity()
                }
                "returnPacket" -> {
                    returned.incrementAndGet()
                    (args!![0] as ByteArray).fill(0)
                    null
                }
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
        } as IQSourceInterface
        val output = ByteArrayOutputStream()
        val scheduler = Scheduler(16, source)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        scheduler.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> failure.set(error) }
        try {
            scheduler.startRecording(BufferedOutputStream(output), false,
                onRecordingStopped = {}, onFileSizeUpdate = {})
            scheduler.start()
            scheduler.join(5000)
            assertFalse("Scheduler did not stop", scheduler.isAlive)
            failure.get()?.let { throw AssertionError("Scheduler failed", it) }
            assertArrayEquals(expected, output.toByteArray())
            assertEquals(4, returned.get())
            if (!frozen || resumeMidway) {
                assertTrue("Recording must feed FFT", displayed.get() > 0)
                assertFalse(scheduler.fftOutputQueue.isEmpty())
            } else {
                assertEquals(0, displayed.get())
                assertTrue(scheduler.fftOutputQueue.isEmpty())
            }
        } finally {
            scheduler.stopScheduler()
            scheduler.join(5000)
            ReceiverFocus.resume()
        }
    }
}
