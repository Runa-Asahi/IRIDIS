package com.iris.localai.imagegen

import kotlin.random.Random

data class ImageRequest(
    val prompt: String,
    val negative: String = "",
    val width: Int = 512,
    val height: Int = 512,
    val steps: Int = 20,
    val cfg: Float = 7.5f,
    val seed: Long = -1,
    val initImagePath: String? = null,
    val strength: Float = 0.75f,
) {
    /** Negative prompt actually used: ignored without classifier-free guidance. */
    val effectiveNegative: String get() = if (cfg <= 1f) "" else negative

    fun normalize(model: ImageModel, random: Random = Random.Default): ImageRequest {
        fun dim(v: Int): Int = model.fixedSize ?: (v.coerceIn(MIN_DIM, MAX_DIM) / 16 * 16)
        return copy(
            width = dim(width),
            height = dim(height),
            steps = steps.coerceIn(1, 50),
            cfg = if (cfg.isNaN()) model.defaultCfg else cfg.coerceIn(1f, 20f),
            strength = if (strength.isNaN()) 0.75f else strength.coerceIn(0f, 1f),
            seed = if (seed < 0) random.nextLong(0, Long.MAX_VALUE) else seed,
        )
    }

    companion object {
        const val MIN_DIM = 256
        const val MAX_DIM = 1024
    }
}
