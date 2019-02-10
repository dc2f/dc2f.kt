package com.dc2f.render

import com.dc2f.*
import mu.KotlinLogging
import java.io.File
import java.lang.StringBuilder
import java.nio.file.*


private val logger = KotlinLogging.logger {}

class Renderer(
    private val theme: Theme,
    private val target: Path
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

    fun render(node: ContentDef, metadata: ContentDefMetadata) {
        // first clear target directory
        clear()
        val dir = target.resolve(metadata.path.toString())
        Files.createDirectories(dir)
        Files.newBufferedWriter(dir.resolve("index.html")).use { writer ->
            RenderContext(
                rootPath = dir,
                node = node,
                metadata = metadata,
                theme = theme,
                out = writer
            ).renderToHtml()
        }
    }
}