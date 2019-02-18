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
                    mockk<LoadedContent<ContentDef>>())
            }

    private fun assertMarkdown(expected: String, source: String) =
        assertEquals(expected, markdown(source).renderedContent(renderContext).trim())

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
        assertMarkdown(
            "<p>test render CONSTANT STUFF</p>",
            "test render {{render content=rootNode.embed.foobar/}}"
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
        debugMarkdown("Lorem [label](/link)")
    }

    private fun debugMarkdown(source: String): String {
        val document = Markdown.parser.parse(source)
//        document[VALIDATORS].map { it() }
        println(AstCollectingVisitor().collectAndGetAstText(document))

        return HtmlRenderer.builder(Markdown.options).build().render(document).also(::println)
    }
}