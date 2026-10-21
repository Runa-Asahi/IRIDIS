package com.iris.localai.imagegen

import kotlin.math.roundToInt

/**
 * Asymmetric uint16 quantization: q = clamp(round(x/scale) + zp, 0, 65535); x ≈ (q − zp) * scale.
 * Values are stored in ShortArray (reinterpret as unsigned: `s.toInt() and 0xFFFF`).
 * Note: QNN "offset" convention is real = (q + offset) * scale, i.e. zp = −offset.
 */
object Quant {
    const val QMAX = 65535

    fun quantize(x: Float, scale: Float, zp: Int): Int =
        ((x / scale).roundToInt() + zp).coerceIn(0, QMAX)

    fun dequantize(q: Int, scale: Float, zp: Int): Float = ((q and 0xFFFF) - zp) * scale

    fun quantize(xs: FloatArray, scale: Float, zp: Int): ShortArray =
        ShortArray(xs.size) { quantize(xs[it], scale, zp).toShort() }

    fun dequantize(qs: ShortArray, scale: Float, zp: Int): FloatArray =
        FloatArray(qs.size) { dequantize(qs[it].toInt() and 0xFFFF, scale, zp) }

    fun requantize(qs: ShortArray, s1: Float, z1: Int, s2: Float, z2: Int): ShortArray =
        ShortArray(qs.size) { quantize(dequantize(qs[it].toInt() and 0xFFFF, s1, z1), s2, z2).toShort() }

    fun toUInt(qs: ShortArray): IntArray = IntArray(qs.size) { qs[it].toInt() and 0xFFFF }
}
