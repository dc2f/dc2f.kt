package com.dc2f.assets

import com.dc2f.render.RenderCharAsset
import com.google.common.hash.Hashing
import com.google.common.io.CharSource
import de.larsgrefer.sass.embedded.SassCompilerFactory
import de.larsgrefer.sass.embedded.importer.FileImporter
import mu.KotlinLogging
import java.io.*
import java.nio.file.*
import java.util.*

private val logger = KotlinLogging.logger {}

fun CharSequence.splitAtLastOccurrence(char: Char): Pair<String, String> {
    val pos = lastIndexOf(char)
    return substring(0, pos) to substring(pos + 1)
}

data class DigestValue(val integrityAttrValue: String) : TransformerValue

/**
 * Calculates sha 256 hash of the resource. This must (obviously) be the last transformation.
 * It will 1. change the file name to include the hash for cache-busting and 2. set a value containing the digest for
 * the html `integrity` attribute.
 */
@Suppress("UnstableApiUsage")
class DigestTransformer(override val cacheKey: TransformerCacheKey = StringTransformerCacheKey("")) :
    Transformer<DigestValue> {

    override var value: DigestValue? = null

    override fun updateValueFromCache(transformerValue: TransformerValue) {
        value = transformerValue as? DigestValue
    }

    override fun transform(input: RenderCharAsset): RenderCharAsset {
        val inputString = input.contentReader.read()
        val hashCode = Hashing.sha256().hashString(inputString, Charsets.UTF_8)
        val algorithm = Hashing::sha256.name

        value = DigestValue(integrityAttrValue = "$algorithm-${Base64.getEncoder().encodeToString(hashCode.asBytes())}")
        val (baseName, extension) = input.fileName.splitAtLastOccurrence('.')

        return RenderCharAsset(
            CharSource.wrap(inputString),
            "$baseName.$hashCode.$extension"
        )
    }

}

class ScssTransformer(
    val includePaths: List<File> = emptyList(),
    val modulesBasePath: Path = Paths.get("node_modules"),
    override val cacheKey: TransformerCacheKey = StringTransformerCacheKey(includePaths.hashCode().toString())
) : Transformer<TransformerValue> {


    override fun transform(input: RenderCharAsset): RenderCharAsset {
        return RenderCharAsset(
            object : CharSource() {
                override fun openStream(): Reader {
                    // One compiler per stylesheet. `bundled()` starts the dart-sass
                    // process that ships inside sass-embedded-bundled, and that
                    // process carries the load paths and importer registered on it,
                    // so it cannot be shared across concurrent transforms. A site
                    // has a handful of stylesheets, which makes the startup cost
                    // irrelevant next to the correctness of not sharing state.
                    val css = SassCompilerFactory.bundled().use { compiler ->
                        compiler.loadPaths = compiler.loadPaths + includePaths
                        compiler.registerImporter(NodeModulesImporter(modulesBasePath))
                        compiler.compileScssString(input.contentReader.read()).css
                    }
                    // TODO do something with the source map?
                    return StringReader(css)
                }

            },
            "${input.fileName.substringBeforeLast('.')}.css"
        )
    }
//
//        val compiler = Compiler()
//        val options = Options()
//        options.includePaths.add(File("."));
//        val result = compiler.compileFile(input, output, options)
//        Files.write(Paths.get(output), result.css.toByteArray())
//        logger.debug { "Successfully compiled scss from $input into $output" }
//    }
}

/**
 * Resolves `~`-prefixed imports -- the webpack convention for "look in the module
 * directory" -- out of [modulesBasePath]. Anything else is left to the compiler's
 * own load paths.
 *
 * This is a [FileImporter] rather than an importer that answers with the file's
 * contents: handing dart-sass the file itself means imports *inside* that file are
 * resolved by sass relative to it, partials and index files included. An importer
 * that returns contents owns that resolution too, and bulma alone imports about
 * forty further files.
 */
private class NodeModulesImporter(private val modulesBasePath: Path) : FileImporter() {
    override fun handleImport(url: String, fromImport: Boolean): File? {
        if (!url.startsWith('~')) {
            return null
        }
        val relative = url.substring(1)
        val path = sequenceOf("", ".scss", ".sass", ".css")
            .map { modulesBasePath.resolve(relative + it) }
            .firstOrNull { Files.exists(it) }
        logger.debug { "Resolved $url to $path" }
        return path?.toAbsolutePath()?.toFile()
    }
}
