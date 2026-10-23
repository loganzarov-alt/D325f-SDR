// D325f SDR changes, Logan Zarov, 2026-10-23. See docs/CHANGES.md.
package com.mantz_it.rfanalyzer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.content.ContextCompat
import com.mantz_it.rfanalyzer.database.AppStateRepository
import com.mantz_it.rfanalyzer.database.AppStateRepository.Companion.VERTICAL_SCALE_LOWER_BOUNDARY
import com.mantz_it.rfanalyzer.database.AppStateRepository.Companion.VERTICAL_SCALE_UPPER_BOUNDARY
import com.mantz_it.rfanalyzer.database.collectAppState
import com.mantz_it.rfanalyzer.source.SamplePacket
import com.mantz_it.rfanalyzer.ui.composable.DemodulationMode
import com.mantz_it.rfanalyzer.ui.composable.FftColorMap
import com.mantz_it.rfanalyzer.ui.composable.FftDrawingType
import com.mantz_it.rfanalyzer.ui.composable.FftWaterfallSpeed
import com.mantz_it.rfanalyzer.ui.composable.FontSize
import com.mantz_it.rfanalyzer.ui.composable.asSizeInBytesToString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.ArrayBlockingQueue
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import com.mantz_it.rfanalyzer.R
import com.mantz_it.rfanalyzer.analyzer.FftProcessorData
import com.mantz_it.rfanalyzer.database.GlobalPerformanceData

/**
 * <h1>RF Analyzer - Analyzer Surface (FFT/Waterfall View)</h1>
 *
 * Module:      AnalyzerSurface.kt
 * Description: The custom SurfaceView which draws the FFT and the Waterfall plots.
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


data class AnalyzerSurfaceActions(
    val onViewportFrequencyChanged: (Long) -> Unit,
    val onViewportSampleRateChanged: (Long) -> Unit,
    val onViewportVerticalScaleChanged: (Pair<Float, Float>) -> Unit,  // former minDB, maxDB
    val onChannelFrequencyChanged: (newFrequency: Long) -> Unit,
    val onChannelWidthChanged: (newWidth: Int) -> Unit,
    val onSquelchChanged: (newSquelch: Float) -> Unit
)

class AnalyzerSurface(context: Context,
                      private val sourceName: AppStateRepository.State<String>,
                      private val sourceOptimalSampleRates: AppStateRepository.State<List<Long>>,
                      private val sourceFrequency: AppStateRepository.State<Long>,
                      private val sourceSampleRate: AppStateRepository.State<Long>,
                      private val sourceSignalStartFrequency: AppStateRepository.State<Long>,
                      private val sourceSignalEndFrequency: AppStateRepository.State<Long>,
                      private val fftAverageLength: AppStateRepository.State<Int>,
                      private val fftPeakHold: AppStateRepository.State<Boolean>,
                      private val maxFrameRate: AppStateRepository.State<Int>,
                      private val waterfallSpeed: AppStateRepository.State<FftWaterfallSpeed>,
                      private val waterfallColorMap: AppStateRepository.State<FftColorMap>,
                      private val fftDrawingType: AppStateRepository.State<FftDrawingType>,
                      private val fftRelativeFrequency: AppStateRepository.State<Boolean>,
                      private val fftWaterfallRatio: AppStateRepository.State<Float>,
                      private val demodulationMode: AppStateRepository.State<DemodulationMode>,
                      private val demodulationEnabled: AppStateRepository.State<Boolean>,
                      private val channelFrequency: AppStateRepository.State<Long>,
                      private val channelWidth: AppStateRepository.State<Int>,
                      private val channelStartFrequency: AppStateRepository.State<Long>,
                      private val channelEndFrequency: AppStateRepository.State<Long>,
                      private val squelchEnabled: AppStateRepository.State<Boolean>,
                      private val squelch: AppStateRepository.State<Float>,
                      private val recordingRunning: AppStateRepository.State<Boolean>,
                      private val recordOnlyWhenSquelchIsSatisfied: AppStateRepository.State<Boolean>,
                      private val recordingCurrentFileSize: AppStateRepository.State<Long>,
                      private val fontSize: AppStateRepository.State<FontSize>,
                      private val showDebugInformation: AppStateRepository.State<Boolean>,
                      private val viewportFrequency: AppStateRepository.State<Long>,
                      private val viewportSampleRate: AppStateRepository.State<Long>,
                      private val viewportVerticalScaleMin: AppStateRepository.State<Float>,
                      private val viewportVerticalScaleMax: AppStateRepository.State<Float>,
                      private val viewportStartFrequency: AppStateRepository.State<Long>,
                      private val viewportEndFrequency: AppStateRepository.State<Long>,
                      private val averageSignalStrength: AppStateRepository.State<Float>,
                      private val squelchSatisfied: AppStateRepository.State<Boolean>,
                      private val fftProcessorData: FftProcessorData,
                      private val analyzerSurfaceActions: AnalyzerSurfaceActions) : SurfaceView(context){

    companion object {
        private const val LOGTAG = "AnalyzerSurface"
        private const val MIN_VIRTUAL_SAMPLERATE = 64L // Smallest virtual sample rate

        const val STROKE_WIDTH_CHANNELWIDTHSELECTOR = 1f
        const val STROKE_WIDTH_SQUELCH = 3f
        const val STROKE_WIDTH_CHANNELSELECTOR = 3f
        const val STROKE_WIDTH_ACTIVE = 6f
    }

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var drawingThread: DrawThread? = null
    private var waterfallBitmap: Bitmap? = null
    private var waterfallHead = 0
    private var retainedRows = 0

    // Gesture detectors to detect scaling, scrolling ...
    private var scaleGestureDetector: ScaleGestureDetector
    private var gestureDetector: GestureDetector
    private var surfaceHolder: SurfaceHolder? = null

    // internal state
    @Volatile private var width = 0 // current width (in pixels) of the SurfaceView
    @Volatile private var height = 0 // current height (in pixels) of the SurfaceView
    private var doAutoscaleInNextDraw = false // will cause draw() to adjust minDB and maxDB according to the samples

    // derived (calculated) properties
    private val fftHeight: Int  // Returns the height of the fft plot in px (y coordinate of the bottom line of the fft spectrum)
        get() = (height * fftWaterfallRatio.value).toInt()
    private val waterfallHeight: Int // Returns the height of the waterfall plot in px
        get() = (height * (1 - fftWaterfallRatio.value)).toInt()
    private val gridSize: Int // Returns the height/width of the frequency/power grid in px
        get() {
            val xdpi = resources.displayMetrics.xdpi
            val xpixel = resources.displayMetrics.widthPixels.toFloat()
            val xinch = xpixel / xdpi

            return if (xinch < 30) (75 * xdpi / 200).toInt() // Smartphone / Tablet / Computer screen
            else (400 * xdpi / 200).toInt() // TV screen
        }
    private val channelSelectorDragHandlePosition: Pair<Float, Float> // vertical position of the drag handles (in dB; first: channel handle; second: channelWidth handle)
        get() {
            // handles should be at 1/3 distance from the top if squelch is below half and vice versa
            val minDB = viewportVerticalScaleMin.value
            val maxDB = viewportVerticalScaleMax.value
            val viewportHeightInDb = maxDB - minDB
            val centerHandlePosition: Float
            val outerHandlePosition: Float
            if(squelch.value < minDB + viewportHeightInDb/2) {
                centerHandlePosition = maxDB - viewportHeightInDb * 0.35f
                outerHandlePosition = maxDB - viewportHeightInDb * 0.2f
            } else {
                centerHandlePosition = minDB + viewportHeightInDb * 0.35f
                outerHandlePosition = minDB + viewportHeightInDb * 0.2f
            }
            return Pair(centerHandlePosition, outerHandlePosition)
        }

    // scroll type stores the intention of the user on a pointer down event:
    enum class ScrollType() {
        NONE,
        NORMAL,
        NORMAL_VERTICAL,
        CHANNEL_FREQUENCY,
        CHANNEL_WIDTH_LEFT,
        CHANNEL_WIDTH_RIGHT,
        SQUELCH
    }
    private var scrollType = ScrollType.NORMAL

    private final val scaleGestureListener = object: ScaleGestureDetector.OnScaleGestureListener {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            // A second finger changes a marker drag into a viewport pinch.
            if (scrollType != ScrollType.NORMAL_VERTICAL) scrollType = ScrollType.NORMAL
            return true
        }
        override fun onScaleEnd(detector: ScaleGestureDetector) { }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            when (scrollType) {
                ScrollType.NORMAL -> {
                    // Use the full two-finger distance: nearly vertical pinches must
                    // zoom just as reliably as horizontal ones on the small LG screen.
                    val factor = detector.scaleFactor.toDouble()
                    if (!factor.isFinite() || factor <= 0.0 || width <= 0) return true
                    val xScale = Math.pow(factor, 3.2).coerceIn(0.25, 4.0)
                    val oldRate = viewportSampleRate.value
                    val sourceMaxSampleRate = sourceSampleRate.value.coerceAtLeast(MIN_VIRTUAL_SAMPLERATE)
                    val newVirtualSampleRate = (oldRate / xScale).toLong().coerceIn(MIN_VIRTUAL_SAMPLERATE, sourceMaxSampleRate)
                    val focusFraction = detector.focusX.toDouble() / width - 0.5
                    // Keep the frequency underneath the fingers stationary.
                    val newVirtualFrequency = (viewportFrequency.value + focusFraction * (oldRate - newVirtualSampleRate)).toLong()
                    analyzerSurfaceActions.onViewportSampleRateChanged(newVirtualSampleRate)
                    analyzerSurfaceActions.onViewportFrequencyChanged(newVirtualFrequency)
                }
                ScrollType.NORMAL_VERTICAL -> {
                    val minDB = viewportVerticalScaleMin.value
                    val maxDB = viewportVerticalScaleMax.value
                    val yScale = detector.currentSpanY / detector.previousSpanY
                    val dBFocus = maxDB - (maxDB - minDB) * (detector.focusY / fftHeight)
                    val newMinDB = (dBFocus - (dBFocus - minDB) / yScale).coerceIn(VERTICAL_SCALE_LOWER_BOUNDARY, VERTICAL_SCALE_UPPER_BOUNDARY - 10)
                    val newMaxDB = (dBFocus - (dBFocus - maxDB) / yScale).coerceIn(newMinDB + 10, VERTICAL_SCALE_UPPER_BOUNDARY)
                    analyzerSurfaceActions.onViewportVerticalScaleChanged(Pair(newMinDB, newMaxDB))
                }
                else -> { } // if the user tapped on a drag handle we don't scale but handle it in onScroll
            }
            return true
        }
    }

    private val gestureListener = object: GestureDetector.OnGestureListener {
        override fun onDown(e: MotionEvent): Boolean {
            // Find out which type of scrolling is requested:
            val demodulationEnabled = demodulationEnabled.value
            val demodulationMode = demodulationMode.value
            val minDB = viewportVerticalScaleMin.value
            val maxDB = viewportVerticalScaleMax.value
            val channelFrequency = channelFrequency.value
            val channelStartFrequency = channelStartFrequency.value
            val channelEndFrequency = channelEndFrequency.value
            val squelchEnabled = squelchEnabled.value
            val squelch = squelch.value
            val hzPerPx = viewportSampleRate.value / width.toFloat()
            val dbPerPx = (maxDB - minDB) / fftHeight.toFloat()
            val channelFrequencyVariation = viewportSampleRate.value * 0.05f
            val dbVariation = (maxDB - minDB) * 0.1f
            val touchedFrequency = viewportStartFrequency.value + (e.x * hzPerPx).toLong()
            val touchedDB = maxDB - e.y * dbPerPx
            val showUpperBand = demodulationMode != DemodulationMode.LSB
            val (centerDragHandlePositionDb, outerDragHandlePositionDb) = channelSelectorDragHandlePosition

            // Frozen waterfall is a frequency-selection surface: drag the demodulator,
            // never retune/pan the receiver behind the frozen measurements.
            if (demodulationEnabled && com.mantz_it.rfanalyzer.analyzer.ReceiverFocus.paused && e.y >= fftHeight) {
                scrollType = ScrollType.CHANNEL_FREQUENCY
                analyzerSurfaceActions.onChannelFrequencyChanged(touchedFrequency.coerceIn(sourceSignalStartFrequency.value, sourceSignalEndFrequency.value))
                drawingThread?.channelSelectorPaint?.strokeWidth = STROKE_WIDTH_ACTIVE
                return true
            }

            // if the user touched the squelch indicator the user wants to adjust the squelch threshold:
            val squelchHandlePositionInHz = if (showUpperBand) channelEndFrequency else channelStartFrequency
            if (demodulationEnabled && squelchEnabled
                    && touchedFrequency < squelchHandlePositionInHz + channelFrequencyVariation
                    && touchedFrequency > squelchHandlePositionInHz - channelFrequencyVariation
                    && touchedDB < squelch + dbVariation
                    && touchedDB > squelch - dbVariation) {
                scrollType = ScrollType.SQUELCH
                drawingThread?.squelchPaint?.strokeWidth = STROKE_WIDTH_ACTIVE
            } else if (demodulationEnabled
                    && touchedDB in centerDragHandlePositionDb - dbVariation .. centerDragHandlePositionDb + dbVariation
                    && touchedFrequency < channelFrequency + channelFrequencyVariation
                    && touchedFrequency > channelFrequency - channelFrequencyVariation) {
                scrollType = ScrollType.CHANNEL_FREQUENCY
                drawingThread?.channelSelectorPaint?.strokeWidth = STROKE_WIDTH_ACTIVE
            } else if (demodulationEnabled && !showUpperBand
                    && touchedDB in outerDragHandlePositionDb - dbVariation .. outerDragHandlePositionDb + dbVariation
                    && touchedFrequency < channelStartFrequency + channelFrequencyVariation
                    && touchedFrequency > channelStartFrequency - channelFrequencyVariation) {
                scrollType = ScrollType.CHANNEL_WIDTH_LEFT
                drawingThread?.channelWidthSelectorPaint?.strokeWidth = STROKE_WIDTH_ACTIVE
            } else if (demodulationEnabled && showUpperBand
                    && touchedDB in outerDragHandlePositionDb - dbVariation .. outerDragHandlePositionDb + dbVariation
                    && touchedFrequency < channelEndFrequency + channelFrequencyVariation
                    && touchedFrequency > channelEndFrequency - channelFrequencyVariation
            ) {
                scrollType = ScrollType.CHANNEL_WIDTH_RIGHT
                drawingThread?.channelWidthSelectorPaint?.strokeWidth = STROKE_WIDTH_ACTIVE
            } else if (e.x > gridSize * 1.5 || e.y > fftHeight - gridSize)
                scrollType = ScrollType.NORMAL
            else
                scrollType = ScrollType.NORMAL_VERTICAL


            return true
        }

        override fun onShowPress(e: MotionEvent) { }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // Set the channel frequency to the tapped position
            if (demodulationEnabled.value) {
                val hzPerPx = viewportSampleRate.value / width.toFloat()
                val newChannelFrequency = viewportStartFrequency.value + (hzPerPx * e.x).toLong()
                analyzerSurfaceActions.onChannelFrequencyChanged(newChannelFrequency.coerceIn(sourceSignalStartFrequency.value, sourceSignalEndFrequency.value))
            }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2:  MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if(e1 == null)
                return true
            val hzPerPx = viewportSampleRate.value / width.toFloat()

            // scroll horizontally or adjust channel selector (scroll type was selected in onDown() event routine:
            when (scrollType) {
                ScrollType.NONE -> Log.w(LOGTAG, "onScroll: No ScrollType specified!")
                // Scroll horizontal if touch point in the main area
                ScrollType.NORMAL -> {
                    // Scroll viewport frequency (horizontal)
                    val oldViewportFrequency = viewportFrequency.value
                    val newViewportFrequency = (oldViewportFrequency + (hzPerPx * distanceX * 2.5f).toLong())
                    analyzerSurfaceActions.onViewportFrequencyChanged(newViewportFrequency)
                }
                ScrollType.NORMAL_VERTICAL -> {
                    // Scroll viewport vertical scale
                    val minDB = viewportVerticalScaleMin.value
                    val maxDB = viewportVerticalScaleMax.value
                    var yDiff = (maxDB - minDB) * (distanceY / fftHeight.toFloat())
                    // Make sure we stay in the boundaries:
                    if (maxDB - yDiff > VERTICAL_SCALE_UPPER_BOUNDARY) yDiff = VERTICAL_SCALE_UPPER_BOUNDARY - maxDB
                    if (minDB - yDiff < VERTICAL_SCALE_LOWER_BOUNDARY) yDiff = VERTICAL_SCALE_LOWER_BOUNDARY - minDB
                    analyzerSurfaceActions.onViewportVerticalScaleChanged(Pair(minDB - yDiff, maxDB - yDiff))
                }

                ScrollType.CHANNEL_FREQUENCY -> {
                    val newChannelFrequency = (channelFrequency.value - distanceX * hzPerPx).toLong()
                    analyzerSurfaceActions.onChannelFrequencyChanged(newChannelFrequency.coerceIn(sourceSignalStartFrequency.value, sourceSignalEndFrequency.value))
                }

                ScrollType.CHANNEL_WIDTH_LEFT, ScrollType.CHANNEL_WIDTH_RIGHT -> {
                    val tmpChannelWidth = if (scrollType == ScrollType.CHANNEL_WIDTH_LEFT) (channelWidth.value + distanceX * hzPerPx).toInt() else (channelWidth.value - distanceX * hzPerPx).toInt()
                    analyzerSurfaceActions.onChannelWidthChanged(tmpChannelWidth.coerceIn(demodulationMode.value.minChannelWidth, demodulationMode.value.maxChannelWidth))
                }

                ScrollType.SQUELCH -> {
                    val minDB = viewportVerticalScaleMin.value
                    val maxDB = viewportVerticalScaleMax.value
                    val dbPerPx = (maxDB - minDB) / fftHeight.toFloat()
                    val newSquelch = squelch.value + distanceY * dbPerPx
                    analyzerSurfaceActions.onSquelchChanged(newSquelch.coerceIn(minDB, maxDB))
                }
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) { }
        override fun onFling( e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float ): Boolean { return true }
    }


    init {
        // Add a Callback to get informed when the dimensions of the SurfaceView changes:
        this.holder.addCallback(object: SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                this@AnalyzerSurface.surfaceHolder = holder
                Log.d(LOGTAG, "surfaceCreated")
                // Start drawing thread
                if(drawingThread != null) {
                    Log.w(LOGTAG, "surfaceCreated: drawingThread not null (${drawingThread?.name}). state: ${drawingThread?.running}. join()..")
                    drawingThread?.running = false
                    drawingThread?.join()
                }
                drawingThread = DrawThread().apply { running = true }
                drawingThread?.start()
            }

            /**
             * SurfaceHolder.Callback function. This is called every time the dimension changes
             * (and after the SurfaceView is created).
             *
             * @param holder    reference to the surface holder
             * @param format
             * @param width        current width of the surface view
             * @param height    current height of the surface view
             */
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                this@AnalyzerSurface.surfaceHolder = holder
                if (this@AnalyzerSurface.width != width || this@AnalyzerSurface.height != height) {
                    this@AnalyzerSurface.width = width
                    this@AnalyzerSurface.height = height
                    drawingThread?.apply { updateFftPaint() }
                    drawingThread?.apply { updateTextPaint() }
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d(LOGTAG, "surfaceDestroyed")
                if(drawingThread != null) {
                    drawingThread?.running = false
                    drawingThread?.join()
                    Log.d(LOGTAG, "surfaceDestroyed: drawingThread ${drawingThread?.name} joined.")
                    drawingThread = null
                }
            }
        })

        // Instantiate the (scale-) gesture detectors:
        this.scaleGestureDetector = ScaleGestureDetector(context, scaleGestureListener)
        this.gestureDetector = GestureDetector(context, gestureListener)
        this.gestureDetector.setIsLongpressEnabled(false)

        // react to changes of the view model:
        observeAppState()
    }

    private fun observeAppState() {
        fun updateViewport() {
            // if viewport is out of range of the source's new frequency and samplerate, we try to fix it
            // Keep the viewport within the useful rate exposed by the source.  The LG
            // modem transports 7.68 MSps but only the central 5 MHz passband is useful.
            val maximumVisibleSampleRate = minOf(
                sourceSampleRate.value,
                sourceOptimalSampleRates.value.lastOrNull()?.takeIf { it > 0 } ?: sourceSampleRate.value
            )
            if (viewportSampleRate.value > maximumVisibleSampleRate) {
                analyzerSurfaceActions.onViewportSampleRateChanged(maximumVisibleSampleRate)
            }
            // if the viewport is completely outside of the actual signal, we reset freq and sample rate:
            if (viewportStartFrequency.value > sourceSignalEndFrequency.value || viewportEndFrequency.value < sourceSignalStartFrequency.value) {
                //Log.d(LOGTAG, "observeAppState: calling onViewportFrequencyChanged(${sourceFrequency.value})")
                analyzerSurfaceActions.onViewportFrequencyChanged(sourceFrequency.value)
                //Log.d(LOGTAG, "observeAppState: calling onViewportSampleRateChanged(${sourceSampleRate.value}) [two]")
                analyzerSurfaceActions.onViewportSampleRateChanged(sourceSampleRate.value)
            }
            // if the viewport is a bit to much on the right we shift it left:
            if (viewportEndFrequency.value > sourceSignalEndFrequency.value) {
                val sef = sourceSignalEndFrequency.value
                val sf  = sourceFrequency.value
                val newViewportFrequency = sef - viewportSampleRate.value / 2
                //Log.d(LOGTAG, "observeAppState: calling onViewportFrequencyChanged($newViewportFrequency)  [sf=$sf   sef=$sef]")
                analyzerSurfaceActions.onViewportFrequencyChanged(newViewportFrequency)
            }
            // if the viewport is a bit to much on the left we shift it right:
            if (viewportStartFrequency.value < sourceSignalStartFrequency.value) {
                val newViewportFrequency = sourceSignalStartFrequency.value + viewportSampleRate.value / 2
                //Log.d(LOGTAG, "observeAppState: calling onViewportFrequencyChanged($newViewportFrequency) [four]")
                analyzerSurfaceActions.onViewportFrequencyChanged(newViewportFrequency)
            }
        }
        coroutineScope.collectAppState(sourceFrequency) { updateViewport() }
        coroutineScope.collectAppState(sourceSampleRate) { updateViewport() }
        coroutineScope.collectAppState(fftWaterfallRatio) { drawingThread?.apply { updateFftPaint() } }
        coroutineScope.collectAppState(fontSize) { drawingThread?.apply { updateTextPaint() } }
        coroutineScope.collectAppState(waterfallColorMap) { drawingThread?.createWaterfallColorMap(it) }
        coroutineScope.collectAppState(fftDrawingType) { drawingThread?.apply { updateFftPaint() } }
        coroutineScope.collectAppState(squelchSatisfied) { drawingThread?.squelchPaint?.color = if(it) Color.GREEN else Color.RED }
    }

    /**
     * Will cause the surface to automatically adjust the dB scale at the
     * next call of draw() so that it fits the incoming fft samples perfectly
     */
    fun autoscale() {
        this.doAutoscaleInNextDraw = true
    }


    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Reset the stroke width of the channel controls if the user lifts his finger:
        if (event.action == MotionEvent.ACTION_UP) {
            drawingThread?.squelchPaint?.strokeWidth = STROKE_WIDTH_SQUELCH
            drawingThread?.channelSelectorPaint?.strokeWidth = STROKE_WIDTH_CHANNELSELECTOR
            drawingThread?.channelWidthSelectorPaint?.strokeWidth = STROKE_WIDTH_CHANNELWIDTHSELECTOR
            scrollType = ScrollType.NONE
        }

        var retVal = scaleGestureDetector.onTouchEvent(event)
        retVal = gestureDetector.onTouchEvent(event) || retVal
        return retVal
    }

    //
    //  ---- DRAWING -----
    //

    private inner class DrawThread() : Thread() {
        private val displayDsp = com.mantz_it.nativedsp.NativeDsp()
        @Volatile var running = false

        // Objects for Drawing
        private val bounds = Rect()                             // A bounds object to calculate the box size of text
        private val defaultPaint: Paint = Paint()               // Paint object to draw bitmaps on the canvas
        // One measured row per screen pixel; history is never vertically enlarged.
        private val waterfallPaint: Paint = Paint().apply { isFilterBitmap = false }
        private val blackPaint: Paint = Paint()                 // Paint object to draw black (erase)
        private val fftPaint: Paint = Paint()                   // Paint object to draw the fft lines
        private val peakHoldPaint: Paint = Paint()              // Paint object to draw the fft peak hold points
        private val textPaint: Paint = Paint()                  // Paint object to draw text on the canvas
        private val textSmallPaint: Paint = Paint()             // Paint object to draw small text on the canvas
        val channelSelectorPaint: Paint = Paint()       // Paint object to draw the area of the channel
        val channelWidthSelectorPaint: Paint = Paint()  // Paint object to draw the borders of the channel
        val squelchPaint: Paint = Paint()               // Paint object to draw the squelch selector
        private val leftRightArrowDrawable = ContextCompat.getDrawable(context, R.drawable.left_right_arrow_icon)
        private val topBottomArrowDrawable = ContextCompat.getDrawable(context, R.drawable.top_bottom_arrow_icon)
        private var fftPath: Path = Path()                  // Path object to draw the fft lines
        private lateinit var waterfallColorMapArray: IntArray    // Colors used to draw the waterfall plot.
        private var fftGridBitmap: Bitmap? = null           // bitmap caching the power and frequency grid of the fft
        private var fftGridCanvas: Canvas? = null           // canvas for drawing onto the fftGridBitmap
        private val fftGridBitmapState: MutableMap<String, Any> = HashMap() // Hashmap to track all relevant variables for the grid
                                                                            // (stores all variables which influence the grid at the time the bitmap was drawn)
        private var gpu: GpuWaterfall? = null
        private var overlayBitmap: Bitmap? = null
        private var overlayDirty = true
        private var lastStatsNs = 0L
        private var gpuGeometry = ""
        private var peaksYCoordinates: FloatArray? = null
        private var spectrumSnapshot = FloatArray(0)
        private var peakSnapshot: FloatArray? = null
        private var pixelDb = FloatArray(0)
        private var smoothDb = FloatArray(0)
        private var rowColors = IntArray(0)
        private var displayEpoch = 0L
        private var pendingRows = Array(6) { FloatArray(0) }
        private val pendingTimes = LongArray(6)
        private var pendingCount = 0
        private var requestedGeometry = ""
        private var lastSmoothNs = 0L
        private var lastTraceDrawNs = 0L
        private var spectrumAnimating = false
        private var lastGeometry = ""
        private var frameFrequency = 0L
        private var frameSampleRate = 1L
        private var frameTimestampNs = 0L
        private var snapshotGeneration = -1L
        private val sourceRect = Rect()
        private val destinationRect = Rect()

        // Copy one frame while holding a read lock, release before any rendering.
        private fun snapshotLatest(): Boolean {
            if (!fftProcessorData.lock.readLock().tryLock()) return false
            try {
                val source = fftProcessorData.spectrum ?: return false
                val snapshotWidth = width
                val requestKey = "${snapshotWidth}/${viewportFrequency.value}/${viewportSampleRate.value}/${fftProcessorData.frequency}/${fftProcessorData.sampleRate}"
                val geometryChanged = requestKey != requestedGeometry
                if (geometryChanged && snapshotWidth > 0) {
                    requestedGeometry = requestKey
                    displayEpoch = (fftProcessorData.displayRequest?.epoch ?: 0L) + 1L
                    if (pendingRows[0].size != snapshotWidth) pendingRows = Array(6) { FloatArray(snapshotWidth) }
                    fftProcessorData.frames.configure(snapshotWidth, displayEpoch)
                    fftProcessorData.displayRequest = com.mantz_it.rfanalyzer.analyzer.DisplayRequest(
                        viewportFrequency.value, viewportSampleRate.value.coerceAtLeast(1L), snapshotWidth,
                        fftProcessorData.frequency ?: 0L, fftProcessorData.sampleRate ?: 1L, displayEpoch)
                }
                pendingCount = fftProcessorData.frames.drain(pendingRows, pendingTimes, displayEpoch)
                // Full-resolution copy is only needed for a changed viewport or when
                // no compact row has arrived yet; never copy the whole history.
                if (geometryChanged || (pendingCount == 0 && snapshotGeneration != fftProcessorData.generation) || spectrumSnapshot.size != source.size) {
                    if (spectrumSnapshot.size != source.size) spectrumSnapshot = FloatArray(source.size)
                    source.copyInto(spectrumSnapshot)
                }
                val peaks = fftProcessorData.peaks
                if (peaks != null) {
                    if (peakSnapshot?.size != peaks.size) peakSnapshot = FloatArray(peaks.size)
                    if (snapshotGeneration != fftProcessorData.generation) peaks.copyInto(peakSnapshot!!)
                } else peakSnapshot = null
                frameFrequency = fftProcessorData.frequency ?: return false
                frameSampleRate = fftProcessorData.sampleRate ?: return false
                frameTimestampNs = fftProcessorData.timestampNs
                snapshotGeneration = fftProcessorData.generation
                return true
            } finally { fftProcessorData.lock.readLock().unlock() }
        }

        init {
            blackPaint.color = Color.rgb(5, 10, 15)
            fftPaint.color = Color.rgb(52, 229, 196)
            peakHoldPaint.color = Color.YELLOW
            peakHoldPaint.style = Paint.Style.FILL
            peakHoldPaint.strokeWidth = 2f
            textPaint.color = Color.WHITE
            textPaint.isAntiAlias = true
            textSmallPaint.color = Color.WHITE
            textSmallPaint.isAntiAlias = true
            channelSelectorPaint.color = Color.YELLOW
            channelWidthSelectorPaint.color = Color.WHITE
            squelchPaint.color = if (squelchSatisfied.value) Color.GREEN else Color.RED

            // Create the color map for the waterfall plot
            this.createWaterfallColorMap(waterfallColorMap.value)
            updateFftPaint()
            updateTextPaint()
        }

        override fun run() {
            this.setName("Thread-DrawThread-" + System.currentTimeMillis())
            Log.i(LOGTAG, "DrawThread started. (Thread: " + this.name + ")")

            // EGL must own the Surface before any Canvas lock on API 25.
            try { gpu = GpuWaterfall(holder.surface) }
            catch (e: RuntimeException) { Log.e(LOGTAG, "GPU init failed; software fallback", e) }
            if (gpu == null) drawWatermark()

            var frameRateTimestamp = System.currentTimeMillis()
            var frameRateFrameCounter = 0
            var frameRate = 0
            var lastRenderedGeneration = -1L
            var lastForcedDrawMs = 0L
            var wasFrozen = false

            try {
            while (running) {
                val frozen = com.mantz_it.rfanalyzer.analyzer.ReceiverFocus.paused
                gpu?.setFrozen(frozen)
                if (wasFrozen && !frozen) {
                    requestedGeometry = ""
                    lastRenderedGeneration = -1L
                }
                wasFrozen = frozen
                val startTimestamp = System.currentTimeMillis()

                // Local current state (better performance, and safer because width/height could change at any time)
                val width = width
                val fftHeight = fftHeight

                if (width <= 0 || fftHeight <= 0) { sleep(50); continue }
                var doDraw = false
                val fresh = !frozen && fftProcessorData.generation != lastRenderedGeneration
                val viewportChanged = hasFftGridChanged()
                if (!frozen && (fresh || viewportChanged) && snapshotLatest()) {
                    try { drawPreprocessing(fresh) }
                    catch (e: RuntimeException) {
                        if (gpu == null) throw e
                        Log.e(LOGTAG, "GPU history update failed; software fallback", e)
                        gpu?.close()
                        gpu = null
                        lastGeometry = ""
                        drawPreprocessing(fresh)
                    }
                    lastRenderedGeneration = snapshotGeneration
                    doDraw = true
                } else if (waterfallBitmap != null && (viewportChanged || startTimestamp - lastForcedDrawMs >= if (frozen) 33L else 250L)) {
                    doDraw = true
                }

                if (!frozen && (gpu?.isAnimating == true || spectrumAnimating) && waterfallBitmap != null) doDraw = true
                if (doDraw) {
                    frameRateFrameCounter++
                    if(startTimestamp-frameRateTimestamp > 1000) {
                        frameRate = (frameRateFrameCounter * 1000f / (startTimestamp-frameRateTimestamp)).toInt()
                        frameRateFrameCounter = 0
                        frameRateTimestamp = startTimestamp
                    }
                    draw(frameRate)
                    lastForcedDrawMs = startTimestamp
                }

                // measure current frametime to derive how long we need to sleep to meet the user's preferred framerate
                val frameDrawingTime = System.currentTimeMillis()-startTimestamp
                val rendererLimit = if (gpu != null) 60 else 15
                val requestedRate = if (gpu != null && sourceName.value.startsWith("LG D325"))
                    maxOf(30, maxFrameRate.value) else maxFrameRate.value
                val desiredFrameTimeMs = 1000/requestedRate.coerceIn(1, rendererLimit)
                GlobalPerformanceData.updateLoad("Renderer", frameDrawingTime.toFloat()/desiredFrameTimeMs)
                val sleepTime = desiredFrameTimeMs - frameDrawingTime
                sleep(sleepTime.coerceAtLeast(0))
            }
            } finally {
                gpu?.close()
                gpu = null
                overlayBitmap?.recycle()
                overlayBitmap = null
            }
            Log.i(LOGTAG, "DrawThread stopped. (Thread: " + this.name + ")")
        }

        private fun drawPreprocessing(fresh: Boolean) {
            val w = width
            val h = maxOf(1024, height - fftHeight)
            if (w <= 0 || spectrumSnapshot.isEmpty() || frameSampleRate <= 0) return
            if (pendingCount > 0 && pendingRows[0].size != w) pendingCount = 0
            val low = viewportVerticalScaleMin.value
            val high = max(low + 10f, viewportVerticalScaleMax.value)
            val span = viewportSampleRate.value.coerceAtLeast(1L)
            val center = viewportFrequency.value
            val geometry = "$w/$h/$center/$span/$low/$high/$frameFrequency/$frameSampleRate/${spectrumSnapshot.size}/${waterfallSpeed.value}/${waterfallColorMap.value}"
            val reset = geometry != lastGeometry
            val historyGeometry = "$w/$h"
            val resizeHistory = waterfallBitmap?.width != w || waterfallBitmap?.height != h
            if (waterfallBitmap?.width != w || waterfallBitmap?.height != h) {
                val previous = waterfallBitmap
                val oldHead = waterfallHead
                val replacement = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
                replacement.eraseColor(Color.rgb(5, 10, 15))
                val keep = minOf(retainedRows, h, previous?.height ?: 0)
                if (previous != null) {
                    val row = IntArray(minOf(w, previous.width))
                    for (age in 0 until keep) {
                        previous.getPixels(row, 0, row.size, 0, (oldHead + age) % previous.height, row.size, 1)
                        replacement.setPixels(row, 0, row.size, 0, age, row.size, 1)
                    }
                    previous.recycle()
                }
                waterfallBitmap = replacement
                waterfallHead = 0
                retainedRows = keep
            }
            if (pixelDb.size != w) {
                pixelDb = FloatArray(w)
                smoothDb = FloatArray(w)
                rowColors = IntArray(w)
                peaksYCoordinates = FloatArray(w)
            }
            if (reset) {
                // A raster row represents one RF viewport. Never remap old observations
                // into a different frequency grid or recolour them as if freshly measured.
                lastSmoothNs = 0L
                lastGeometry = geometry
            }
            if (gpu != null && gpuGeometry != historyGeometry) {
                gpu!!.reset(w, h)
                val savedRow = IntArray(w)
                for (age in retainedRows - 1 downTo 0) {
                    waterfallBitmap!!.getPixels(savedRow, 0, w, 0, (waterfallHead + age) % h, w, 1)
                    gpu!!.append(savedRow, age.toLong())
                }
                gpuGeometry = historyGeometry
            }
            val firstBin = (center - span / 2.0 - (frameFrequency - frameSampleRate / 2.0)) * spectrumSnapshot.size / frameSampleRate
            val binsPerPixel = span.toDouble() * spectrumSnapshot.size / frameSampleRate / w
            if (pendingCount > 0) pendingRows[pendingCount - 1].copyInto(pixelDb)
            else displayDsp.rasterizeSpectrum(spectrumSnapshot, firstBin, binsPerPixel, pixelDb)
            // Time-based exponential smoothing affects only the spectrum trace.
            // Waterfall rows retain the unsmoothed measurement.
            val dt = if (lastSmoothNs == 0L) 1.0 else (frameTimestampNs - lastSmoothNs).coerceAtLeast(0L) / 1e9
            val tau = fftAverageLength.value.coerceIn(0, 32) * 0.05
            val alpha = if (reset || tau == 0.0 || lastSmoothNs == 0L) 1f else (1.0 - Math.exp(-dt / tau)).toFloat()
            if (gpu == null) {
                fftPath.reset()
                fftPath.moveTo(0f, fftHeight.toFloat())
            }
            var minMeasured = high
            var maxMeasured = low
            for (x in 0 until w) {
                if (gpu == null || reset) smoothDb[x] += alpha * (pixelDb[x] - smoothDb[x])
                val db = smoothDb[x]
                if (gpu == null) fftPath.lineTo(x.toFloat(), (fftHeight * (high - db) / (high - low)).coerceIn(0f, fftHeight.toFloat()))
                minMeasured = min(minMeasured, db)
                maxMeasured = max(maxMeasured, db)
            }
            if (gpu == null) fftPath.lineTo(w.toFloat(), fftHeight.toFloat())
            lastSmoothNs = frameTimestampNs
            val peaks = peakSnapshot
            if (fftPeakHold.value && peaks != null) {
                val coords = peaksYCoordinates ?: FloatArray(w).also { peaksYCoordinates = it }
                displayDsp.rasterizeSpectrum(peaks, firstBin, binsPerPixel, coords)
                for (x in coords.indices) coords[x] = (fftHeight * (high - coords[x]) / (high - low)).coerceIn(0f, fftHeight.toFloat())
            } else peaksYCoordinates = null
            if (doAutoscaleInNextDraw && minMeasured < maxMeasured) {
                analyzerSurfaceActions.onViewportVerticalScaleChanged(Pair(
                    (minMeasured - 5f).coerceAtLeast(VERTICAL_SCALE_LOWER_BOUNDARY),
                    (maxMeasured + 5f).coerceAtMost(VERTICAL_SCALE_UPPER_BOUNDARY)))
                doAutoscaleInNextDraw = false
            }
            spectrumAnimating = gpu != null
            // Every row is an actual FFT result. Batch append even when the screen
            // was late; do not fabricate black lines or duplicate measurements.
            for (row in 0 until pendingCount) {
                val values = pendingRows[row]
                val colours = waterfallColorMapArray
                for (x in 0 until w) {
                    val level = ((values[x] - low) / (high - low)).coerceIn(0f, 1f)
                    // Monotonic contrast curve; endpoints and RF/dB calibration stay intact.
                    val contrast = level * level * (3f - 2f * level)
                    val index = (contrast * (colours.size - 1)).toInt().coerceIn(0, colours.lastIndex)
                    rowColors[x] = colours[index]
                }
                waterfallHead = (waterfallHead + h - 1) % h
                if (gpu != null) gpu!!.append(rowColors, pendingTimes[row])
                waterfallBitmap!!.setPixels(rowColors, 0, w, 0, waterfallHead, w, 1)
                retainedRows = minOf(h, retainedRows + 1)
            }
            pendingCount = 0
        }

        private fun drawWaterfall(canvas: Canvas) {
            val bitmap = waterfallBitmap ?: return
            val rows = bitmap.height
            val split = rows - waterfallHead
            sourceRect.set(0, waterfallHead, bitmap.width, rows)
            destinationRect.set(0, fftHeight, width, fftHeight + split)
            canvas.drawBitmap(bitmap, sourceRect, destinationRect, waterfallPaint)
            if (waterfallHead > 0) {
                sourceRect.set(0, 0, bitmap.width, waterfallHead)
                destinationRect.set(0, fftHeight + split, width, fftHeight + rows)
                canvas.drawBitmap(bitmap, sourceRect, destinationRect, waterfallPaint)
            }
        }

        /**
         * Will (re-)draw the surface.
         *
         * @param frameRate    current frame rate (FPS)
         */
        fun draw(frameRate: Int) {
            // Redraw/recreate the fft grid bitmap if necessary
            if (hasFftGridChanged()) {
                overlayDirty = true
                //Log.d(LOGTAG, "SURFACE - before redraw grid: " + (System.currentTimeMillis()-startTime));
                if (fftGridBitmap == null || fftGridBitmap!!.height != fftHeight || fftGridBitmap!!.width != width) {
                    // fft height has changed!
                    fftGridBitmap = Bitmap.createBitmap(width, fftHeight, Bitmap.Config.ARGB_8888)
                    fftGridCanvas = Canvas(fftGridBitmap!!)
                }
                // Clear the bitmap with transparency
                fftGridCanvas!!.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                drawFrequencyGrid(fftGridCanvas!!)
                drawPowerGrid(fftGridCanvas!!)
                // save the current state:
                fftGridBitmapState["fftHeight"] = fftHeight
                fftGridBitmapState["fontSize"] = fontSize
                fftGridBitmapState["scrollType"] = scrollType
                fftGridBitmapState["virtualSampleRate"] = viewportSampleRate.value
                fftGridBitmapState["virtualFrequency"] = viewportFrequency.value
                fftGridBitmapState["width"] = width
                fftGridBitmapState["displayRelativeFrequencies"] = fftRelativeFrequency.value
                fftGridBitmapState["demodulationEnabled"] = demodulationEnabled.value
                fftGridBitmapState["channelWidth"] = channelWidth.value
                fftGridBitmapState["channelFrequency"] = channelFrequency.value
                fftGridBitmapState["squelchEnabled"] = squelchEnabled.value
                fftGridBitmapState["squelch"] = squelch.value
                fftGridBitmapState["minDB"] = viewportVerticalScaleMin.value
                fftGridBitmapState["maxDB"] = viewportVerticalScaleMax.value
                fftGridBitmapState["squelchPaintStrokeWidth"] = squelchPaint.strokeWidth
                fftGridBitmapState["squelchPaintColor"] = squelchPaint.color
                fftGridBitmapState["channelSelectorStrokeWidth"] = channelSelectorPaint.strokeWidth
                fftGridBitmapState["channelWidthSelectorStrokeWidth"] = channelWidthSelectorPaint.strokeWidth
                fftGridBitmapState["showUpperBand"] = demodulationMode.value != DemodulationMode.USB
                fftGridBitmapState["showLowerBand"] = demodulationMode.value != DemodulationMode.LSB
            }

            if (gpu != null) {
                try {
                    if (overlayBitmap?.width != width || overlayBitmap?.height != fftHeight) {
                        overlayBitmap?.recycle()
                        overlayBitmap = Bitmap.createBitmap(width, fftHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        overlayDirty = true
                    }
                    // Static scales/channel shading upload only when geometry or UI changes.
                    if (overlayDirty) gpu!!.uploadOverlay(fftGridBitmap!!)
                    val nowNs = System.nanoTime()
                    if (overlayDirty || nowNs - lastStatsNs >= 250_000_000L) {
                        val top = Canvas(overlayBitmap!!)
                        top.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                        drawPerformanceInfo(top, frameRate, averageSignalStrength.value)
                        gpu!!.uploadStats(overlayBitmap!!)
                        lastStatsNs = nowNs
                    }
                    overlayDirty = false
                    val channelX = if (demodulationEnabled.value)
                        width / 2f + width / viewportSampleRate.value.toFloat() * (channelFrequency.value - viewportFrequency.value)
                        else -1f
                    // Interpolate only the trace at display cadence; keep waterfall measurements raw.
                    val traceDt = if (lastTraceDrawNs == 0L) 1.0 else (nowNs - lastTraceDrawNs).coerceAtLeast(0L) / 1e9
                    lastTraceDrawNs = nowNs
                    val traceTau = maxOf(0.045, fftAverageLength.value.coerceIn(0, 32) * 0.05)
                    val traceAlpha = if (com.mantz_it.rfanalyzer.analyzer.ReceiverFocus.paused) 0f else (1.0 - Math.exp(-traceDt / traceTau)).toFloat()
                    spectrumAnimating = false
                    for (x in pixelDb.indices) {
                        smoothDb[x] += traceAlpha * (pixelDb[x] - smoothDb[x])
                        if (kotlin.math.abs(pixelDb[x] - smoothDb[x]) > 0.02f) spectrumAnimating = true
                    }
                    gpu!!.updateSpectrum(smoothDb, viewportVerticalScaleMin.value,
                        max(viewportVerticalScaleMin.value + 10f, viewportVerticalScaleMax.value), peaksYCoordinates, fftHeight)
                    gpu!!.render(width, height, fftHeight, fftDrawingType.value == FftDrawingType.BAR, channelX)
                    return
                } catch (e: RuntimeException) {
                    Log.e(LOGTAG, "GPU rendering failed; reset to software fallback", e)
                    gpu?.close()
                    gpu = null
                    lastGeometry = ""
                }
            }

            // Draw:
            var c: Canvas? = null
            try {
                c = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    holder.lockHardwareCanvas()
                } else {
                    holder.lockCanvas()
                }
                synchronized(holder) {
                    if (c != null) {
                        // Clear the canvas:
                        c.drawColor(Color.rgb(5, 10, 15))
                        // Draw FFT:
                        c.drawPath(fftPath, fftPaint)
                        // Draw peaks
                        if (peaksYCoordinates != null)
                            for(i in 0 until width)
                                c.drawPoint(i.toFloat(), peaksYCoordinates!![i], peakHoldPaint)
                        // One FFT frame is one screen row: never stretch a short history into thick bands.
                        drawWaterfall(c)
                        // Draw Grid (with channel selector)
                        c.drawBitmap(fftGridBitmap!!, 0f, 0f, null)

                        // draw channel frequency line into waterfall:
                        if (demodulationEnabled.value) {
                            val pxPerHz = width / viewportSampleRate.value.toFloat()
                            val channelPosition = width / 2 + pxPerHz * (channelFrequency.value - viewportFrequency.value)
                            c.drawLine(channelPosition, fftHeight.toFloat(), channelPosition, height.toFloat(), channelSelectorPaint)
                        }

                        drawPerformanceInfo(c, frameRate, averageSignalStrength.value)
                    } else Log.d(LOGTAG, "draw: Canvas is null.")
                }
            } catch (e: Exception) {
                Log.e(LOGTAG, "draw: Error while drawing on the canvas. Stop!")
                e.printStackTrace()
            } finally {
                if (c != null) {
                    holder.unlockCanvasAndPost(c)
                }
            }
        }

        /**
         * checks current state vs old state (fftGridBitmapState)
         * @return true if the fft grid bitmap must be redrawn
         */
        private fun hasFftGridChanged(): Boolean {
            if (!fftGridBitmapState.containsKey("fftHeight"))  // test if hashmap was initialized with values already
                return true
            return !(fftGridBitmapState["fftHeight"] == fftHeight &&
                    fftGridBitmapState["fontSize"] == fontSize &&
                    fftGridBitmapState["scrollType"] == scrollType &&
                    fftGridBitmapState["virtualSampleRate"] ==viewportSampleRate.value &&
                    fftGridBitmapState["virtualFrequency"] == viewportFrequency.value &&
                    fftGridBitmapState["width"] == width &&
                    fftGridBitmapState["displayRelativeFrequencies"] == fftRelativeFrequency.value &&
                    fftGridBitmapState["demodulationEnabled"] == demodulationEnabled.value &&
                    fftGridBitmapState["channelWidth"] == channelWidth.value &&
                    fftGridBitmapState["channelFrequency"] == channelFrequency.value &&
                    fftGridBitmapState["squelchEnabled"] == squelchEnabled.value &&
                    fftGridBitmapState["squelch"] == squelch.value &&
                    fftGridBitmapState["minDB"] == viewportVerticalScaleMin.value &&
                    fftGridBitmapState["maxDB"] == viewportVerticalScaleMax.value &&
                    fftGridBitmapState["squelchPaintStrokeWidth"] == squelchPaint.strokeWidth &&
                    fftGridBitmapState["squelchPaintColor"] == squelchPaint.color &&
                    fftGridBitmapState["channelSelectorStrokeWidth"] == channelSelectorPaint.strokeWidth &&
                    fftGridBitmapState["channelWidthSelectorStrokeWidth"] == channelWidthSelectorPaint.strokeWidth &&
                    fftGridBitmapState["showUpperBand"] == (demodulationMode.value != DemodulationMode.USB) &&
                    fftGridBitmapState["showLowerBand"] == (demodulationMode.value != DemodulationMode.LSB))
        }

        /**
         * This method will draw the frequency grid into the canvas
         *
         * @param c                canvas of the surface view
         */
        private fun drawFrequencyGrid(c: Canvas) {
            val showLowerBand = demodulationMode.value != DemodulationMode.USB
            val showUpperBand = demodulationMode.value != DemodulationMode.LSB
            val minDB = viewportVerticalScaleMin.value
            val maxDB = viewportVerticalScaleMax.value
            val virtualFrequency = viewportFrequency.value
            val virtualSampleRate = viewportSampleRate.value
            var textStr: String
            val MHZ = 1000000.0
            var tickFreqMHz: Double
            var lastTextEndPos = -99999f // will indicate the horizontal pixel pos where the last text ended
            var textPos: Float

            // Calculate the min space (in px) between text if we want it separated by at least
            // the same space as two dashes would consume.
            textPaint.getTextBounds("--", 0, 2, bounds)
            val minFreeSpaceBetweenText = bounds.width().toFloat()

            // Calculate span of a minor tick (must be a power of 10KHz)
            var tickSize = 10 // we start with 10KHz
            var helperVar = virtualSampleRate / 20f
            while (helperVar > 100) {
                helperVar = helperVar / 10f
                tickSize = tickSize * 10
            }

            // Calculate pixel width of a minor tick
            val pixelPerMinorTick = width / (virtualSampleRate / tickSize.toFloat())

            // Calculate the frequency at the left most point of the fft:
            val startFrequency =
                if (fftRelativeFrequency.value) ((virtualFrequency-sourceFrequency.value) - (virtualSampleRate / 2.0)).toLong()
                else (virtualFrequency - (virtualSampleRate / 2.0)).toLong()

            // Calculate the frequency and position of the first Tick (ticks are every <tickSize> KHz)
            var tickFreq = (ceil(startFrequency.toDouble() / tickSize.toFloat()) * tickSize).toLong()
            var tickPos = pixelPerMinorTick / tickSize.toFloat() * (tickFreq - startFrequency)

            // Draw the ticks
            var i = 0
            while (i < virtualSampleRate / tickSize.toFloat()) {
                var tickHeight: Float
                if (tickFreq % (tickSize * 10) == 0L) {
                    // Major Tick (10x <tickSize> KHz)
                    tickHeight = (gridSize / 2.0).toFloat()

                    // Draw Frequency Text (always in MHz)
                    tickFreqMHz = tickFreq / MHZ
                    textStr = if (tickFreqMHz == tickFreqMHz.toInt().toDouble()) String.format("%d", tickFreqMHz.toInt()) else String.format("%s", tickFreqMHz)
                    textPaint.getTextBounds(textStr, 0, textStr.length, bounds)
                    textPos = tickPos - bounds.width() / 2

                    // ...only if not overlapping with the last text:
                    if (lastTextEndPos + minFreeSpaceBetweenText < textPos) {
                        c.drawText(textStr, textPos, fftHeight - tickHeight, textPaint)
                        lastTextEndPos = textPos + bounds.width()
                    }
                } else if (tickFreq % (tickSize * 5) == 0L) {
                    // Half major tick (5x <tickSize> KHz)
                    tickHeight = (gridSize / 3.0).toFloat()

                    // Draw Frequency Text (always in MHz)...
                    tickFreqMHz = tickFreq / MHZ
                    textStr = if (tickFreqMHz == tickFreqMHz.toInt().toDouble()) String.format("%d", tickFreqMHz.toInt()) else String.format("%s", tickFreqMHz)
                    textSmallPaint.getTextBounds(textStr, 0, textStr.length, bounds)
                    textPos = tickPos - bounds.width() / 2

                    // ...only if not overlapping with the last text:
                    if (lastTextEndPos + minFreeSpaceBetweenText < textPos) {
                        // ... if enough space between the major ticks:
                        if (bounds.width() < pixelPerMinorTick * 3) {
                            c.drawText(textStr, textPos, fftHeight - tickHeight, textSmallPaint)
                            lastTextEndPos = textPos + bounds.width()
                        }
                    }
                } else {
                    // Minor tick (<tickSize> KHz)
                    tickHeight = (gridSize / 4.0).toFloat()
                }

                // Draw the tick line:
                c.drawLine(
                    tickPos, fftHeight.toFloat(), tickPos, fftHeight - tickHeight,
                    textPaint
                )
                tickFreq += tickSize.toLong()
                tickPos += pixelPerMinorTick
                i++
            }

            // If demodulation is activated: draw channel selector:
            if (demodulationEnabled.value) {
                val squelchEnabled = squelchEnabled.value
                val pxPerHz = width / virtualSampleRate.toFloat()
                val channelPosition = width / 2 + pxPerHz * (channelFrequency.value - virtualFrequency)
                val leftBorder = width / 2 + pxPerHz * (channelStartFrequency.value - virtualFrequency)
                val rightBorder = width / 2 + pxPerHz * (channelEndFrequency.value - virtualFrequency)
                //val leftBorder = channelPosition - pxPerHz * channelWidth.value
                //val rightBorder = channelPosition + pxPerHz * channelWidth.value
                val dbWidth = fftHeight / (maxDB - minDB)
                val squelchYPosition = if(squelchEnabled) fftHeight - (squelch.value - minDB) * dbWidth else fftHeight.toFloat()
                val squelchXPosition = if(showUpperBand) rightBorder else leftBorder
                val (centerDragHandlePositionDb, outerDragHandlePositionDb) = channelSelectorDragHandlePosition
                val centerDragHandleYPosition = fftHeight - (centerDragHandlePositionDb-minDB)*dbWidth
                val outerDragHandleYPosition = fftHeight - (outerDragHandlePositionDb-minDB)*dbWidth

                // draw half transparent channel area:
                channelWidthSelectorPaint.alpha = 0x40
                c.drawRect(leftBorder, 0f, rightBorder, squelchYPosition, channelWidthSelectorPaint)
                channelWidthSelectorPaint.alpha = 0xff

                // draw outer borders
                c.drawLine(leftBorder, fftHeight.toFloat(), leftBorder, 0f, channelWidthSelectorPaint)
                c.drawLine(rightBorder, fftHeight.toFloat(), rightBorder, 0f, channelWidthSelectorPaint)
                // draw center
                c.drawLine(channelPosition, fftHeight.toFloat(), channelPosition, 0f,channelSelectorPaint)

                // draw channel drag handle
                val dragHandleBounds = RectF(channelPosition - 30f, centerDragHandleYPosition - 15f, channelPosition + 30f, centerDragHandleYPosition + 15f)
                c.drawOval(dragHandleBounds, channelSelectorPaint)
                leftRightArrowDrawable?.bounds = Rect(dragHandleBounds.left.toInt() + 5, dragHandleBounds.top.toInt() + 5, dragHandleBounds.right.toInt() - 5, dragHandleBounds.bottom.toInt() - 5)
                leftRightArrowDrawable?.draw(c)

                // draw channel width drag handle
                val channelWidthHandleBounds = RectF(squelchXPosition - 30f, outerDragHandleYPosition - 15f, squelchXPosition + 30f, outerDragHandleYPosition + 15f)
                c.drawOval(channelWidthHandleBounds, channelWidthSelectorPaint)
                leftRightArrowDrawable?.bounds = Rect(channelWidthHandleBounds.left.toInt() + 5, channelWidthHandleBounds.top.toInt() + 5, channelWidthHandleBounds.right.toInt() - 5, channelWidthHandleBounds.bottom.toInt() - 5)
                leftRightArrowDrawable?.draw(c)

                // draw squelch
                if (squelchEnabled) {
                    c.drawLine(leftBorder, squelchYPosition, rightBorder, squelchYPosition, squelchPaint)

                    // draw squelch text above the squelch selector:
                    textStr = String.format("%2.1f dB", squelch.value)
                    textSmallPaint.getTextBounds(textStr, 0, textStr.length, bounds)
                    c.drawText(textStr, channelPosition - bounds.width() / 2f, squelchYPosition - bounds.height() * 0.1f, textSmallPaint)

                    // draw drag handle on squelch
                    val squelchHandleBounds = RectF(squelchXPosition - 15f, squelchYPosition - 30f, squelchXPosition + 15f, squelchYPosition + 30f)
                    c.drawOval(squelchHandleBounds, squelchPaint)
                    topBottomArrowDrawable?.bounds = Rect(squelchHandleBounds.left.toInt() + 5, squelchHandleBounds.top.toInt() + 5, squelchHandleBounds.right.toInt() - 5, squelchHandleBounds.bottom.toInt() - 5)
                    topBottomArrowDrawable?.draw(c)

                    // draw channel width text below the squelch selector:
                    var shownChannelWidth = 0
                    if (showLowerBand) shownChannelWidth += channelWidth.value
                    if (showUpperBand) shownChannelWidth += channelWidth.value
                    textStr = String.format("%d kHz", shownChannelWidth / 1000)
                    textSmallPaint.getTextBounds(textStr, 0, textStr.length, bounds)
                    c.drawText(textStr, channelPosition - bounds.width() / 2f, squelchYPosition + bounds.height() * 1.1f, textSmallPaint)
                }
            }
        }

        /**
         * This method will draw the power grid into the canvas
         *
         * @param c                canvas of the surface view
         */
        private fun drawPowerGrid(c: Canvas) {
            // Calculate pixel height of a minor tick (1dB)
            val minDB = viewportVerticalScaleMin.value
            val maxDB = viewportVerticalScaleMax.value
            val pixelPerMinorTick = (fftHeight / (maxDB - minDB))

            // During vertical scroll/scale we highlight the left axis:
            if (scrollType == ScrollType.NORMAL_VERTICAL) {
                channelWidthSelectorPaint.alpha = 0x5f
                c.drawRect(0f, 0f, gridSize.toFloat(), fftHeight.toFloat() - gridSize, channelWidthSelectorPaint)
                channelWidthSelectorPaint.alpha = 0xff
            }

            // Draw the ticks from the top to the bottom. Stop as soon as we interfere with the frequency scale
            var tickDB = maxDB.toInt()
            var tickPos = (maxDB - tickDB) * pixelPerMinorTick
            while (tickDB > minDB) {
                var tickWidth: Float
                if (tickDB % 10 == 0) {
                    // Major Tick (10dB)
                    tickWidth = (gridSize / 3.0).toFloat()
                    // Draw Frequency Text:
                    c.drawText("" + tickDB, (gridSize / 2.9).toFloat(), tickPos, textPaint)
                } else if (tickDB % 5 == 0) {
                    // 5 dB tick
                    tickWidth = (gridSize / 3.5).toFloat()
                } else {
                    // Minor tick
                    tickWidth = (gridSize / 5.0).toFloat()
                }
                c.drawLine(0f, tickPos, tickWidth, tickPos, textPaint)
                tickPos += pixelPerMinorTick

                // stop if we interfere with the frequency grid:
                if (tickPos > fftHeight - gridSize) break
                tickDB--
            }
        }

        /**
         * This method will draw the performance information into the canvas
         *
         * @param c                canvas of the surface view
         * @param frameRate    current frame rate (FPS)
         * @param load            current load (percentage [0..1])
         * @param averageSignalStrength        average magnitude of the signal in the selected channel
         */
        private fun drawPerformanceInfo(c: Canvas, frameRate: Int, averageSignalStrength: Float) {
            var text: String
            var yPos = height * 0.01f
            val rightBorder = width * 0.99f
            val minDB = viewportVerticalScaleMin.value
            val maxDB = viewportVerticalScaleMax.value

            // Name
            text = sourceName.value
            textSmallPaint.getTextBounds(text, 0, text.length, bounds)
            c.drawText(text, rightBorder - bounds.width(), yPos + bounds.height(), textSmallPaint);
            yPos += bounds.height() * 1.1f;

            if (demodulationEnabled.value) {
                // Draw the channel frequency if demodulation is enabled:
                text = String.format("demod at %4.6f MHz", channelFrequency.value / 1000000f)
                textSmallPaint.getTextBounds(text, 0, text.length, bounds)
                c.drawText(text, rightBorder - bounds.width(), yPos + bounds.height(), textSmallPaint)

                // increase yPos:
                yPos += bounds.height() * 1.1f

                // Draw the average signal strength indicator if demodulation is enabled
                text = String.format("%2.1f dB", averageSignalStrength)
                textSmallPaint.getTextBounds(text, 0, text.length, bounds)

                val indicatorWidth = (width / 10).toFloat()
                val indicatorPosX = rightBorder - indicatorWidth
                val indicatorPosY = yPos + bounds.height()
                val squelchTickPos = (squelch.value - minDB) / (maxDB - minDB) * indicatorWidth
                var signalWidth = (averageSignalStrength - minDB) / (maxDB - minDB) * indicatorWidth
                if (signalWidth < 0) signalWidth = 0f
                if (signalWidth > indicatorWidth) signalWidth = indicatorWidth

                // draw signal rectangle:
                c.drawRect(
                    indicatorPosX,
                    yPos + bounds.height() * 0.1f,
                    indicatorPosX + signalWidth,
                    indicatorPosY,
                    squelchPaint
                )

                // draw left border, right border, bottom line and squelch tick:
                c.drawLine(indicatorPosX, indicatorPosY, indicatorPosX, yPos, textPaint)
                c.drawLine(rightBorder, indicatorPosY, rightBorder, yPos, textPaint)
                c.drawLine(indicatorPosX, indicatorPosY, rightBorder, indicatorPosY, textPaint)
                c.drawLine(
                    indicatorPosX + squelchTickPos,
                    indicatorPosY + 2,
                    indicatorPosX + squelchTickPos,
                    yPos + bounds.height() * 0.5f,
                    textPaint
                )

                // draw text:
                c.drawText(text, indicatorPosX - bounds.width() * 1.1f, indicatorPosY, textSmallPaint)

                // increase yPos:
                yPos += bounds.height() * 1.1f
            }

            // Draw recording information
            if (recordingRunning.value) {
                if (squelchSatisfied.value || !recordOnlyWhenSquelchIsSatisfied.value) {
                    text = String.format("%4.6f MHz @ %2.3f MSps (%s)", sourceFrequency.value / 1000000f, sourceSampleRate.value / 1000000f, recordingCurrentFileSize.value.asSizeInBytesToString())
                    textSmallPaint.getTextBounds(text, 0, text.length, bounds)
                    c.drawText(text, rightBorder - bounds.width(), yPos + bounds.height(), textSmallPaint)
                    defaultPaint.color = Color.RED
                    c.drawCircle(
                        rightBorder - bounds.width() - (bounds.height() / 2) * 1.3f,
                        yPos + bounds.height() / 2,
                        (bounds.height() / 2).toFloat(),
                        defaultPaint
                    )
                    // increase yPos:
                    yPos += bounds.height() * 1.1f
                }
            }

            if (showDebugInformation.value) {
                // Draw the FFT/s rate
                text = "$frameRate FPS / ${fftProcessorData.measuredRowsPerSecond} FFT/s"
                textSmallPaint.getTextBounds(text, 0, text.length, bounds)
                c.drawText(text, rightBorder - bounds.width(), yPos + bounds.height(), textSmallPaint)
                yPos += bounds.height() * 1.1f

                // Draw the load
                GlobalPerformanceData.getAllLoads().forEach { (metricId, value) ->
                    text = String.format("$metricId: %03.1f %%", value * 100)
                    textSmallPaint.getTextBounds(text, 0, text.length, bounds)
                    c.drawText(text, rightBorder - bounds.width(), yPos + bounds.height(), textSmallPaint)
                    yPos += bounds.height() * 1.1f
                }
            }
        }

        /**
         * Will populate the waterfallColorMap array with color instances
         */
        fun createWaterfallColorMap(colorMap: FftColorMap) {
            this.waterfallColorMapArray = createColorMap(colorMap)
        }

        fun updateFftPaint() {
            when(fftDrawingType.value) {
                FftDrawingType.BAR -> fftPaint.style = Paint.Style.FILL
                FftDrawingType.LINE ->  fftPaint.style = Paint.Style.STROKE
            }
            // Recreate the shaders:
            fftPaint.apply { setShader( LinearGradient( 0f, 0f, 0f, fftHeight.toFloat(), Color.rgb(174, 255, 241), Color.rgb(52, 229, 196), Shader.TileMode.MIRROR ) ) }
        }

        fun updateTextPaint() {
            val normalTextSize: Float
            val smallTextSize: Float
            when (fontSize.value) {
                FontSize.SMALL -> {
                    normalTextSize = gridSize * 0.2f
                    smallTextSize = gridSize * 0.13f
                }
                FontSize.NORMAL -> {
                    normalTextSize = gridSize * 0.3f
                    smallTextSize = gridSize * 0.2f
                }
                FontSize.LARGE -> {
                    normalTextSize = gridSize * 0.476f
                    smallTextSize = gridSize * 0.25f
                }
            }
            textPaint.textSize = normalTextSize
            textSmallPaint.textSize = smallTextSize
            Log.i(
                LOGTAG, "setFontSize: X-dpi=${resources.displayMetrics.xdpi} X-width=${resources.displayMetrics.widthPixels}" +
                        "  fontSize=${fontSize}  normalTextSize=${normalTextSize} smallTextSize=${smallTextSize}"
            )
        }

        fun drawWatermark() {
            var c: Canvas? = null
            try {
                c = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    holder.lockHardwareCanvas()
                } else {
                    holder.lockCanvas()
                }
                synchronized(holder) {
                    if (c != null) {
                        val canvas = c ?: return@synchronized
                        val bg = Color.rgb(5, 10, 15)
                        val grid = Color.rgb(14, 27, 37)
                        val teal = Color.rgb(52, 229, 196)
                        val text = Color.rgb(230, 237, 244)
                        val muted = Color.rgb(106, 132, 151)
                        canvas.drawColor(bg)

                        val gridPaint = Paint().apply { color = grid; strokeWidth = 1f }
                        val xStep = (width / 12f).coerceAtLeast(40f)
                        val yStep = (height / 16f).coerceAtLeast(40f)
                        var x = 0f
                        while (x <= width) { canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint); x += xStep }
                        var y = 0f
                        while (y <= height) { canvas.drawLine(0f, y, width.toFloat(), y, gridPaint); y += yStep }

                        // A small deterministic idle trace, matching the RF Decoder stopped screen.
                        val tracePaint = Paint().apply { color = teal; strokeWidth = 2.5f; style = Paint.Style.STROKE; isAntiAlias = true }
                        val trace = Path()
                        val baseline = height * 0.43f
                        val amp = (height * 0.018f).coerceIn(8f, 28f)
                        val points = 36
                        for (i in 0..points) {
                            val px = width * i / points.toFloat()
                            val wave = ((i * 37) % 9 - 4) / 4f
                            val py = baseline + wave * amp
                            if (i == 0) trace.moveTo(px, py) else trace.lineTo(px, py)
                        }
                        canvas.drawPath(trace, tracePaint)

                        val titlePaint = Paint().apply { color = text; textSize = (width * 0.047f).coerceIn(34f, 52f); isAntiAlias = true; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
                        val captionPaint = Paint().apply { color = muted; textSize = (width * 0.022f).coerceIn(17f, 25f); isAntiAlias = true; textAlign = Paint.Align.CENTER }
                        canvas.drawText("RF DECODER", width / 2f, height * 0.58f, titlePaint)
                        canvas.drawText("READY · SELECT A SOURCE AND START", width / 2f, height * 0.635f, captionPaint)
                    } else Log.d(LOGTAG, "drawWatermark: Canvas is null.")
                }
            } catch (e: Exception) {
                Log.e(LOGTAG, "drawWatermark: Error while drawing on the canvas. Stop!", e)
            } finally {
                if (c != null) holder.unlockCanvasAndPost(c)
            }
        }
    }
}
