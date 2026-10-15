package com.wildcreator.sonorus.domain.agent

/** Conservative, tokenizer-independent fallback used when a provider gives no usage. */
object TokenEstimator {
    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var ascii = 0
        var other = 0
        text.forEach { ch ->
            when {
                ch.code in 0x2E80..0x9FFF || ch.code in 0xAC00..0xD7AF -> cjk++
                ch.code < 128 -> ascii++
                else -> other++
            }
        }
        return cjk + other + ((ascii + 3) / 4)
    }
}
