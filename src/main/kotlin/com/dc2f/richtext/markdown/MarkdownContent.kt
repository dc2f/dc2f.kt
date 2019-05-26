package com.dc2f.richtext.markdown

import com.dc2f.*
import com.dc2f.render.RenderContext
import com.dc2f.richtext.*
import com.dc2f.util.*
import com.vladsch.flexmark.ext.typographic.TypographicExtension
import com.vladsch.flexmark.ext.xwiki.macros.*
import com.vladsch.flexmark.html.*
import com.vladsch.flexmark.html.renderer.*
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.ast.*
import com.vladsch.flexmark.util.html.Attributes
import com.vladsch.flexmark.util.options.*
import jodd.bean.BeanUtil
import mu.KotlinLogging
import org.springframework.expression.spel.standard.SpelExpressionParser
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
val PARENT = DataKey<ContentDef?>("PARENT", null as ContentDef?)
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
            if (link.url.startsWith('@') || (!link.url.contains("://") && !link.url.contains("@"))) {
                // validate internal link.
                val loaderContext = requireNotNull(context.options[LOADER_CONTEXT])
                val renderContext = context.options[RENDER_CONTEXT]
                val parent = context.options[PARENT]

                if (link.url.startsWith('@')) {
                    if (renderContext == null) {
                        require(loaderContext.phase == LoaderContext.LoaderPhase.Validating)
                        return link
                    }
                    val obj = BeanUtil.pojo.getProperty<Any>(RichTextContext(renderContext.node, loaderContext, renderContext, null), link.url.substring(1))
                    return link.withUrl(RichText.render(obj, renderContext)).withLinkType(LinkType.LINK).withStatus(
                        LinkStatus.VALID)
                }

                val parentContentPath = parent?.let { loaderContext.findContentPath(it) }

                val linkedContent = loaderContext.contentByPath[parentContentPath?.resolve(link.url) ?: ContentPath.parse(link.url)]
                    ?: throw ValidationException("Invalid link: ${link.toStringReflective()}")

                return renderContext?.let { renderContext ->
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
//            return link
            throw ValidationException("Invalid link (malformed URL): ${link.toStringReflective()}, ${e.message}", e)
        }
        logger.debug { "We need to resolve link ${link.toStringReflective()} for $node" }
        // absolute links are kept as they are...
        return link
    }

}


/*
TODO the markdown renderer has quite a few problems
 - {{ render /}} won't render at all (space after {{)
 - {{ render content=test-test }} .. no '-' allowed in attribute values? (maybe makes sense?)
 - generally it does not fail for broken macros.
 - it should be easier to reference content.
 */
class MarkdownMacroRenderer(options: DataHolder) : NodeRenderer {

    companion object {
        val expressionParser by lazy { SpelExpressionParser() }
    }

    fun render(param: Macro, nodeRendererContext: NodeRendererContext, html: HtmlWriter) {
        logger.debug { "Rendering ${param.name} with: ${param.attributeText}" }

        @Suppress("ReplaceCallWithBinaryOperator")
        if (!param.name.equals("render")) {
            logger.debug { "Unsupported macro ${param.name}" }
            throw IllegalArgumentException("Unsupported macro in markdown context. ${param.name}")
        }

        val loaderContext = nodeRendererContext.options[LOADER_CONTEXT]

        if (!loaderContext.phase.isAfter(LoaderContext.LoaderPhase.Validating)) {
            // we are in validation step. do not do anything yet.
            // TODO validate content.
            return
        }

        val renderContext = nodeRendererContext.options[RENDER_CONTEXT]
        val context = RichTextContext(renderContext.node, renderContext.renderer.loaderContext, renderContext, null)
        val contentPath = param.attributes["content"]
        val arguments = param.attributes["arguments"]?.let { str ->
            val expr = expressionParser.parseExpression(str)
            expr.getValue(context)
        }

        val result: Any = BeanUtil.pojo.getProperty(context, contentPath)
        html.rawPre(RichText.render(result, renderContext, arguments))
    }

    override fun getNodeRenderingHandlers(): MutableSet<NodeRenderingHandler<*>> =
        mutableSetOf(
            NodeRenderingHandler<Macro>(Macro::class.java, ::render),
            NodeRenderingHandler<MacroBlock>(MacroBlock::class.java) { param: MacroBlock, nodeRendererContext: NodeRendererContext, htmlWriter: HtmlWriter ->
                if (!param.isClosedTag) {
                    throw IllegalArgumentException("Tag must be cloesd. Got: ${param.chars}")
                }
                if (!param.macroContentChars.isNullOrBlank()) {
                    throw IllegalArgumentException("Tag content must be empty, because it is ignored. contains: ${param.chars}")
                }
                render(param.macroNode, nodeRendererContext, htmlWriter)
            }
        )

}

@PropertyType("md")
class Markdown(private val content: String) : ParsableContentDef, RichText, ValidationRequired {

    companion object : Parsable<Markdown> {
        override fun parseContent(
            context: LoaderContext,
            file: Path,
            contentPath: ContentPath
        ): Markdown =
            parseContentString(file.readString())

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

    val rawContent get() = content
    override fun rawContent(): String = content

    private fun parsedContent(context: LoaderContext, content: String = this.content): Document {
        require(context.phase.isAfter(LoaderContext.LoaderPhase.Loading))
        return parser.parse(content)
    }

    private fun renderer(renderContext: RenderContext<*>?, loaderContext: LoaderContext, asInlineContent: Boolean = false) =
        HtmlRenderer.builder(
            MutableDataSet(options)
                .set(LOADER_CONTEXT, loaderContext)
                .set(PARENT, renderContext?.node)
                .set(RENDER_CONTEXT, renderContext)
                .set(HtmlRenderer.NO_P_TAGS_USE_BR, asInlineContent)
        ).build()

    fun renderedContent(context: RenderContext<*>, content: String = this.content, asInlineContent: Boolean = false): String {
        val doc = parsedContent(context.renderer.loaderContext, content = content)

        return renderer(
            context,
            context.renderer.loaderContext,
            asInlineContent = asInlineContent
        ).render(doc)
            .let { html ->
                if (asInlineContent) {
                    html.trim().let {
                        require(it.endsWith("<br /><br />"))
                        it.substring(0, it.length - "<br /><br />".length)
                    }
                } else {
                    html
                }
            }
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
                MutableDataSet(options)
                    .set(LOADER_CONTEXT, loaderContext)
                    .set(PARENT, parent.content)
            ).build()
                .render(parsedContent(loaderContext))
            null
        } catch (e: ValidationException) {
            e.message
        } catch (e: IllegalArgumentException) {
            logger.error(e) { "Error while parsing content." }
            e.message
        }
    }
}

