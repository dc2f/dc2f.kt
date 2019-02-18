package com.dc2f.util

import com.ibm.icu.text.*

/**
 * Very much based on: https://github.com/slugify/slugify/blob/master/core/src/main/java/com/github/slugify/Slugify.java
 */
class Slugify(
    private val disallowedCharacters: Regex = Regex("[^A-Za-z0-9.]+")
) {

    companion object {
        private const val ASCII =
            "de-ASCII; Cyrillic-Latin; Any-Latin; Latin-ASCII; [^\\p{Print}] Remove; ['\"] Remove; Any-Lower"
    }


    private val transliterate = Transliterator.getInstance(ASCII)

    fun slugify(text: String): String {
        return (text
            .trim()
            .transliterate()
            .hyphenate()
            .trim('-')
            )

    }

    private fun String.transliterate() = transliterate.transliterate(this)
    private fun String.hyphenate() = this.replace(disallowedCharacters, "-")

}