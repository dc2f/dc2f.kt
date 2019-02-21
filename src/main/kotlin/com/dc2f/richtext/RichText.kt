package com.dc2f.richtext

import com.dc2f.*
import com.dc2f.render.RenderContext
import com.dc2f.util.toStringReflective

interface RichText: ContentDef, Renderable {
    companion object {
        /**
         * Help method for rich text elements which might need to render resolved objects.
         */
        fun render(content: Any?, renderContext: RenderContext<*>): String =
            when (content) {
                is Renderable -> content.renderContent(renderContext)
                is ContentReference -> render(content.referencedContent, renderContext)
                is ContentDef -> renderContext.renderNode(content)
                is String -> content
                else -> throw IllegalArgumentException("Unable to render ${content?.toStringReflective()} (unknown type)")
            }
    }
}

@Suppress("unused")
data class RichTextContext(
    val node: ContentDef,
    val loaderContext: LoaderContext,
    val renderContext: RenderContext<*>?,
    val arguments: Any?
) {
    val rootNode get() = loaderContext.rootNode

    fun asMap() =
        mapOf(
            "node" to node,
            "renderContext" to renderContext,
            "arguments" to arguments
            )
}
