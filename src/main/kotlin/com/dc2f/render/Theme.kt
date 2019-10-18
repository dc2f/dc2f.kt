package com.dc2f.render

import com.dc2f.*
import com.dc2f.assets.*
import com.dc2f.util.isLazyInitialized
import com.google.common.io.*
import kotlinx.html.TagConsumer
import kotlinx.html.stream.appendHTML
import mu.KotlinLogging
import java.io.*
import java.net.URI
import java.nio.file.*
import java.nio.file.Files
import java.time.LocalDate
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.full.isSuperclassOf

private val logger = KotlinLogging.logger {}

abstract class OutputType(val name: String) {
    protected abstract fun indexFileForRenderPath(folderRenderPath: RenderPath): RenderPath

    fun fileForRenderPath(renderPath: RenderPath) =
        if (renderPath.isLeaf) {
            renderPath
        } else {
            indexFileForRenderPath(renderPath)
        }

    class SimpleOutputType(name: String, private val indexFileName: String) : OutputType(name) {
        override fun indexFileForRenderPath(folderRenderPath: RenderPath): RenderPath = folderRenderPath.childLeaf(indexFileName)
    }

    companion object {
        val html = SimpleOutputType("html", "index.html")
        val robotsTxt = SimpleOutputType("robots.txt", "robots.txt")
        val rssFeed = SimpleOutputType("rss", "feed.rss")
    }
}

interface ThemeMarker {

}

abstract class Theme() : ThemeMarker {

    val config: ThemeConfig = ThemeConfig()

    init {
        // TODO maybe we should change how configure is called?
        @Suppress("LeakingThis")
        configure(config)
    }


    abstract fun configure(config: ThemeConfig)

    internal fun <T: ContentDef> findRenderer(node: T, forOutputType: OutputType): ThemeConfig.RenderConfig<*> =
        // for now we don't support having more than one renderer for each type.
        requireNotNull(config.renderers[forOutputType]) { "Output Type has no configured renderers $forOutputType" }
            .firstOrNull { it.canRender(node) } ?: throw Exception("Unable to find a suitable renderer for $node")

    /**
     * Provide a "title" for links to this content.
     * (a bit hackish.. maybe we could make this a bit more generic?)
     */
    open fun renderLinkTitle(content: ContentDef): String? = null
}

data class AssetPipelineCacheKey(val cacheInfo: String, val transformer: List<TransformerCacheKey>) : Serializable
data class AssetPipelineCacheValue(
    val fileName: String,
    val cachedFileName: String,
    val transformerValue: List<TransformerValue?>
) : Serializable

@Suppress("UnstableApiUsage")
class AssetPipeline(
    private val cacheInfo: String,
    private val context: RenderContext<*>,
    private val renderCharAsset: RenderCharAsset
) {

    // TODO maybe we should not always call createDirectories here?
    val cachePath: Path by lazy {
        loaderContext.cache.cacheDirectory.toPath().resolve("dc2f-assetpipeline")
            .also { Files.createDirectories(it) }
    }

    val cacheNamePrefix by lazy {
        LocalDate.now().let { "${it.year}-${it.monthValue}" }
            .also { Files.createDirectories(cachePath.resolve(it)) }
    }

    private val loaderContext get() = context.renderer.loaderContext

    private val pipeline = mutableListOf<Transformer<TransformerValue>>()

    fun href(outputDirectory: RenderPath, urlConfig: UrlConfig): String = UriReferencePath.fromRenderPath(runTransformations(outputDirectory), urlConfig).absoluteUrlWithoutHost()

    private fun runTransformations(outputDirectory: RenderPath): RenderPath {
        // check if we have cached something..
        val cacheKey = AssetPipelineCacheKey(cacheInfo, pipeline.map { it.cacheKey })
        val imageCache = loaderContext.imageCache
        val value = imageCache.assetPipelineCache.get(cacheKey)?.also {
            it.transformerValue.forEachIndexed { index, transformerValue ->
                transformerValue?.let {
                    pipeline[index].updateValueFromCache(transformerValue)
                    // make sure value was actually set correctly.
                    assert(pipeline[index].value != null)
                }
            }
        } ?: {
            val result = pipeline.fold(renderCharAsset) { last, transformer ->
                transformer.transform(last)
            }

            val cachedFileName = "$cacheNamePrefix/${result.fileName}.${UUID.randomUUID()}.${result.fileName}"
            result.contentReader.copyTo(MoreFiles.asCharSink(cachePath.resolve(cachedFileName), Charsets.UTF_8))

            AssetPipelineCacheValue(result.fileName, cachedFileName, pipeline.map { it.value })
                .also { imageCache.assetPipelineCache.put(cacheKey, it) }
        }()

//        if (pipeline.isEmpty()) {
//            if (!Files.exists(absPath)) {
//                renderCharAsset.contentReader.copyTo(MoreFiles.asCharSink(absPath, Charsets.UTF_8))
//            }
//            return "/$outputPath"
//        }

        val renderPath = outputDirectory.childLeaf(value.fileName).asType(RenderPathType.StaticAsset)
        if (context is FileRenderContext) {
            val absFsPath = context.rootPath.resolve(renderPath.toString())
            val cachedFsPath = cachePath.resolve(value.cachedFileName)

            if (!Files.exists(absFsPath)) {
                Files.createDirectories(absFsPath.parent)
                Files.createLink(absFsPath, cachedFsPath)
            }
        } else {
            val cachedFsPath = cachePath.resolve(value.cachedFileName)
            context.storeInRenderPath(cachedFsPath, renderPath)
            return renderPath
        }
//        result.contentReader.copyTo(MoreFiles.asCharSink(absFsPath, Charsets.UTF_8))
//        if (!Files.exists(absPath)) {
//            val outputUri = absPath.toUri()
//            pipeline[0].transform(renderCharAsset)
//        }
//        Files.write(context.rootPath.resolve(outputPath), output.toByteArray())
//        return "/$outputPath"
        return renderPath
    }

    fun transform(transformer: Transformer<TransformerValue>): AssetPipeline {
        pipeline.add(transformer)
        return this
    }
}

class RenderCharAsset(
    val contentReader: CharSource,
    val fileName: String
)

class LazyFileRenderOutput(val filePath: Path) : RenderOutput() {
    private val writer by lazy {
        logger.debug { "creating file at $filePath." }
        Files.newBufferedWriter(filePath)
    }

    override fun appendln(string: String) {
        writer.appendln(string)
    }

    override fun appendHTML() = writer.appendHTML()

    override fun close() {
        if (this::writer.isLazyInitialized) {
            writer.close()
        }
    }
}

class AppendableOutput(val appendable: Appendable) : RenderOutput() {
    override fun appendln(string: String) { appendable.appendln(string) }
    override fun appendHTML(): TagConsumer<Appendable> = appendable.appendHTML()
    override fun close() {}

}

abstract class RenderOutput : Closeable {
    abstract fun appendln(string: String)
    internal abstract fun appendHTML() : TagConsumer<Appendable>
}

interface RenderContextData<T: ContentDef> {
    val node: T
    val metadata: ContentDefMetadata
    val theme: Theme
    val out: RenderOutput
    val renderer: Renderer
    val enclosingNode: ContentDef?
    val forOutputType: OutputType
    val rootNode get() = renderer.loaderContext.rootNode
}

abstract class RenderContext<T : ContentDef> : RenderContextData<T> {

    open val context: RenderContext<T> get() = this

    //    fun render()
    abstract fun <U: ContentDef> createSubContext(
        node: U,
        out: RenderOutput,
        enclosingNode: ContentDef?
    ) : RenderContext<U>

    fun render() {
        @Suppress("UNCHECKED_CAST")
        val renderer = theme.findRenderer(this.node, forOutputType = forOutputType)
            .renderer as RenderContext<T>.() -> Unit
        this.renderer()
    }

    fun href(page: ContentDef, absoluteUrl: Boolean = false): String =
        renderer.href(page, absoluteUrl)

    fun<U: ContentDef> renderNode(content: U): String =
        renderer.renderPartialContent(content, metadata, this)


    fun<U: ContentDef> renderChildren(children: List<U>) {
        children.map { child ->
            val metadata = requireNotNull(metadata.childrenMetadata[child] ?: renderer.loaderContext.metadata[child]) {
                "Unknown child? $child"
            }
            renderer.renderContent(child, metadata, this, forOutputType)
        }
    }

    fun appendHTML() =
        out.run {
            appendln("<!DOCTYPE html>")
            appendHTML()
        }

    fun getAsset(path: String): AssetPipeline {
        // TODO add caching

        // for now we always load from the file system, mainly because it is easier for reloading..
        // (and scss transform from classpath is not supported anyway).
//        val resource =
//            theme.javaClass.classLoader.getResource(path)?.toURI()
//                ?: getResourceFromFileSystem(path)
        val resource = getAssetFromFileSystem(path)
        val size = try { File(resource).length() } catch (e: IllegalArgumentException) { -1L }
        logger.debug { "getAsset for $path:$size" }
        @Suppress("UnstableApiUsage")
        return AssetPipeline(
            "$path:$size",
            this,
            RenderCharAsset(
                Resources.asCharSource(resource.toURL(), Charsets.UTF_8),
                File(resource.toURL().file).name
            )
        )
    }

    fun getAssetFromFileSystem(path: String): URI {
        // TODO kinda hackish, don't you think?
        //val root = FileSystems.getDefault().getPath("src", "main", "resources")
        val root = theme.config.assetBaseDirectory
        val resource = root.resolve(path)
        if (!Files.exists(resource)) {
            throw IllegalArgumentException("Unable to find required asset: $path (in $resource)")
        }
        return resource.toUri()
    }

    fun storeInParentContent(sourceFsPath: Path, container: ContentDef, fileName: String): RenderPath {
        val targetRenderPath = renderer.findRenderPath(container).childLeaf(fileName).asType(RenderPathType.StaticAsset)
        storeInRenderPath(sourceFsPath, targetRenderPath)
        return targetRenderPath
    }

    abstract fun storeInRenderPath(sourceFsPath: Path, targetRenderPath: RenderPath)
}

data class BaseRenderContextData<T : ContentDef>(
    override val node: T,
    // the root metadata.
    override val metadata: ContentDefMetadata,
    override val theme: Theme,
    override val out: RenderOutput,
    override val renderer: Renderer,
    override val enclosingNode: ContentDef? = null,
    override val forOutputType: OutputType
) : RenderContextData<T> {

    @Suppress("UNCHECKED_CAST")
    fun <U : ContentDef>createSubContextData(node: U, out: RenderOutput, enclosingNode: ContentDef?) =
        (this as BaseRenderContextData<U>).copy(node = node, out = out, enclosingNode = enclosingNode)

}

class InMemoryRenderContext<T: ContentDef>(val data: BaseRenderContextData<T>, private val resourcesOutputPath: Path?) : RenderContextData<T> by data, RenderContext<T>() {

    val content get() = node
    override val context: RenderContext<T> get() = this

    @Suppress("UNCHECKED_CAST")
    override fun <U : ContentDef>createSubContext(node: U, out: RenderOutput, enclosingNode: ContentDef?) =
        InMemoryRenderContext(data.createSubContextData(node = node, out = out, enclosingNode = enclosingNode), resourcesOutputPath)

    override fun storeInRenderPath(sourceFsPath: Path, targetRenderPath: RenderPath) {
//        logger.warn { "We are not rendering to files, so we can't resolve output path!" }
        if (resourcesOutputPath != null) {
            val targetFsPath = resourcesOutputPath.resolve(targetRenderPath.toString())
            if (!Files.exists(targetFsPath)) {
                Files.createDirectories(targetFsPath.parent)
                Files.createLink(targetFsPath, sourceFsPath)
//            Files.copy(sourceFsPath, targetFsPath)
            }
        } else {
            logger.warn { "We are not rendering to files, so we can't resolve output path!" }
        }
    }
}

class FileRenderContext<T: ContentDef>(
    private val baseRenderContextData: BaseRenderContextData<T>,
    val rootPath: Path
    ) : RenderContextData<T> by baseRenderContextData, RenderContext<T>() {

    override fun storeInRenderPath(sourceFsPath: Path, targetRenderPath: RenderPath) {
        val targetFsPath = rootPath.resolve(targetRenderPath.toString())
        if (!Files.exists(targetFsPath)) {
            Files.createDirectories(targetFsPath.parent)
            Files.createLink(targetFsPath, sourceFsPath)
//            Files.copy(sourceFsPath, targetFsPath)
        }
    }

    override fun <U : ContentDef> createSubContext(
        node: U,
        out: RenderOutput,
        enclosingNode: ContentDef?
    ): RenderContext<U> =
        FileRenderContext(
            baseRenderContextData.createSubContextData(
                node = node,
                out = out,
                enclosingNode = enclosingNode
            ),
            rootPath = rootPath
        )
}

//open class PageRenderContext<T : ContentBranchDef<*>>(
//    node: T,
//    metadata: ContentDefMetadata
//) : RenderContext<T>(
//    node,
//    metadata
//)


class ThemeConfig {

    class RenderConfig<T : ContentDef>(
        val pageClass: KClass<T>,
        val renderer: RenderContext<T>.() -> Unit
    ) {
        fun <U: ContentDef> canRender(node: U) =
            pageClass.isSuperclassOf(node::class)
    }

    internal val renderers = mutableMapOf<OutputType, MutableList<RenderConfig<*>>>()
    internal lateinit var assetBaseDirectory: Path

    inline fun <reified T : ContentDef> pageRenderer(
        forOutputType: OutputType = OutputType.html,
//        noinline fileName: (page: LoadedContent<T>) -> Path,
        noinline renderer: RenderContext<T>.() -> Unit
    ) {
        _registerPageRenderer(
            forOutputType,
            RenderConfig(
                T::class, renderer
            )
        )
    }

    @Suppress("FunctionName")
    fun _registerPageRenderer(forOutputType: OutputType, config: RenderConfig<*>) {
        renderers.getOrPut(forOutputType) { mutableListOf() }
            .add(config)
    }
}
