package com.dc2f.util

import com.dc2f.*
import org.apache.commons.lang3.builder.*
import org.apache.commons.text.StringEscapeUtils
import java.nio.file.Path

private val toStringStyle = object : MultilineRecursiveToStringStyle() {
    init {
        isUseShortClassName = true
        isUseIdentityHashCode = false
    }

    override fun appendDetail(buffer: StringBuffer?, fieldName: String?, value: Any?) {
        if (value is Path) {
            buffer?.append("Path:")?.append(StringEscapeUtils.escapeJson(value.toString()))
        } else if (value is String) {
            buffer?.append(StringEscapeUtils.escapeJson(value))
        } else {
            super.appendDetail(buffer, fieldName, value)
        }
    }

    override fun accept(clazz: Class<*>?): Boolean {
        return !setOf(ContentPath::class.java, LoaderContext::class.java)
            .contains(clazz)
    }
}

fun Any.toStringReflective(): String =
    ReflectionToStringBuilder.toString(
        this,
        toStringStyle
    )

