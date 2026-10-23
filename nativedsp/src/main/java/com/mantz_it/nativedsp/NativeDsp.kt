// D325f SDR changes, Logan Zarov, 2026-10-23. See docs/CHANGES.md.
package com.mantz_it.nativedsp

import android.util.Log
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

class NativeDsp {
    external fun createBandwidth(rate: Int): Long
    external fun destroyBandwidth(handle: Long)
    external fun filterBandwidth(handle: Long, raw: ByteArray, re: FloatArray, im: FloatArray, inverted: Boolean): Int
    external fun mixBandwidth(re: FloatArray, im: FloatArray, count: Int, outRe: FloatArray, outIm: FloatArray, start: Int, offset: Double, rate: Int, state: FloatArray): Int


    var window: FloatArray? = null
    var inputBuf: FloatArray? = null
    private var windowCorrection = 0f

    protected fun makeWindow(size: Int) {
        // Make a blackman window:
        // w(n)=0.42-0.5cos{(2*PI*n)/(N-1)}+0.08cos{(4*PI*n)/(N-1)};
        window = FloatArray(size)
        for (i in window!!.indices)
            window!![i] = (0.42 - 0.5 * cos(2 * Math.PI * i / (size - 1))
                + 0.08 * cos(4 * Math.PI * i / (size - 1))).toFloat()
        windowCorrection = (20.0 * log10((window!!.sum() / size).toDouble())).toFloat()
    }

    /**
     * Native methods implemented by the 'nativedsp' native library,
     * performing a FFT with the pffft library. Not thread safe!
     */
    private external fun performFFT(input: FloatArray?, output: FloatArray?)
    private external fun performFFTAndLogMag(input: FloatArray?, output: FloatArray?)
    private external fun mixDownsampleCi8Boxcar30Native(
        input: ByteArray,
        outputRe: FloatArray,
        outputIm: FloatArray,
        outputStart: Int,
        outputCapacity: Int,
        frequencyOffsetHz: Double,
        sampleRateHz: Double,
        state: FloatArray
    ): Int

    companion object {
        // Used to load the 'nativedsp' library on application startup.
        init {
            System.loadLibrary("nativedsp")
        }
    }


    /**
     * Translate a CI8 channel to DC at the original sample rate, then apply a cheap
     * 30-sample anti-alias boxcar and decimate 7.68 MSps -> 256 kSps.  The state
     * keeps NCO phase and a partial 30-sample accumulator across 32 KiB packets.
     * This is intentionally native so the LG D325 can move the demodulator marker
     * without retuning the modem or running a 7.68 MHz Java DSP loop.
     */
    fun mixDownsampleCi8Boxcar30(
        input: ByteArray,
        outputRe: FloatArray,
        outputIm: FloatArray,
        outputStart: Int,
        outputCapacity: Int,
        frequencyOffsetHz: Double,
        sampleRateHz: Double,
        state: FloatArray
    ): Int = mixDownsampleCi8Boxcar30Native(
        input, outputRe, outputIm, outputStart, outputCapacity,
        frequencyOffsetHz, sampleRateHz, state
    )

    /**
     * Applies a Blackman Window to the input samples, followed by a FFT operation.
     * Fills the array magOut with the logarithmic magnitude of the FFT results (centered around the 0-frequency)
     * IMPORTANT: This function uses native code. The native code is NOT thread safe (TODO) and therefore
     * this method is also not Thread safe!!
     */
    fun performWindowedFftAndReturnMag(re: FloatArray, im: FloatArray, magOut: FloatArray): Boolean {
        val N = re.size
        if(im.size != N || magOut.size != N)
            return false

        performWindowedSplitFFT(re, im, magOut)
        return true
    }

    /** Same power-mean detector and fractional-bin weighting as SpectrumRaster. */
    external fun unpackCi8Conjugated(input: ByteArray, re: FloatArray, im: FloatArray, start: Int): Int
    external fun conjugate(imaginary: FloatArray, start: Int, count: Int)

    external fun rasterizeSpectrum(bins: FloatArray, firstBin: Double, binsPerPixel: Double, output: FloatArray)

    external fun unpackCi8(input: ByteArray, re: FloatArray, im: FloatArray, start: Int): Int

    private external fun performWindowedSplitFFT(re: FloatArray, im: FloatArray, output: FloatArray)

}
