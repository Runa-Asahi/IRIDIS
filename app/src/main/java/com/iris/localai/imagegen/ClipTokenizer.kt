package com.iris.localai.imagegen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * CLIP byte-level BPE tokenizer (SD1.5 text encoder). Pure Kotlin.
 * vocab: token -> id (vocab.json); merges: ordered list of "a b" pairs (merges.txt).
 */
class ClipTokenizer(
    private val vocab: Map<String, Int>,
    merges: List<Pair<String, String>>,
) {
    private val ranks: Map<Pair<String, String>, Int> = merges.withIndex().associate { it.value to it.index }
    private val cache = HashMap<String, List<String>>()

    companion object {
        const val BOS = 49406
        const val EOS = 49407
        const val PAD = EOS
        const val MAX_LEN = 77

        private val PATTERN = Regex(
            """<\|startoftext\|>|<\|endoftext\|>|'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]|[^\s\p{L}\p{N}]+""",
            RegexOption.IGNORE_CASE,
        )
        private val WS = Regex("""\s+""")

        /** GPT-2 / CLIP bytes_to_unicode: byte value -> printable char. */
        val BYTE_ENCODER: Array<Char> = run {
            val bs = ArrayList<Int>()
            bs.addAll('!'.code..'~'.code); bs.addAll(0xA1..0xAC); bs.addAll(0xAE..0xFF)
            val cs = ArrayList(bs)
            var n = 0
            for (b in 0 until 256) if (b !in bs) { bs.add(b); cs.add(256 + n); n++ }
            val arr = Array(256) { ' ' }
            for (i in bs.indices) arr[bs[i]] = cs[i].toChar()
            arr
        }

        fun parseVocab(json: String): Map<String, Int> =
            Json.parseToJsonElement(json).jsonObject.mapValues { it.value.jsonPrimitive.int }

        fun parseMerges(text: String): List<Pair<String, String>> =
            text.lineSequence()
                .filter { it.isNotBlank() && !it.startsWith("#version") }
                .mapNotNull { line ->
                    val p = line.trim().split(' ')
                    if (p.size == 2) p[0] to p[1] else null
                }.toList()

        fun fromText(vocabJson: String, mergesTxt: String) = ClipTokenizer(parseVocab(vocabJson), parseMerges(mergesTxt))

        fun clean(text: String): String = WS.replace(text, " ").trim().lowercase()
    }

    /** BPE of one byte-encoded word; returns sub-tokens (last carries "</w>"). */
    internal fun bpe(token: String): List<String> {
        cache[token]?.let { return it }
        if (token.isEmpty()) return emptyList()
        var word: MutableList<String> = token.map { it.toString() }.toMutableList()
        word[word.size - 1] = word.last() + "</w>"
        while (word.size > 1) {
            var best: Pair<String, String>? = null
            var bestRank = Int.MAX_VALUE
            for (i in 0 until word.size - 1) {
                val r = ranks[word[i] to word[i + 1]] ?: continue
                if (r < bestRank) { bestRank = r; best = word[i] to word[i + 1] }
            }
            val (a, b) = best ?: break
            val nw = ArrayList<String>(word.size)
            var i = 0
            while (i < word.size) {
                if (i < word.size - 1 && word[i] == a && word[i + 1] == b) { nw.add(a + b); i += 2 }
                else { nw.add(word[i]); i++ }
            }
            word = nw
        }
        cache[token] = word
        return word
    }

    /** Token ids of text without BOS/EOS. Unknown sub-tokens are skipped. */
    fun tokenize(text: String): List<Int> {
        val out = ArrayList<Int>()
        for (m in PATTERN.findAll(clean(text))) {
            val piece = m.value
            when (piece) {
                "<|startoftext|>" -> { out.add(BOS); continue }
                "<|endoftext|>" -> { out.add(EOS); continue }
            }
            val enc = buildString { for (b in piece.toByteArray(Charsets.UTF_8)) append(BYTE_ENCODER[b.toInt() and 0xFF]) }
            for (t in bpe(enc)) vocab[t]?.let { out.add(it) }
        }
        return out
    }

    /** BOS + ids + EOS, truncated (EOS kept) and padded with [PAD] to [maxLen]. */
    fun encode(text: String, maxLen: Int = MAX_LEN): IntArray {
        require(maxLen >= 2)
        val ids = tokenize(text).take(maxLen - 2)
        val arr = IntArray(maxLen) { PAD }
        arr[0] = BOS
        for (i in ids.indices) arr[i + 1] = ids[i]
        arr[ids.size + 1] = EOS
        return arr
    }
}
