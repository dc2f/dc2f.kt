package com.dc2f

import com.dc2f.render.*
import com.dc2f.richtext.markdown.ValidationRequired
import com.dc2f.util.*
import com.fasterxml.jackson.annotation.JacksonInject
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

interface Renderable: ContentDef {
    fun renderContent(renderContext: RenderContext<*>, arguments: Any? = null): String
}

class Slug private constructor(private val value: String) : ContentDef, ValidationRequired {
    val slug: String get() = value

    override fun validate(loaderContext: LoaderContext, parent: LoadedContent<*>): String? {
        return null
    }

    companion object {
        val VALID_SLUG = Regex("^[a-zA-Z0-9_-]+$")
    }
    init {
        require(value.matches(VALID_SLUG)) {
            "Not a valid slug: $value (must adhere to pattern: $VALID_SLUG"
        }
    }
}

interface SlugCustomization {
    val slug: Slug?

    @JvmDefault
    fun slugGenerationValue(): String? = null
    @JvmDefault
    fun createSlug() = slug?.slug ?: slugGenerationValue()?.let { Slugify().slugify(it) }
}

interface Parsable<T: ContentDef> {
    abstract fun parseContent(
        context: LoaderContext,
        file: Path,
        contentPath: ContentPath
    ): T
}

// TODO: Maybe find a way to enforce the type of content which can be referenced?
class ContentReference(private val contentPathValue: String) : ContentDef, ValidationRequired {

//    @JsonCreator
//    constructor(path: String) : this(ContentPath.parse(path))

    lateinit var referencedContent: ContentDef

    override fun validate(loaderContext: LoaderContext, parent: LoadedContent<*>): String? {
        val contentPath = parent.metadata.path.resolve(contentPathValue)
        referencedContent = loaderContext.contentByPath[contentPath]
            ?: return "Invalid content path: $contentPath"
        return null
    }

    fun href(renderContext: RenderContext<*>): String =
        renderContext.href(referencedContent)

    fun hrefRenderable(): Renderable = object : Renderable {
        override fun renderContent(renderContext: RenderContext<*>, arguments: Any?): String =
            href(renderContext)

    }
}


open class FileAsset(val file: ContentPath, val fsPath: Path) : ContentDef, ValidationRequired {

    val name: String get() = fsPath.fileName.toString()

    private lateinit var container: ContentDef

    override fun validate(loaderContext: LoaderContext, parent: LoadedContent<*>): String? {
        container = loaderContext.contentByPath[file.parent()] ?: return "Unable to find parent of file asset $file"
        return null
    }

    protected fun getTargetOutputPath(context: RenderContext<*>, fileName: String = name): Pair<RenderPath, Path> {
        val containerPath = context.renderer.findRenderPath(container)
        val renderPath = containerPath.child(fileName)

        return renderPath to context.rootPath.resolve(renderPath.toString())
    }

    fun href(context: RenderContext<*>): String {
        val (renderPath, targetPath) = getTargetOutputPath(context)
        Files.createDirectories(targetPath.parent)
        if (!Files.exists(targetPath)) {
            Files.copy(fsPath, targetPath)
        }
        return "/$renderPath"
    }
    fun hrefRenderable(): Renderable = object : Renderable {
        override fun renderContent(renderContext: RenderContext<*>, arguments: Any?): String =
            href(renderContext)

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
        val (renderPath, targetPath) = getTargetOutputPath(context, fileName = fileName)

        val original = ImageIO.read(fsPath.toFile())
//        val targetSize = ImageSize(original.width, original.height)
//            .resize(width, height, fillType)
        val thumbnails = Thumbnails.of(original)
        when(fillType) {
            FillType.Cover -> thumbnails.size(width, height).crop(Positions.CENTER)
            FillType.Fit -> thumbnails.size(width, height)
            FillType.Transform -> thumbnails.forceSize(width, height)
        }
        Files.createDirectories(targetPath.parent)
        thumbnails.toFile(targetPath.toFile())
//        thumbnails.addFilter()
        return ResizedImage(
            "/$renderPath",
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


