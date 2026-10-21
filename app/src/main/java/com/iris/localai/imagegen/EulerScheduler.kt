package com.iris.localai.imagegen

import kotlin.math.sqrt

/** SD1.5 EulerDiscreteScheduler (scaled_linear betas, linspace spacing, epsilon prediction). */
class EulerScheduler(
    val trainSteps: Int = 1000,
    betaStart: Double = 0.00085,
    betaEnd: Double = 0.012,
) {
    val alphasCumprod: DoubleArray
    /** Training sigmas, index = timestep. */
    val trainSigmas: DoubleArray

    var timesteps: FloatArray = FloatArray(0); private set
    /** n+1 entries; last is 0. */
    var sigmas: FloatArray = FloatArray(0); private set

    init {
        val s0 = sqrt(betaStart); val s1 = sqrt(betaEnd)
        val betas = DoubleArray(trainSteps) { val v = s0 + (s1 - s0) * it / (trainSteps - 1); v * v }
        alphasCumprod = DoubleArray(trainSteps)
        var acc = 1.0
        for (i in 0 until trainSteps) { acc *= (1.0 - betas[i]); alphasCumprod[i] = acc }
        trainSigmas = DoubleArray(trainSteps) { sqrt((1 - alphasCumprod[it]) / alphasCumprod[it]) }
    }

    val initNoiseSigma: Float get() {
        val m = if (sigmas.isNotEmpty()) sigmas.max().toDouble() else trainSigmas.max()
        return sqrt(m * m + 1).toFloat()
    }

    fun setTimesteps(n: Int) {
        require(n >= 1)
        val last = (trainSteps - 1).toDouble()
        val ts = DoubleArray(n) { if (n == 1) last else last * it / (n - 1) }.reversedArray()
        timesteps = FloatArray(n) { ts[it].toFloat() }
        sigmas = FloatArray(n + 1)
        for (i in 0 until n) sigmas[i] = interp(ts[i]).toFloat()
        sigmas[n] = 0f
    }

    private fun interp(t: Double): Double {
        val lo = t.toInt().coerceIn(0, trainSteps - 1)
        val hi = (lo + 1).coerceAtMost(trainSteps - 1)
        val f = t - lo
        return trainSigmas[lo] * (1 - f) + trainSigmas[hi] * f
    }

    fun scaleModelInput(x: FloatArray, i: Int): FloatArray {
        val s = sigmas[i]
        val d = sqrt(s * s + 1f)
        return FloatArray(x.size) { x[it] / d }
    }

    /** Euler step for epsilon prediction: x + eps * (σ_{i+1} − σ_i). */
    fun step(modelOut: FloatArray, i: Int, sample: FloatArray): FloatArray {
        require(modelOut.size == sample.size)
        val dt = sigmas[i + 1] - sigmas[i]
        return FloatArray(sample.size) { sample[it] + modelOut[it] * dt }
    }
}
