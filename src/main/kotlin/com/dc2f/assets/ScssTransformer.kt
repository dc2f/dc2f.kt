package com.dc2f.assets

import io.bit3.jsass.*
import mu.KotlinLogging
import java.io.File
import java.net.URI
import java.nio.file.*

private val logger = KotlinLogging.logger {}

class ScssTransformer : Transformer {


    override fun transform(input: URI, output: URI) {
        val compiler = Compiler()
        val options = Options()
        options.includePaths.add(File("."));
        val result = compiler.compileFile(input, output, options)
        Files.write(Paths.get(output), result.css.toByteArray())
        logger.debug { "Successfully compiled scss from $input into $output" }
    }
}
