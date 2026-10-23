// D325f SDR changes, Logan Zarov, 2026-10-23. See docs/CHANGES.md.
package com.mantz_it.rfanalyzer.analyzer

import android.util.Log
import com.mantz_it.nativedsp.NativeDsp
import com.mantz_it.rfanalyzer.database.GlobalPerformanceData
import com.mantz_it.rfanalyzer.source.SamplePacket
import com.mantz_it.rfanalyzer.ui.composable.FftWaterfallSpeed
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.collections.indices
import kotlin.math.max

/**
 * <h1>RF Analyzer - Analyzer Processing Loop</h1>
 *
 * Module:      FftProcessor.kt
 * Description: This Thread will fetch samples from the incoming queue (provided by the scheduler),
 * do the signal processing (fft) and then forward the result to the AnalyzerSurfacee.
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


// FFT Buffer
data class DisplayRequest(val frequency: Long, val span: Long, val width: Int,
                          val sourceFrequency: Long, val sourceRate: Long, val epoch: Long)

class FftProcessorData {
    val frames = com.mantz_it.rfanalyzer.dsp.DisplayFrameQueue()
    @Volatile var displayRequest: DisplayRequest? = null
    val lock = ReentrantReadWriteLock()
    // One bounded latest-frame mailbox; history belongs exclusively to the renderer.
    var spectrum: FloatArray? = null
    var frequency: Long? = null
    var sampleRate: Long? = null
    var peaks: FloatArray? = null
    var timestampNs: Long = 0L
    @Volatile var measuredRowsPerSecond: Int = 0
    @Volatile var generation: Long = 0L

}


class FftProcessor(
    initialFftSize: Int,
    private val inputQueue: ArrayBlockingQueue<SamplePacket>, // queue that delivers sample packets
    private val returnQueue: ArrayBlockingQueue<SamplePacket>, // queue to return unused buffers
    private val fftProcessorData: FftProcessorData,
    @Volatile var waterfallSpeed: FftWaterfallSpeed,
    var fftPeakHold: Boolean,
    @Volatile var suppressDcSpike: Boolean,
    private val getChannelFrequencyRange: () -> Pair<Long, Long>?,
    private val onAverageSignalStrengthChanged: (Float) -> Unit,
) : Thread() {
    @Volatile private var stopRequested = true // Will stop the thread when set to true
    private var nativeDsp: NativeDsp = NativeDsp()

    companion object {
        private const val LOGTAG = "FftProcessor"


    }

    /**
     * Will start the processing loop
     */
    override fun start() {
        this.stopRequested = false
        super.start()
    }

    /**
     * Will set the stopRequested flag so that the processing loop will terminate
     */
    fun stopLoop() {
        this.stopRequested = true
    }

    override fun run() {
        this.setName("Thread-FftProcessor-" + System.currentTimeMillis())
        Log.i(LOGTAG, "Processing loop started. (Thread: " + this.name + ")")
        Log.i(LOGTAG, "  using Queues: $inputQueue , $returnQueue")

        var lastFrequency: Long? = null
        var lastSampleRate: Long? = null
        var magPacket = SamplePacket(0)
        var lastFftTimestampNs = 0L
        var displayRow = FloatArray(0)
        var processingCpuNs = 0.0
        var rateWindowNs = System.nanoTime()
        var rateCount = 0
        // A background Android thread may also be moved into the restricted
        // background CPU group. FFT is visible foreground work; retain a small
        // nice offset while acquisition/audio keep their higher priorities.
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT + 2)

        while (!stopRequested) {

            // fetch the next samples from the queue:
            var samples: SamplePacket?
            try {
                samples = inputQueue.poll(if (ReceiverFocus.paused) 200L else 16L, TimeUnit.MILLISECONDS)  // 16ms is roughly one frame at 60fps
                if (samples == null) {
                    //Log.d(LOGTAG, "run: Timeout while waiting on input data. skip.")
                    continue
                }
            } catch (e: InterruptedException) {
                Log.e(LOGTAG, "run: Interrupted while polling from input queue. stop.")
                this.stopLoop()
                break
            }

            if(stopRequested)
                break
            if (ReceiverFocus.paused) {
                returnQueue.offer(samples)
                lastFftTimestampNs = 0L
                fftProcessorData.measuredRowsPerSecond = 0
                continue
            }


            // Pace FFT work instead of burning CPU on every available modem block.
            // While waiting, the scheduler may queue another FFT packet; keep only the
            // newest one so the spectrum stays current rather than lagging behind.
            // Speed controls independent measurements, never the height of a row.
            val requestedHz = when (waterfallSpeed) {
                FftWaterfallSpeed.SLOW -> 15
                FftWaterfallSpeed.NORMAL -> 30
                FftWaterfallSpeed.FAST -> 50
            }
            // Leave a small CPU reserve without a 40% idle penalty at maximum FFT.
            // Descheduling behind audio/acquisition is already a delay, not DSP work.
            // Includes FFT, pixel reduction and publication; never reduce FFT length.
            val targetPeriodNs = maxOf(1_000_000_000L / requestedHz, (processingCpuNs / (if (ReceiverFocus.audioActive) AudioHeadroom.fftCpuBudget() else 0.50)).toLong())
            if (lastFftTimestampNs != 0L) {
                val remainingNs = lastFftTimestampNs + targetPeriodNs - System.nanoTime()
                if (remainingNs > 0L) {
                    try {
                        val ms = remainingNs / 1_000_000L
                        val ns = (remainingNs % 1_000_000L).toInt()
                        sleep(ms, ns)
                    } catch (_: InterruptedException) {
                        stopLoop()
                        returnQueue.offer(samples)
                        break
                    }
                }
            }
            while (true) {
                val newer = inputQueue.poll() ?: break
                returnQueue.offer(samples)
                samples = newer
            }

            if (ReceiverFocus.paused) { returnQueue.offer(samples); continue }
            val startTime = System.nanoTime()  // start of processing
            val startCpuNs = android.os.Debug.threadCpuTimeNanos()

            if(magPacket.size() != samples.size()) {
                magPacket = SamplePacket(samples.size())
            }
            magPacket.frequency = samples.frequency
            magPacket.sampleRate = samples.sampleRate
            magPacket.setSize(samples.size())

            // do the signal processing:
            nativeDsp.performWindowedFftAndReturnMag(samples.re(), samples.im(), magPacket.re())

            // RF Decoder display cleanup. The DC spur is at the center FFT bin.
            // Interpolate only the magnitude display buffer; the original IQ packet is never changed.
            if (suppressDcSpike && magPacket.size() >= 5) {
                val mag = magPacket.re()
                val center = magPacket.size() / 2
                mag[center] = (mag[center - 2] + mag[center - 1] + mag[center + 1] + mag[center + 2]) / 4f
            }

            //Log.d(LOGTAG, "After processing: ${System.currentTimeMillis()-startTime}ms")

            // return samples to the buffer pool
            returnQueue.offer(samples)

            // Update signal strength in appStateRepository:
            val samplesPerHz = magPacket.size() / magPacket.sampleRate.toFloat()
            val frequencyAtIndexZero = magPacket.frequency - magPacket.sampleRate/2
            val channelFrequencyRange = getChannelFrequencyRange()
            if(channelFrequencyRange != null) {
                val (channelStartFrequency, channelEndFrequency) = channelFrequencyRange
                val channelStartIndex = ((channelStartFrequency-frequencyAtIndexZero) * samplesPerHz).toInt() .coerceIn(0, magPacket.size())
                val channelEndIndex = ((channelEndFrequency-frequencyAtIndexZero) * samplesPerHz).toInt() .coerceIn(0, magPacket.size())
                if (channelEndIndex > channelStartIndex) {
                    var sum = 0f
                    val mag = magPacket.re()
                    for (i in channelStartIndex until channelEndIndex) sum += mag[i]
                    val averageSignalStrengh = sum / (channelEndIndex - channelStartIndex)
                    onAverageSignalStrengthChanged(averageSignalStrengh)
                }
            }

            // Performance Tracking
            GlobalPerformanceData.updateLoad("FftProcessor", (System.nanoTime() - startTime).toFloat() / targetPeriodNs)

            // Publish only the newest full-resolution frame. No scrolling, colour work,
            // history reallocations or RF-history shifts on the DSP thread.
            val changed = magPacket.frequency != lastFrequency ||
                magPacket.sampleRate.toLong() != lastSampleRate
            lastFrequency = magPacket.frequency
            lastSampleRate = magPacket.sampleRate.toLong()
            val mag = magPacket.re()
            fftProcessorData.lock.writeLock().lock()
            try {
                if (fftProcessorData.spectrum?.size != mag.size)
                    fftProcessorData.spectrum = FloatArray(mag.size)
                mag.copyInto(fftProcessorData.spectrum!!)
                fftProcessorData.frequency = magPacket.frequency
                fftProcessorData.sampleRate = magPacket.sampleRate.toLong()
                if (fftPeakHold) {
                    if (changed || fftProcessorData.peaks?.size != mag.size)
                        fftProcessorData.peaks = FloatArray(mag.size) { -160f }
                    val peaks = fftProcessorData.peaks!!
                    for (i in mag.indices) peaks[i] = max(peaks[i], mag[i])
                } else fftProcessorData.peaks = null
                fftProcessorData.timestampNs = startTime
            } finally {
                fftProcessorData.lock.writeLock().unlock()
            }
            val request = fftProcessorData.displayRequest
            if (request != null && request.sourceFrequency == magPacket.frequency &&
                request.sourceRate == magPacket.sampleRate.toLong()) {
                if (displayRow.size != request.width) displayRow = FloatArray(request.width)
                val firstBin = (request.frequency - request.span / 2.0 -
                    (magPacket.frequency - magPacket.sampleRate / 2.0)) * mag.size / magPacket.sampleRate
                val binsPerPixel = request.span.toDouble() * mag.size / magPacket.sampleRate / request.width
                nativeDsp.rasterizeSpectrum(mag, firstBin, binsPerPixel, displayRow)
                fftProcessorData.frames.offer(displayRow, startTime, request.epoch)
            }
            fftProcessorData.generation++
            val completedNs = System.nanoTime()
            val elapsedCpuNs = (android.os.Debug.threadCpuTimeNanos() - startCpuNs).coerceAtLeast(0L).toDouble()
            processingCpuNs = if (processingCpuNs == 0.0) elapsedCpuNs else processingCpuNs * 0.8 + elapsedCpuNs * 0.2
            rateCount++
            if (completedNs - rateWindowNs >= 1_000_000_000L) {
                fftProcessorData.measuredRowsPerSecond = (rateCount * 1_000_000_000L / (completedNs - rateWindowNs)).toInt()
                rateWindowNs = completedNs
                rateCount = 0
            }
            lastFftTimestampNs = startTime
            //Log.d(LOGTAG, "After draw: ${System.currentTimeMillis()-startTime}ms")
        }
        this.stopRequested = true
        Log.i(LOGTAG, "Processing loop stopped. (Thread: " + this.name + ")")
    }

}
