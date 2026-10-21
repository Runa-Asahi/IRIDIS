package com.iris.localai.imagegen

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/** Embed generation parameters as a PNG text chunk (A1111-style "parameters"). */
object PngParams {
    val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    const val KEY = "parameters"

    /**
     * Inserts a text chunk right after IHDR. Uses tEXt when [text] is Latin-1 encodable,
     * otherwise iTXt (UTF-8) so Chinese prompts survive.
     */
    fun insert(png: ByteArray, text: String, keyword: String = KEY): ByteArray {
        require(png.size >= 33 && png.copyOfRange(0, 8).contentEquals(SIGNATURE)) { "not a PNG" }
        val ihdrLen = readInt(png, 8)
        require(String(png, 12, 4, Charsets.ISO_8859_1) == "IHDR") { "IHDR must be first" }
        val insertAt = 8 + 12 + ihdrLen
        val latin1 = Charsets.ISO_8859_1.newEncoder().canEncode(text)
        val data = ByteArrayOutputStream().apply {
            write(keyword.toByteArray(Charsets.ISO_8859_1)); write(0)
            if (latin1) write(text.toByteArray(Charsets.ISO_8859_1))
            else { write(0); write(0); write(0); write(0); write(text.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        val chunk = chunk(if (latin1) "tEXt" else "iTXt", data)
        return png.copyOfRange(0, insertAt) + chunk + png.copyOfRange(insertAt, png.size)
    }

    fun chunk(type: String, data: ByteArray): ByteArray {
        val t = type.toByteArray(Charsets.ISO_8859_1)
        val crc = CRC32().apply { update(t); update(data) }.value
        val out = ByteArrayOutputStream()
        out.write(intBytes(data.size)); out.write(t); out.write(data); out.write(intBytes(crc.toInt()))
        return out.toByteArray()
    }

    data class Chunk(val type: String, val data: ByteArray, val crcOk: Boolean)

    fun chunks(png: ByteArray): List<Chunk> {
        val list = ArrayList<Chunk>()
        var p = 8
        while (p + 12 <= png.size) {
            val len = readInt(png, p)
            val type = String(png, p + 4, 4, Charsets.ISO_8859_1)
            val data = png.copyOfRange(p + 8, p + 8 + len)
            val crc = readInt(png, p + 8 + len).toLong() and 0xFFFFFFFFL
            val calc = CRC32().apply { update(png, p + 4, 4 + len) }.value
            list.add(Chunk(type, data, crc == calc))
            p += 12 + len
            if (type == "IEND") break
        }
        return list
    }

    /** Reads the text for [keyword] from tEXt/iTXt (uncompressed) chunks, or null. */
    fun read(png: ByteArray, keyword: String = KEY): String? {
        for (c in chunks(png)) {
            val nul = c.data.indexOf(0)
            if (nul < 0 || String(c.data, 0, nul, Charsets.ISO_8859_1) != keyword) continue
            when (c.type) {
                "tEXt" -> return String(c.data, nul + 1, c.data.size - nul - 1, Charsets.ISO_8859_1)
                "iTXt" -> {
                    var q = nul + 3 // compression flag, method
                    q = c.data.indexOf(0, q) + 1 // language tag
                    q = c.data.indexOf(0, q) + 1 // translated keyword
                    return String(c.data, q, c.data.size - q, Charsets.UTF_8)
                }
            }
        }
        return null
    }

    private fun ByteArray.indexOf(b: Byte, from: Int = 0): Int {
        for (i in from until size) if (this[i] == b) return i
        return -1
    }

    private fun readInt(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun intBytes(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
}
