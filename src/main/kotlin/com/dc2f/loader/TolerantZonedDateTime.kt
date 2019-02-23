package com.dc2f.loader

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import mu.KotlinLogging
import java.time.*
import java.time.format.*
import java.time.temporal.*

private val logger = KotlinLogging.logger {}

class TolerantZonedDateTime : StdDeserializer<ZonedDateTime>(ZonedDateTime::class.java) {

    companion object {
        val parser: DateTimeFormatter by lazy {
            DateTimeFormatterBuilder()
                .append(DateTimeFormatter.ISO_LOCAL_DATE)
                .optionalStart()
                .appendLiteral(' ')
                .appendValue(ChronoField.HOUR_OF_DAY, 2)
                .appendLiteral(':')
                .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
                .optionalStart()
                .appendLiteral(':')
                .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
                .optionalEnd()
                .optionalStart()
                .appendLiteral(' ')
                .appendZoneOrOffsetId()
                .optionalEnd()
                .optionalEnd()
                .parseDefaulting(ChronoField.HOUR_OF_DAY, 10)
                .toFormatter()
                // default zone
                .withZone(ZoneId.systemDefault())
        }
    }

    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): ZonedDateTime {
        val text = p.text.trim()
        try {
            val parsed = parser.parse(text.replace('T', ' '))
            logger.trace { "Parsed date to $parsed" }
            return ZonedDateTime.from(parsed)
        } catch (e: DateTimeParseException) {
            val ex = ctxt.weirdStringException(text, handledType(), e.message)
            ex.initCause(e)
            throw ex
        }
    }

}
