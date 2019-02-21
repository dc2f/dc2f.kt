package com.dc2f.util

fun <T> Boolean.then(block: () -> T): T? =
    if (this) { block() } else { null }
