package com.dc2f.util

import java.nio.file.*

fun Path.readString() = String(Files.readAllBytes(this))
