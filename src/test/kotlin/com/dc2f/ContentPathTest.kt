package com.dc2f

import assertk.assertThat
import assertk.assertions.*
import org.junit.Test
import org.junit.jupiter.api.Assertions.*

internal class ContentPathTest {

    val hello = ContentPath.root.child("hello")
    val helloWorld = hello.child("world")
    val helloWorldOther = helloWorld.child("other")

    val foo = ContentPath.root.child("foo")
    val fooBar = foo.child("bar")

    @Test
    fun testDistances() {
        assertNull(hello.subPathDistance(foo))
        assertThat(helloWorld.subPathDistance(hello))
            .isNotNull()
            .isGreaterThan(0)
        assertThat(hello.subPathDistance(hello))
            .isNotNull()
            .isEqualTo(0)
        assertThat(helloWorld.subPathDistance(hello))
            .isNotNull()
            .given {  helloWorldDistance ->
                assertThat(helloWorldOther.subPathDistance(hello))
                    .isNotNull()
                    .isGreaterThan(helloWorldDistance)
            }

    }

}