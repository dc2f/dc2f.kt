package com.dc2f.render

import com.dc2f.*
import java.io.File
import java.lang.StringBuilder
import java.nio.file.*


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
            .peek(System.out::println)
            .forEach(Files::delete);
    }

    fun render(node: ContentDef, metadata: ContentDefMetadata) {
        // first clear target directory
        clear()
        val dir = target.resolve(metadata.path.toString())
        Files.createDirectories(dir)
        Files.newBufferedWriter(dir.resolve("index.html")).use { writer ->
            RenderContext(node, metadata, theme, writer).renderToHtml()
        }
    }
}