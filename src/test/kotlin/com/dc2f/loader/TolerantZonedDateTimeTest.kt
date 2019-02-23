package com.dc2f.loader

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.mrbean.MrBeanModule
import org.junit.Test
import java.time.ZonedDateTime
import java.time.temporal.*

internal class TolerantZonedDateTimeTest {

    private val objectMapper : ObjectMapper = ObjectMapper()
        .registerModule(MrBeanModule())
        .registerModule(SimpleModule().apply {
        addDeserializer(ZonedDateTime::class.java, TolerantZonedDateTime())
    })

    interface SimpleJson {
        val date: ZonedDateTime
    }

    private fun runFormat(string: String): ZonedDateTime {
        val json = objectMapper.writeValueAsString(mapOf("date" to string))
        return objectMapper.readValue(json, SimpleJson::class.java).date
    }

    @Test
    fun testOptionalValues() {
        assertThat(runFormat("2018-01-20")).given {
            assertThat(it.dayOfMonth).isEqualTo(20)
            assertThat(it.hour).isEqualTo(10)
        }
    }

    @Test
    fun testWithTime() {
        assertThat(runFormat("2018-01-20 13:00")).given {
            assertThat(it.dayOfMonth).isEqualTo(20)
            assertThat(it.hour).isEqualTo(13)
        }
    }

    @Test
    fun testWithZoneId() {
        // standard time, CET
        assertThat(
            runFormat("2018-01-20 12:00:00 Europe/Vienna")
        ).given {
            assertThat(it.hour).isEqualTo(12)
            assertThat(it.offset.totalSeconds).isEqualTo(1 * 3600)
        }

        // summer saving time CEST
        assertThat(
            runFormat("2018-07-20 12:00 Europe/Vienna")
        ).given {
            assertThat(it.hour).isEqualTo(12)
            assertThat(it.offset.totalSeconds).isEqualTo(2 * 3600)
        }
    }

}