package com.dc2f.render

import com.dc2f.*
import com.dc2f.assets.Asset
import java.net.*
import java.nio.file.*
import kotlin.reflect.KClass
import kotlin.reflect.full.*


abstract class Theme {

    val config: ThemeConfig by lazy {
        ThemeConfig().also { configure(it) }
    }
    abstract fun configure(config: ThemeConfig)

    internal fun <T: ContentDef> findRenderer(node: T): ThemeConfig.RenderConfig<*> =
        // for now we don't support having more than one renderer for each type.
        config.renderers.single { it.canRender(node) }

}

class AssetPipeline(
    private val context: RenderContext<*>,
    private val sourceUri: URI
) {

    private val pipeline = mutableListOf<Asset>()

    fun href(outputPath: String): String = runTransformations(outputPath)

    private fun runTransformations(outputPath: String): String {
        // we currently only support one pipeline step.. don't ask.
        val absPath = context.rootPath.resolve(outputPath)
        Files.createDirectories(absPath.parent);
        val outputUri = absPath.toUri()
//        val output = pipeline[0].transform(sourceUri.toURL().readText())
        pipeline[0].transform(sourceUri, outputUri)
//        Files.write(context.rootPath.resolve(outputPath), output.toByteArray())
        return outputPath
    }

    fun transform(asset: Asset): AssetPipeline {
        pipeline.add(asset)
        return this
    }
}


data class RenderContext<T : ContentDef>(
    val rootPath: Path,
    val node: T,
    val metadata: ContentDefMetadata,
    val theme: Theme,
    val out: Appendable
) {
    val content get() = node
    val context get() = this
    fun renderToHtml() {
        @Suppress("UNCHECKED_CAST")
        val renderer = theme.findRenderer(this.node)
            .renderer as RenderContext<T>.() -> Unit
        this.renderer()
    }

    @Suppress("UNCHECKED_CAST")
    fun <U : ContentDef>copyForNode(node: U) =
        (this  as RenderContext<U>).copy(node = node)

    fun getAsset(path: String): AssetPipeline {
        return AssetPipeline(this, theme.javaClass.classLoader.getResource(path).toURI())
    }
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















    internal val renderers = mutableListOf<RenderConfig<*>>()

    inline fun <reified T : ContentDef> pageRenderer(
//        noinline fileName: (page: LoadedContent<T>) -> Path,
        noinline renderer: RenderContext<T>.() -> Unit
    ) {
        _registerPageRenderer(
            RenderConfig(
                T::class, renderer
            )
        )
    }

    fun _registerPageRenderer(config: RenderConfig<*>) {
        renderers.add(config)
    }
}
