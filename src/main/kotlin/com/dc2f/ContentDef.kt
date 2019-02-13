package com.dc2f

import com.dc2f.render.RenderContext
import com.dc2f.richtext.markdown.ValidationRequired
import com.dc2f.util.*
import com.fasterxml.jackson.annotation.*
import mu.KotlinLogging
import net.coobird.thumbnailator.Thumbnails
import net.coobird.thumbnailator.geometry.Positions
import java.lang.annotation.Inherited
import java.nio.file.*
import javax.imageio.ImageIO

private val logger = KotlinLogging.logger {}

@Target(AnnotationTarget.CLASS) @Inherited
annotation class Nestable(val identifier: String)
@Target(AnnotationTarget.CLASS)
annotation class PropertyType(val identifier: String)

interface ContentDef

interface RichText: ContentDef {
}

interface Parsable<T: ContentDef> {
    abstract fun parseContent(
        context: LoaderContext,
        file: Path,
        contentPath: ContentPath
    ): T
}

class ContentReference(private val contentPath: ContentPath) : ContentDef, ValidationRequired {

    @JsonCreator
    constructor(path: String) : this(ContentPath.parse(path))

    lateinit var referencedContent: ContentDef

    override fun validate(loaderContext: LoaderContext): String? {
        referencedContent = loaderContext.contentByPath[contentPath]
            ?: return "Invalid content path: $contentPath"
        return null
    }

    fun href(renderContext: RenderContext<*>): String =
        renderContext.href(referencedContent)
}


open class FileAsset(val file: ContentPath, val fsPath: Path) {
    val name: String get() = fsPath.fileName.toString()

    fun href(context: RenderContext<*>): String {
        val targetPath = context.rootPath.resolve(file.toString())
        Files.createDirectories(targetPath.parent)
        if (!Files.exists(targetPath)) {
            Files.copy(fsPath, targetPath)
        }
        return "/$file"
    }
}

enum class FillType {
    Fit,
    Cover,
    Transform
}

//data class ImageSize(
//    val width: Int, val height: Int
//) {
//    fun resize(targetWidth: Int, targetHeight: Int, fillType: FillType) =
//        when(fillType) {
//            FillType.Transform -> ImageSize(targetWidth, targetHeight)
//            FillType.Cover -> {
//                // 10x100 -> 20x100 ---- (0.1 vs 0.2)
//                // 100x10 -> 100x20 ---- (10 vs 5)
//                val ratio = width.toDouble() / height
//                val targetRatio = targetWidth.toDouble() / targetHeight
//                if (ratio > targetRatio) {
//                    ImageSize(targetWidth, (targetWidth / ratio).toInt())
//                } else {
//                    ImageSize((targetWidth * ratio).toInt(), targetHeight)
//                }
//            }
//            FillType.Fit -> {
//                val ratio = width.toDouble() / height
//                val targetRatio = targetWidth.toDouble() / targetHeight
//                if (ratio < targetRatio) {
//                    ImageSize(targetWidth, (targetWidth / ratio).toInt())
//                } else {
//                    ImageSize((targetWidth * ratio).toInt(), targetHeight)
//                }
//            }
//        }
//}

class ResizedImage(
    val href: String,
    val width: Int,
    val height: Int
)

open class ImageAsset(file: ContentPath, fsPath: Path) : FileAsset(file, fsPath) {
    val imageInfo: ImageInfo by lazy { parseImage() }
    val width get() = imageInfo.width
    val height get() = imageInfo.height

    fun resize(context: RenderContext<*>, width: Int, height: Int, fillType: FillType): ResizedImage {
        val targetPathOrig = context.rootPath.resolve(file.toString())
        val fileName = "${fillType}_${width}x${height}_${targetPathOrig.fileName}"
        val targetContentPath = file.sibling(fileName)
        val targetPath = targetPathOrig.resolveSibling(fileName)

        val original = ImageIO.read(fsPath.toFile())
//        val targetSize = ImageSize(original.width, original.height)
//            .resize(width, height, fillType)
        val thumbnails = Thumbnails.of(original)
        when(fillType) {
            FillType.Cover -> thumbnails.size(width, height).crop(Positions.CENTER)
            FillType.Fit -> thumbnails.size(width, height)
            FillType.Transform -> thumbnails.forceSize(width, height)
        }
        thumbnails.toFile(targetPath.toFile())
//        thumbnails.addFilter()
        return ResizedImage(
            "/$targetContentPath",
            width,
            height)
    }

    private fun parseImage() =
        ImageUtil.readImageData(fsPath) ?: throw IllegalArgumentException("Invalid image at $fsPath")
}

//@JsonDeserialize(using = ChildrenDeserializer::class)
//class Children<T: ContentDef>(val children: List<T>)

//class ChildrenDeserializer: JsonDeserializer<Children<*>?>() {
//    override fun deserialize(p: JsonParser?, ctxt: DeserializationContext?): Children<*>? {
//        logger.debug { "Deserializing stuff." }
//        val res = ctxt?.findInjectableValue("children", null, null)
//        if (res is Children<*>) {
//            return res
//        }
//        return null
//    }
//
//    override fun getNullValue(ctxt: DeserializationContext?): Children<*>? {
//        logger.debug { "need to get null value." }
//        return super.getNullValue(ctxt)
//    }
//
//}

interface ContentBranchDef<CHILD_TYPE: ContentDef> : ContentDef {
    @get:JacksonInject("children") @set:JacksonInject("children")
    var children: List<CHILD_TYPE>

}

interface Website<CHILD_TYPE: ContentDef> : ContentBranchDef<CHILD_TYPE> {
    val name: String
}


