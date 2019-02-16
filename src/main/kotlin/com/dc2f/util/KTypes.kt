package com.dc2f.util

import kotlin.reflect.KType

val KType.isJavaType: Boolean
    // veeeery hackish.. we ignore java-types and only care about kotlin types.
    // There must be some better way to figure it out, but i haven't found it yet.
    get() = toString().endsWith('!')

