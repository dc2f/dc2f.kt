package com.dc2f.util

/**
 * Returns a substring before the first occurrence of [delimiter].
 * If the string does not contain the delimiter, returns [missingDelimiterValue] which defaults to the original string.
 */
public fun String.substringBefore(delimiter: String, missingDelimiterValueProducer: () -> String): String {
    val index = indexOf(delimiter)
    return if (index == -1) missingDelimiterValueProducer() else substring(0, index)
}
