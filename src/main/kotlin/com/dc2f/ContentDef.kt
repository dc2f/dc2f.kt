package com.dc2f

import com.dc2f.render.*
import com.dc2f.richtext.markdown.ValidationRequired
import com.dc2f.util.*
import com.fasterxml.jackson.annotation.JsonIgnore
import mu.KotlinLogging
import net.coobird.thumbnailator.Thumbnails
import net.coobird.thumbnailator.geometry.Positions
import net.coobird.thumbnailator.tasks.io.FileImageSink
import org.ehcache.Cache
import org.ehcache.config.builders.*
import org.ehcache.config.units.*
import java.io.Serializable
import java.lang.annotation.Inherited
import java.nio.file.*
import java.util.*
import javax.imageio.ImageIO

private val logger = KotlinLogging.logger {}

@Target(AnnotationTarget.CLASS)
@Inherited
annotation class Nestable(val identifier: String)

@Target(AnnotationTarget.CLASS)
annotation class PropertyType(val identifier: String)

interface ObjectDef

/**
 * Any structured data in dc2f which is constructed out of other primitives or ContentDefs
 *
 * ContentDefs can use the following types:
 *
 * * String
 * * Boolean
 * * int, float
 * * ZonedDateTime
 * * subclasses of ObjectDef
 */
interface ContentDef : ObjectDef

interface Renderable : ObjectDef {
    fun renderContent(renderContext: RenderContext<*>, arguments: Any? = null): String
}

class Slug private constructor(private val value: String) : ObjectDef, ValidationRequired {
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

interface WithRenderPathOverride {
    @JvmDefault
    fun renderPath(renderer: Renderer): RenderPath? = null
}

interface WithRenderPathAliases {
    @JvmDefault
    fun renderPathAliases(renderer: Renderer): List<RenderPath>? = null
}

/**
 * The content of this page is basically a "symlink" to another content the content
 * returned by [contentSymlink] will be rendered at the location of this node.
 *
 * A typical use case is to have a folder with an `index` property which should be placed in the
 * location of this node.
 */
interface WithContentSymlink {
    @JvmDefault
    fun contentSymlink(): ContentDef? = null
}

interface WithUriReferencePathOverride {
    @JvmDefault
    fun uriReferencePath(renderer: Renderer): UriReferencePath? = null
}

interface WithRedirect : WithUriReferencePathOverride {
    val redirect: ContentReference?

    @JvmDefault
    override fun uriReferencePath(renderer: Renderer) =
        redirect?.let { renderer.findUriReferencePath(it.referencedContent) }
}

interface ParsableObjectDef: ObjectDef {
    /** raw unparsed content, which is used to persist this value */
    fun rawContent(): String
}

interface Parsable<T : ParsableObjectDef> {
    fun parseContent(
        context: LoaderContext,
        file: Path,
        contentPath: ContentPath
    ): T
}

// TODO: Maybe find a way to enforce the type of content which can be referenced?
class ContentReference(private val contentPathValue: String) : ObjectDef, ValidationRequired,
    WithRenderPathOverride {

//    @JsonCreator
//    constructor(path: String) : this(ContentPath.parse(path))

    //    lateinit var referencedContentPath: ContentPath
    @Transient
    lateinit var referencedContent: ContentDef

    override fun validate(loaderContext: LoaderContext, parent: LoadedContent<*>): String? {
        val referencedContentPath = parent.metadata.path.resolve(contentPathValue)
        referencedContent = loaderContext.contentByPath[referencedContentPath]
            ?: return "Invalid content path: $referencedContentPath"
        return null
    }

    fun referencedContentPath(loaderContext: LoaderContext) =
        loaderContext.findContentPath(referencedContent)

    fun href(renderContext: RenderContext<*>): String =
        renderContext.href(referencedContent)

    @Suppress("unused")
    fun hrefRenderable(): Renderable = object : Renderable {
        override fun renderContent(renderContext: RenderContext<*>, arguments: Any?): String =
            href(renderContext)

    }

    // TODO this should probably override uriReferencePath, not RenderPath?!
    override fun renderPath(renderer: Renderer): RenderPath =
        renderer.findRenderPath(referencedContent)
}


sealed class BaseFileAsset(val file: ContentPath, val fsPath: Path) : ObjectDef, ValidationRequired {

    val name: String get() = fsPath.fileName.toString()

    @Transient
    internal lateinit var container: ContentDef
    @JsonIgnore
    @Transient
    protected lateinit var loaderContext: LoaderContext

    override fun validate(loaderContext: LoaderContext, parent: LoadedContent<*>): String? {
        this.loaderContext = loaderContext
        container = loaderContext.contentByPath[file.parent()]
            ?: return "Unable to find parent of file asset $file"
        return null
    }

    fun href(context: RenderContext<*>, absoluteUri: Boolean = false): String {
        val renderPath = context.storeInParentContent(fsPath, container, name)
        return context.renderer.href(renderPath, absoluteUri)
    }

    @Suppress("unused")
    fun hrefRenderable(): Renderable = object : Renderable {
        override fun renderContent(renderContext: RenderContext<*>, arguments: Any?): String =
            href(renderContext)

    }
}

class FileAsset(file: ContentPath, fsPath: Path): BaseFileAsset(file, fsPath)

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

class ImageAsset(file: ContentPath, fsPath: Path) : BaseFileAsset(file, fsPath) {
    val imageInfo: ImageInfo by lazy { parseImage() }
    val width by lazy { imageInfo.width }
    val height by lazy { imageInfo.height }
    private val fileSize by lazy { Files.size(fsPath) }

    private fun imageCache() = loaderContext.imageCache

    private fun cachePath(loaderContext: LoaderContext): Path =
        loaderContext.cache.cacheDirectory.toPath().resolve("dc2f-image-resize")
            .also { Files.createDirectories(it) }

    fun resize(
        context: RenderContext<*>,
        width: Int,
        height: Int,
        fillType: FillType
    ): ResizedImage {
//        if (context !is FileRenderContext) {
//            logger.warn { "We are not rendering to file system. can't resize image." }
//            return ResizedImage("/unrendered/$width/$height", width, height)
//        }

        val cachePath = cachePath(context.renderer.loaderContext)
//        val targetPathOrig = context.rootPath.resolve(file.toString())
        val fileName = "${fillType}_${width}x${height}_${file.name}"
//        val (renderPath, targetPath) = getTargetOutputPath(context, fileName = fileName)

        // FIXME: 1.) implement some way to clean up old resized images.
        //        2.) if because of some reason there is a cache entry, but no resized file, we have to resize it again.
        val cacheKey = ImageResizeCacheKey(file.toString(), fileSize, width, height, fillType.name)
        val cachedData = imageCache().imageResizeCache.get(cacheKey)
            ?: {
                logger.info { "Image not found in cache. need to recompute $cacheKey" }
                val original = ImageIO.read(fsPath.toFile())
                val thumbnails = Thumbnails.of(original)
                when (fillType) {
                    FillType.Cover -> thumbnails.size(width, height).crop(Positions.CENTER)
                    FillType.Fit -> thumbnails.size(width, height)
                    FillType.Transform -> thumbnails.forceSize(width, height)
                }
                val thumbnailImage = thumbnails.asBufferedImage()

                val cachedFileName = "${file.name}.${UUID.randomUUID()}.${file.name}"

                FileImageSink(cachePath.resolve(cachedFileName).toFile()).write(thumbnailImage)

                ImageResizeCacheData(cachedFileName, thumbnailImage.width, thumbnailImage.height)
                    .also { imageCache().imageResizeCache.put(cacheKey, it) }
            }()

        val renderPath = context.storeInParentContent(cachePath.resolve(cachedData.cachedFileName), container, fileName)
//        if (!Files.exists(targetPath)) {
//            Files.createDirectories(targetPath.parent)
//            Files.createLink(targetPath, cachePath.resolve(cachedData.cachedFileName))
////            Files.copy(cachePath.resolve(cachedData.cachedFileName), targetPath)
//        }

//        thumbnails.toFile(targetPath.toFile())
//        thumbnails.addFilter()
        return ResizedImage(
            context.renderer.href(renderPath),
//            "/$renderPath",
            cachedData.width,
            cachedData.height
        )
    }

    private fun parseImage() =
        imageCache().imageInfoCache.cached(
            ImageInfoCacheKey(
                fsPath.toString(),
                fileSize
            )
        ) {
            ImageUtil.readImageData(fsPath)
                ?: throw IllegalArgumentException("Invalid image at $fsPath")
        }

}

data class ImageInfoCacheKey(val imageFsPath: String, val imageFileSize: Long) : Serializable
data class ImageResizeCacheKey(
    val imageContentPath: String,
    val imageFileSize: Long,
    val width: Int,
    val height: Int,
    val fillTypeName: String
) : Serializable

data class ImageResizeCacheData(val cachedFileName: String, val width: Int, val height: Int) :
    Serializable

class ImageCache(val cache: CacheUtil) {

    val imageInfoCache: Cache<ImageInfoCacheKey, ImageInfo> by lazy {
        cache.cacheManager
            .createCache(
                "imageInfoCache",
                CacheConfigurationBuilder.newCacheConfigurationBuilder(
                    ImageInfoCacheKey::class.java,
                    ImageInfo::class.java,
                    ResourcePoolsBuilder.newResourcePoolsBuilder()
                        .heap(50, EntryUnit.ENTRIES)
                        .disk(50, MemoryUnit.MB, true)
                )
            )

    }

    val imageResizeCache: Cache<ImageResizeCacheKey, ImageResizeCacheData> by lazy {
        cache.cacheManager
            .createCache(
                "imageResizeCache",
                CacheConfigurationBuilder.newCacheConfigurationBuilder(
                    ImageResizeCacheKey::class.java,
                    ImageResizeCacheData::class.java,
                    ResourcePoolsBuilder.newResourcePoolsBuilder()
                        .heap(50, EntryUnit.ENTRIES)
                        .disk(50, MemoryUnit.MB, true)
                )
            )
    }

    val assetPipelineCache: Cache<AssetPipelineCacheKey, AssetPipelineCacheValue> by lazy {
        cache.cacheManager
            .createCache(
                "assetPipeline",
                CacheConfigurationBuilder.newCacheConfigurationBuilder(
                    AssetPipelineCacheKey::class.java,
                    AssetPipelineCacheValue::class.java,
                    ResourcePoolsBuilder.newResourcePoolsBuilder()
                        .heap(50, EntryUnit.ENTRIES)
                        .disk(50, MemoryUnit.MB, true)
                )
            )
    }

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

//@Target(AnnotationTarget.PROPERTY_SETTER)
//annotation class Children

interface ContentBranchDef<CHILD_TYPE : ContentDef> : ContentDef {
//    @set:Children
    /**
     * This is a special property. [ContentBranchDef::children] will be used for the children.
     */
    var children: List<CHILD_TYPE>

}

interface Website<CHILD_TYPE : ContentDef> : ContentBranchDef<CHILD_TYPE> {
    val name: String
}


