package com.dc2f.util

import org.junit.jupiter.api.*
import org.junit.jupiter.api.DynamicTest.dynamicTest
import kotlin.test.assertEquals


internal class SlugifyTest {

    private val slugTests = listOf(
        "Hello World" to "hello-world",
        "Umläute" to "umlaeute",
        "!!trim!" to "trim",
        "file.ext" to "file.ext"
    )

    @TestFactory
    fun testSimpleTransform() = slugTests.map {
        dynamicTest("${it.first} = ${it.second}") {
            assertEquals(it.second, Slugify().slugify(it.first))
        }
    }
}