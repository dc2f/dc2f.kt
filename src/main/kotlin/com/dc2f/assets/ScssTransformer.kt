package com.dc2f.assets

import com.dc2f.render.RenderCharAsset
import com.google.common.hash.Hashing
import com.google.common.io.CharSource
import io.bit3.jsass.*
import io.bit3.jsass.importer.*
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
                    val compiler = Compiler()
                    val options = Options()
                    options.importers.add(object : Importer {
                        override fun apply(url: String, previous: Import?): MutableCollection<Import>? {
                            logger.debug { "should import $url ($previous)" }
                            if (url.startsWith('~')) {
                                val relative = url.substring(1)
                                val nodeModules = modulesBasePath
                                val path = sequenceOf("", ".scss", ".sass", ".css")
                                    .map { relative + it }
                                    .map { nodeModules.resolve(it) }
                                    .tap { logger.debug { "Exists? ${it.toAbsolutePath()}" } }
                                    .firstOrNull { Files.exists(it) } ?: return null
                                logger.info { "Resolved $url to $path (${path.toUri()} // ${path.toAbsolutePath().toUri()}" }
//                                return mutableListOf(Import(URI(url), path.toAbsolutePath().toUri(), path.readString()))
//                                return mutableListOf(Import(path.toUri(), path.toAbsolutePath().toUri()))
                                return mutableListOf(
                                    Import(
                                        path.toUri(),
                                        path.toAbsolutePath().toUri(),
                                        """@import '$path'; """
                                    )
                                )
                            }
                            return null
                        }

                    })
                    options.includePaths.addAll(includePaths)

                    val result = compiler.compileString(input.contentReader.read(), options)
                    // TODO do something with the source map?
                    return StringReader(result.css)
//                    val result = compiler.compileFile(input, output, options)
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

private fun <T> Sequence<T>.tap(cb: (T) -> Unit): Sequence<T> =
    map {
        cb(it)
        it
    }
