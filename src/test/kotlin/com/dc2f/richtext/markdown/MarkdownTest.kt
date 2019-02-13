package com.dc2f.richtext.markdown

import com.dc2f.LoaderContext
import com.vladsch.flexmark.html.HtmlRenderer
import java.nio.file.*
import kotlin.test.*


class MarkdownTest {

    val mockContext = LoaderContext(FileSystems.getDefault().getPath("."))

    @Test
    fun simpleTest() {
        assertEquals(
            Markdown.parseContentString(mockContext, "Lorem ipsum **bold**")
                .toString().trim(),
            "<p>Lorem ipsum <strong>bold</strong></p>"
        )
    }

    @Test
    fun macroTest() {
        debugMarkdown("Lorem {{blubb /}}.")
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