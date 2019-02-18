package com.dc2f.richtext.markdown

import com.dc2f.*
import com.dc2f.render.RenderContext
import com.dc2f.util.*
import com.vladsch.flexmark.ext.typographic.TypographicExtension
import com.vladsch.flexmark.ext.xwiki.macros.*
import com.vladsch.flexmark.html.*
import com.vladsch.flexmark.html.renderer.*
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.ast.*
import com.vladsch.flexmark.util.html.Attributes
import com.vladsch.flexmark.util.options.*
import mu.KotlinLogging
import java.nio.file.*

private val logger = KotlinLogging.logger {}

object MarkdownDc2fExtension : HtmlRenderer.HtmlRendererExtension {
    override fun extend(rendererBuilder: HtmlRenderer.Builder, rendererType: String?) {
        rendererBuilder.nodeRendererFactory { options -> MarkdownMacroRenderer(options) }
        rendererBuilder.linkResolverFactory(object : IndependentLinkResolverFactory() {
            override fun create(context: LinkResolverContext): LinkResolver =
                Dc2fLinkResolver(context)
        })
    }

    override fun rendererOptions(options: MutableDataHolder?) {
    }

}

interface ValidationRequired {
    fun validate(loaderContext: LoaderContext, parent: LoadedContent<*>): String?
}

typealias ValidationRequiredLambda = (context: LoaderContext) -> String

val VALIDATORS = DataKey<MutableList<ValidationRequiredLambda>>("VALIDATORS") { mutableListOf() }
val LOADER_CONTEXT = DataKey<LoaderContext>("LOADER_CONTEXT", null as LoaderContext?)
val RENDER_CONTEXT = DataKey<RenderContext<*>>("RENDER_CONTEXT", null as RenderContext<*>?)

class ValidationException(message: String, cause: Throwable? = null) : Exception(message, cause)

class Dc2fLinkResolver(val context: LinkResolverContext): LinkResolver {
    override fun resolveLink(
        node: Node?,
        context: LinkResolverContext,
        link: ResolvedLink
    ): ResolvedLink {
//        context.document?.get(VALIDATORS)?.add { context ->
////            logger.info { "Validating stuff." }
////            ""
////        }
        if (link.url == null) {
            logger.warn { "Found a link with null url?! ${link.toStringReflective()}" }
            return link
        }
        try {
            if (!link.url.contains("://") && !link.url.contains("@")) {
                // validate internal link.
                val loaderContext = requireNotNull(context.options[LOADER_CONTEXT])
                val linkedContent = loaderContext.contentByPath[ContentPath.parse(link.url)]
                    ?: throw ValidationException("Invalid link: ${link.toStringReflective()}")

                return context.options[RENDER_CONTEXT]?.let { renderContext ->
                    val l = link.withStatus(LinkStatus.VALID)
                        .withUrl(renderContext.href(linkedContent))
                        .withTitle(renderContext.theme.renderLinkTitle(linkedContent))
                    // crazy workaround to get the "title" attribute into the rendered output.
                    // (CoreNodeRenderer#858 / render(Link node, NodeRendererContext context, HtmlWriter html))
                    object : ResolvedLink(l.linkType, l.url, l.attributes, l.status) {
                        override fun getNonNullAttributes(): Attributes {
                            return Attributes(l.attributes)
                        }

                        override fun getAttributes(): Attributes {
                            return nonNullAttributes
                        }
                    }
                } ?: link
            }
        } catch (e: ValidationException) {
            logger.error(e) { "temporarily disabled link errors." }
            return link
//            throw ValidationException("Invalid link (malformed URL): ${link.toStringReflective()}, ${e.message}", e)
        }
        logger.debug { "We need to resolve link ${link.toStringReflective()} for $node" }
        return link.withStatus(LinkStatus.INVALID).withUrl("SomethingElse")
    }

}


class MarkdownMacroRenderer(options: DataHolder) : NodeRenderer {
    override fun getNodeRenderingHandlers(): MutableSet<NodeRenderingHandler<*>> =
        mutableSetOf(
            NodeRenderingHandler<Macro>(Macro::class.java) { param, nodeRendererContext: NodeRendererContext, html: HtmlWriter ->
                println("Rendering $param")
                html.text("Lorem ips<a >um.")
            }
        )

}

@PropertyType("md")
class Markdown(private val content: String) : ContentDef, RichText, ValidationRequired {

    companion object : Parsable<Markdown> {
        override fun parseContent(
            context: LoaderContext,
            file: Path,
            contentPath: ContentPath
        ): Markdown =
            parseContentString(Files.readAllLines(file).joinToString(System.lineSeparator()))

        fun parseContentString(str: String): Markdown {
            return Markdown(str)
        }

        val options by lazy {
            MutableDataSet()
//                .set(MacroExtension.ENABLE_RENDERING, true)
                .set(MacroExtension.ENABLE_INLINE_MACROS, true)
                .set(MacroExtension.ENABLE_BLOCK_MACROS, true)
                .set(Parser.EXTENSIONS, listOf(
                    MacroExtension.create(),
                    MarkdownDc2fExtension,
                    TypographicExtension.create()
                    ))
        }

        val parser: Parser by lazy {
            Parser.builder(
                options
            ).build()
        }
    }

    private fun parsedContent(context: LoaderContext, content: String = this.content): Document {
        require(context.phase.isAfter(LoaderContext.LoaderPhase.Loading))
        return parser.parse(content)
    }

    private fun renderer(renderContext: RenderContext<*>?, loaderContext: LoaderContext) =
        HtmlRenderer.builder(
            options
                .set(LOADER_CONTEXT, loaderContext)
                .set(RENDER_CONTEXT, renderContext)
        ).build()

    fun renderedContent(context: RenderContext<*>, content: String = this.content): String {
        val doc = parsedContent(context.renderer.loaderContext, content = content)
        return renderer(context, context.renderer.loaderContext).render(doc)
    }

    override fun renderContent(renderContext: RenderContext<*>, arguments: Any?): String =
        renderedContent(renderContext, this.content)

    fun summary(context: RenderContext<*>): String {
        val summarySource = content.substringBefore("<!--more-->") {
            content.split(Regex("""\r\n\r\n|\n\n|\r\r"""), 2).first()
        }
        return renderedContent(context, content = summarySource)
    }


    override fun validate(loaderContext: LoaderContext, parent: LoadedContent<*>): String? {
        return try {
            HtmlRenderer.builder(
                options.set(LOADER_CONTEXT, loaderContext)
            ).build()
                .render(parsedContent(loaderContext))
            null
        } catch (e: ValidationException) {
            e.message
        }
    }
}

