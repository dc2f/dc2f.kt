package com.dc2f.util

import com.ibm.icu.text.*

/**
 * Very much based on: https://github.com/slugify/slugify/blob/master/core/src/main/java/com/github/slugify/Slugify.java
 */
class Slugify(
    val disallowedCharacters: Regex = Regex("[^A-Za-z0-9.]+")
) {

//    private val ASCII =
//        "de-ASCII; Any-Latin; Latin-ASCII; [\\u0080-\\u7fff] remove"

    private val ASCII = "de-ASCII; Cyrillic-Latin; Any-Latin; Latin-ASCII; [^\\p{Print}] Remove; ['\"] Remove; Any-Lower"

    val normalizer = Normalizer2.getNFKDInstance()
    val transliterate = Transliterator.getInstance(ASCII)

    fun slugify(text: String): String {
        return (text
            .trim()
//            .normalize()
            .transliterate()
            .hyphenate()
//            .toLowerCase()
            .trim('-')
            )

//        normalizer.normalize(text)
    }

    private fun String.normalize() = normalizer.normalize(this)
    private fun String.transliterate() = transliterate.transliterate(this)
//    private fun lowerCase(text: String) = text.toLowerCase()
    private fun String.hyphenate() = this.replace(disallowedCharacters, "-")

    inline infix fun String.pipe(transform: (text: String) -> String) =
        transform(this)
}