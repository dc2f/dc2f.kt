package com.dc2f.util

import com.dc2f.*
import org.apache.commons.lang3.builder.*
import org.apache.commons.text.StringEscapeUtils
import java.nio.file.Path
import kotlin.reflect.full.*
import kotlin.reflect.jvm.isAccessible

private class ToStringStyle : MultilineRecursiveToStringStyle() {

    companion object {
        const val INFINITE_DEPTH = 0
    }

    var maxDepth = INFINITE_DEPTH

    init {
        isUseShortClassName = true
        isUseIdentityHashCode = false
    }

    private val spacesAccessor by lazy {
        MultilineRecursiveToStringStyle::class.declaredMemberProperties.single { it.name == "spaces" }.also {
            it.isAccessible = true
        }
    }

    private val spaces get() = spacesAccessor.get(this) as Int

    override fun appendDetail(buffer: StringBuffer?, fieldName: String?, value: Any?) {
        if (value is Path) {
            buffer?.append("Path:")?.append(StringEscapeUtils.escapeJson(value.toString()))
        } else if (value is CharSequence) {
            buffer?.append(StringEscapeUtils.escapeJson(value.toString()))
        } else {
            super.appendDetail(buffer, fieldName, value)
        }
    }

    override fun accept(clazz: Class<*>?): Boolean {
        if (maxDepth != INFINITE_DEPTH && (spaces/2) > maxDepth) {
            return false
        }
        return !setOf(ContentPath::class.java, LoaderContext::class.java)
            .contains(clazz)
    }
}

fun Any.toStringReflective(maxDepth: Int = ToStringStyle.INFINITE_DEPTH): String =
    ReflectionToStringBuilder.toString(
        this,
        ToStringStyle().also { it.maxDepth = maxDepth }
    )

