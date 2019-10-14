package com.dc2f.assets

import assertk.assertThat
import assertk.assertions.contains
import com.dc2f.render.RenderCharAsset
import com.google.common.io.CharSource
import mu.KotlinLogging
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Paths

private val logger = KotlinLogging.logger {}

internal class ScssTransformerTest {

    @Test
    fun importTestScss() {
        logger.debug { "Starting test." }
        val transformer = ScssTransformer(
            includePaths = listOf(File(".")),
            modulesBasePath = Paths.get("src/test")
        )
        val result = transformer.transform(RenderCharAsset(CharSource.wrap("""
            // lorem ipsum
            @import '~resources/scss_transformer_test_import';
            
            body {
                color: red;
            }
            """.trimIndent()), "loremipsum.scss"))
        val transformed = result.contentReader.read()
        logger.info { "result: $result / transformed: $transformed"}
        assertThat(transformed).contains("included {")
    }

    @Test
    fun importTestSass() {
        logger.debug { "Starting test." }
        val transformer = ScssTransformer(
            includePaths = listOf(File(".")),
            modulesBasePath = Paths.get("src/test")
        )
        val result = transformer.transform(RenderCharAsset(CharSource.wrap("""
            // lorem ipsum
            @import '~resources/scss_transformer_test_import2';
            
            body {
                color: red;
            }
            """.trimIndent()), "loremipsum.scss"))
        val transformed = result.contentReader.read()
        logger.info { "result: $result / transformed: $transformed"}
        assertThat(transformed).contains("included {")
    }

}