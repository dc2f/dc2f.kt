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
    }
}

abstract class Theme {

    val config: ThemeConfig = ThemeConfig()

    init {
        configure(config)
    }


    abstract fun configure(config: ThemeConfig)

    internal fun <T: ContentDef> findRenderer(node: T, forOutputType: OutputType): ThemeConfig.RenderConfig<*> =
        // for now we don't support having more than one renderer for each type.
        requireNotNull(config.renderers[forOutputType]) { "Output Type has no configured renderers $forOutputType" }
            .first { it.canRender(node) }

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

    fun href(outputDirectory: RenderPath): String = "/${runTransformations(outputDirectory)}"

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

        val renderPath = outputDirectory.child(value.fileName)
        if (context is FileRenderContext) {
            val absFsPath = context.rootPath.resolve(renderPath.toString())
            val cachedFsPath = cachePath.resolve(value.cachedFileName)

            if (!Files.exists(absFsPath)) {
                Files.createDirectories(absFsPath.parent)
                Files.createLink(absFsPath, cachedFsPath)
            }
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

interface RenderContext<T : ContentDef> {
    val node: T
    val metadata: ContentDefMetadata
    val theme: Theme
    val out: RenderOutput
    val renderer: Renderer
    val enclosingNode: ContentDef?
    val forOutputType: OutputType
    val context get() = this
    val rootNode get() = renderer.loaderContext.rootNode

    fun render()
    fun <U: ContentDef> createSubContext(node: U, out: RenderOutput) : RenderContext<U>

    fun href(page: ContentDef, absoluteUrl: Boolean = false): String =
        renderer.href(page, absoluteUrl)

    fun<U: ContentDef> renderNode(content: U): String =
        renderer.renderPartialContent(content, metadata, this)

    fun <U : ContentDef> renderChildren(children: List<U>)

    fun appendHTML() =
        out.run {
            appendln("<!DOCTYPE html>")
            appendHTML()
        }

    fun getAsset(path: String): AssetPipeline {
        // TODO add caching
        val resource =
            theme.javaClass.classLoader.getResource(path)?.toURI()
                ?: getResourceFromFileSystem(path)
        val size = try { File(resource).length() } catch (e: IllegalArgumentException) { -1L }
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

    fun getResourceFromFileSystem(path: String): URI {
        // TODO kinda hackish, don't you think?
        val root = FileSystems.getDefault().getPath("src", "main", "resources")
        val resource = root.resolve(path)
        if (!Files.exists(resource)) {
            throw IllegalArgumentException("Unable to find required asset: $path (in $resource)")
        }
        return resource.toUri()
    }
}

data class BaseRenderContext<T : ContentDef>(
    override val node: T,
    // the root metadata.
    override val metadata: ContentDefMetadata,
    override val theme: Theme,
    override val out: RenderOutput,
    override val renderer: Renderer,
    override val enclosingNode: ContentDef? = null,
    override val forOutputType: OutputType
) : RenderContext<T> {
    val content get() = node

    override fun render() {
        @Suppress("UNCHECKED_CAST")
        val renderer = theme.findRenderer(this.node, forOutputType = forOutputType)
            .renderer as RenderContext<T>.() -> Unit
        this.renderer()
    }

    @Suppress("UNCHECKED_CAST")
    override fun <U : ContentDef>createSubContext(node: U, out: RenderOutput) =
        (this as BaseRenderContext<U>).copy(node = node, out = out)


    override fun<U: ContentDef> renderChildren(children: List<U>) {
        children.map { child ->
            val metadata = requireNotNull(metadata.childrenMetadata[child]) { "Unknown child? ${child}" }
            renderer.renderContent(child, metadata, this, forOutputType)
        }
    }

    inline fun<reified U: T> nodeType(): RenderContext<U>? {
        if (node is U) {
            @Suppress("UNCHECKED_CAST")
            return this as RenderContext<U>
        } else {
            return null
        }
    }

    inline fun <reified U: T, RET> ifType(block: RenderContext<U>.() -> RET): RET? {
        return nodeType<U>()?.let(block)
    }
}

class FileRenderContext<T: ContentDef>(
    private val baseRenderContext: BaseRenderContext<T>,
    val rootPath: Path
    ) : RenderContext<T> by baseRenderContext {

    override fun <U : ContentDef> createSubContext(
        node: U,
        out: RenderOutput
    ): RenderContext<U> =
        FileRenderContext(
            baseRenderContext.createSubContext(
                node = node, out = out
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
