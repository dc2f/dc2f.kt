package com.dc2f.richtext.markdown

import com.dc2f.*
import com.dc2f.render.RenderContext
import com.vladsch.flexmark.html.HtmlRenderer
import io.mockk.*
import kotlin.test.*


class StringConstantRenderable(val content: String) : Renderable {
    override fun renderContent(renderContext: RenderContext<*>, arguments: Any?): String =
        content
}

@Suppress("unused")
class MockContent(val foobar: Any) : ContentDef

/** Stands in for the root node, which is reached as `rootNode.embed.…`. */
@Suppress("unused")
class MockRoot(val embed: Any) : ContentDef

class MarkdownTest {

    // TODO this is just fucked up. we should make those whole context stuff more easily testable.
    private val renderContext = mockRenderContext(LoaderContext.LoaderPhase.Finished)

    private fun mockRenderContext(phase: LoaderContext.LoaderPhase) = mockk<RenderContext<ContentDef>>(relaxed = true) {
        every { renderer.loaderContext.phase } returns phase
        every { renderer.loaderContext.contentByPath[any()] } returns mockk()
    }

    private fun markdown(source: String) =
        Markdown.parseContentString(source)
            .apply {
                validate(
                    mockRenderContext(LoaderContext.LoaderPhase.Validating).renderer.loaderContext,
                    // relaxed: under -Xjvm-default=all the interface members
                    // validate() reaches compile to real default methods, so
                    // this mock now receives calls (getContent()) that a strict
                    // mock rejects. Matches mockRenderContext above.
                    mockk<LoadedContent<ContentDef>>(relaxed = true))
            }

    private fun assertMarkdown(expected: String, source: String, asInlineContent: Boolean = false) =
        assertEquals(expected, markdown(source).renderedContent(renderContext, asInlineContent = asInlineContent).trim())

    @Test
    fun simpleTest() {
        assertMarkdown(
            "<p>Lorem ipsum <strong>bold</strong></p>",
            "Lorem ipsum **bold**".trim()
        )
    }

    @Test
    fun simpleMacroRender() {
        every { renderContext.node } returns MockContent(StringConstantRenderable("CONSTANT STUFF"))
        assertMarkdown(
            "<p>test render CONSTANT STUFF</p>",
            "test render {{render content=node.foobar/}}"
        )
    }

    @Test
    fun simpleMacroRenderDeep() {
        every { renderContext.node } returns MockContent(StringConstantRenderable("CONSTANT STUFF"))
        // Stub rootNode itself, not contentByPath: loaderContext is a mock, so
        // its `rootNode` getter never runs and relaxed mode just hands back a
        // bare ContentDef mock with no `embed` -- beanutils then fails with
        // "Unknown property 'embed'".
        every { renderContext.renderer.loaderContext.rootNode } returns
            MockRoot(MockContent(StringConstantRenderable("CONSTANT STUFF")))
        assertMarkdown(
            "<p>test render CONSTANT STUFF</p>",
            "test render {{render content=rootNode.embed.foobar/}}"
        )
    }

    @Test
    fun simpleInlineContent() {
        assertMarkdown(
            "test <code>simple</code> markdown",
            "test `simple` markdown",
            asInlineContent = true
        )
    }

    @Test
    fun macroTest() {
        assertFails {
            debugMarkdown("Lorem {{blubb /}}.")
        }
    }

    @Test
    fun linkTest() {
        // Not debugMarkdown(): that renders with a bare Markdown.options, so
        // link resolution hits requireNotNull(LOADER_CONTEXT) and throws
        // "Required value was null". Internal links need a loader context, so
        // go through the mocked one like the other render tests.
        val html = markdown("Lorem [label](/link)").renderedContent(renderContext)
        assertTrue(html.contains("label"), "expected the link label in: $html")
    }

    @Test
    fun admonitionTest() {
        debugMarkdown("""
            !!! warning
                lorem ipsum
        """.trimIndent())
    }

    private fun debugMarkdown(source: String): String {
        val document = Markdown.parser.parse(source)
//        document[VALIDATORS].map { it() }
        println(AstCollectingVisitor().collectAndGetAstText(document))

        return HtmlRenderer.builder(Markdown.options).build().render(document).also(::println)
    }
}