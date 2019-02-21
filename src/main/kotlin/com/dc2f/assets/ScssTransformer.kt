package com.dc2f.assets

import com.dc2f.render.RenderCharAsset
import com.google.common.hash.Hashing
import com.google.common.io.CharSource
import io.bit3.jsass.*
import mu.KotlinLogging
import java.io.*
import java.util.*

private val logger = KotlinLogging.logger {}

fun CharSequence.splitAtLastOccurrence(char: Char): Pair<String, String> {
    val pos = lastIndexOf(char)
    return substring(0, pos) to substring(pos+1)
}

data class DigestValue(val integrityAttrValue: String) : TransformerValue

@Suppress("UnstableApiUsage")
class DigestTransformer(override val cacheKey: TransformerCacheKey = StringTransformerCacheKey("")) : Transformer<DigestValue> {

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

class ScssTransformer(val includePaths: List<File> = emptyList(),
                      override val cacheKey: TransformerCacheKey = StringTransformerCacheKey(includePaths.hashCode().toString())
) : Transformer<TransformerValue> {


    override fun transform(input: RenderCharAsset): RenderCharAsset {
        return RenderCharAsset(
            object : CharSource() {
                override fun openStream(): Reader {
                    val compiler = Compiler()
                    val options = Options()
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
