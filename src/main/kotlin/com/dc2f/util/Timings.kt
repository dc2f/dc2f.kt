package com.dc2f.util

typealias Milliseconds = Long

class Timing(val label: String) {

    var duration: Milliseconds = 0

    companion object {
        val allTimings = mutableSetOf<Timing>()
    }

    init {
        allTimings.add(this)
    }

    inline fun <T> measure(block: () -> T): T {
        val start = System.currentTimeMillis()
        try {
            return block()
        } finally {
            duration = System.currentTimeMillis() - start
        }
    }

    override fun toString(): String = "$label: ${duration}ms"
}
