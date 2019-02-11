package com.dc2f

import com.dc2f.render.RenderContext
import com.dc2f.util.*
import com.fasterxml.jackson.annotation.JacksonInject
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import mu.KotlinLogging
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

        val parser: Parser by lazy {
            Parser.builder().build()
        }
    }

    override fun toString(): String {
        val htmlRenderer = HtmlRenderer.builder().build()
        val document = parser.parse(this.content)
        return htmlRenderer.render(document)
//        return ReflectionToStringBuilder(this).toString()
    }
}

open class FileAsset(val file: ContentPath, val fsPath: Path) {
    val name: String get() = fsPath.fileName.toString()

    fun href(context: RenderContext<*>): String {
        val targetPath = context.rootPath.resolve(file.toString())
        Files.createDirectories(targetPath.parent)
        Files.copy(fsPath, targetPath)
        return "/$file"
    }
}

open class ImageAsset(file: ContentPath, fsPath: Path) : FileAsset(file, fsPath) {
    val imageInfo: ImageInfo by lazy { parseImage() }
    val width get() = imageInfo.width
    val height get() = imageInfo.height

    fun parseImage() =
        ImageUtil.readImageData(fsPath) ?: throw IllegalArgumentException("Invalid image at $fsPath")
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


