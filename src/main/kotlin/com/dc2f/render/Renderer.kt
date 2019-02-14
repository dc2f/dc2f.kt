package com.dc2f.render

import com.dc2f.*
import mu.KotlinLogging
import java.io.StringWriter
import java.nio.file.*


private val logger = KotlinLogging.logger {}

class Renderer(
    private val theme: Theme,
    private val target: Path,
    val loaderContext: LoaderContext
) {

    private fun clear() {
        if (!Files.exists(target)) {
            return;
        }
        Files.walk(target)
            .sorted(Comparator.reverseOrder())
//            .map(Path::toFile)
            .peek { logger.debug { "Deleting $it" }}
            .forEach(Files::delete);
    }

    fun renderWebsite(node: ContentDef, metadata: ContentDefMetadata) {
        // first clear target directory
        clear()
        renderContent(node, metadata)
    }

    fun renderContent(node: ContentDef, metadata: ContentDefMetadata, previousContext: RenderContext<*>? = null) {
        val dir = target.resolve(metadata.path.toString())
        Files.createDirectories(dir)
        Files.newBufferedWriter(dir.resolve("index.html")).use { writer ->
            RenderContext(
                rootPath = previousContext?.rootPath ?: dir,
                node = node,
                metadata = previousContext?.metadata ?: metadata,
                theme = theme,
                out = writer,
                renderer = this
            ).renderToHtml()
        }
    }

    fun renderPartialContent(node: ContentDef, metadata: ContentDefMetadata, previousContext: RenderContext<*>): String {
        val writer = StringWriter()
        RenderContext(
            rootPath = previousContext.rootPath,
            node = node,
            metadata = previousContext.metadata,
            theme = theme,
            out = writer,
            renderer = this
        ).renderToHtml()
        return writer.toString()
    }
}