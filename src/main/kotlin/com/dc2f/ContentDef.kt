package com.dc2f

import com.fasterxml.jackson.annotation.JacksonInject
import mu.KotlinLogging
import org.apache.commons.lang3.builder.ReflectionToStringBuilder
import java.lang.annotation.Inherited
import java.nio.file.*

private val logger = KotlinLogging.logger {}

@Target(AnnotationTarget.CLASS) @Inherited
annotation class Nestable(val identifier: String)
@Target(AnnotationTarget.CLASS)
annotation class PropertyType(val identifier: String)

interface ContentDef

interface RichText: ContentDef {
}

interface Parsable<T: ContentDef> {
    abstract fun parseContent(file: Path): T
}

@PropertyType("md")
class Markdown(private val content: String) : ContentDef {

    companion object : Parsable<Markdown> {
        override fun parseContent(file: Path): Markdown {
            return Markdown(Files.readAllLines(file).joinToString(System.lineSeparator()))
        }
    }

    override fun toString(): String {
        return ReflectionToStringBuilder(this).toString()
    }
}

//@JsonDeserialize(using = ChildrenDeserializer::class)
//class Children<T: ContentDef>(val children: List<T>)

//class ChildrenDeserializer: JsonDeserializer<Children<*>?>() {
//    override fun deserialize(p: JsonParser?, ctxt: DeserializationContext?): Children<*>? {
//        logger.debug { "Deserializing stuff." }
//        val res = ctxt?.findInjectableValue("children", null, null)
//        if (res is Children<*>) {
//            return res
//        }
//        return null
//    }
//
//    override fun getNullValue(ctxt: DeserializationContext?): Children<*>? {
//        logger.debug { "need to get null value." }
//        return super.getNullValue(ctxt)
//    }
//
//}

interface ContentBranchDef<CHILD_TYPE: ContentDef> : ContentDef {
    @get:JacksonInject("children") @set:JacksonInject("children")
    var children: List<CHILD_TYPE>

}

interface Website<CHILD_TYPE: ContentDef> : ContentBranchDef<CHILD_TYPE> {
    val name: String
}


