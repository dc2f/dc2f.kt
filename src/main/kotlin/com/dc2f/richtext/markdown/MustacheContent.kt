package com.dc2f.richtext.markdown

import com.dc2f.*
import com.dc2f.render.RenderContext
import com.dc2f.util.readString
import com.github.mustachejava.*
import java.io.StringWriter
import java.nio.file.Path

data class MustacheScope(val node: ContentDef, val renderContext: RenderContext<*>)

@PropertyType("mustache")
class Mustache(
    val content: String,
    val path: Path,
    private val contentPath: ContentPath
) : RichText {

    companion object : Parsable<Mustache> {

        override fun parseContent(
            context: LoaderContext,
            file: Path,
            contentPath: ContentPath
        ): Mustache =
            Mustache(file.readString(), file, contentPath)
    }

    fun renderContent(renderContext: RenderContext<*>): String {
        val mustache = DefaultMustacheFactory().compile(content.reader(), path.toString())
        val output = StringWriter()
        val node = requireNotNull(renderContext.renderer.loaderContext.contentByPath[contentPath.parent()])
        mustache.execute(output, MustacheScope(node, renderContext))
        return output.toString()
    }

}