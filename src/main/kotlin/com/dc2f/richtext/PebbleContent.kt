package com.dc2f.richtext

import com.dc2f.*
import com.dc2f.render.RenderContext
import com.dc2f.util.readString
import com.mitchellbosecke.pebble.PebbleEngine
import java.io.StringWriter
import java.nio.file.Path

data class PebbleContext(
    val node: ContentDef,
    val renderContext: RenderContext<*>,
    val arguments: Any?
) : HashMap<String, Any?>() {
    init {
        putAll(arrayOf("node" to node, "renderContext" to renderContext, "arguments" to arguments))
    }
}

@PropertyType("peb")
class Pebble(
    val content: String,
    val path: Path,
    private val contentPath: ContentPath
) : RichText {

    companion object : Parsable<Pebble> {

        val engine: PebbleEngine by lazy {
            PebbleEngine.Builder()
                .strictVariables(true)
                .cacheActive(false)
                .build()
        }

        override fun parseContent(
            context: LoaderContext,
            file: Path,
            contentPath: ContentPath
        ): Pebble =
            Pebble(file.readString(), file, contentPath)
    }

    fun renderContent(renderContext: RenderContext<*>, arguments: Any? = null): String {
        val template = engine.getLiteralTemplate(content)
        val output = StringWriter()
        val node = requireNotNull(renderContext.renderer.loaderContext.contentByPath[contentPath.parent()])
        template.evaluate(output, PebbleContext(node, renderContext, arguments))
        return output.toString()
    }

}
