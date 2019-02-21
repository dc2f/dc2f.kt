package com.dc2f.render

import com.dc2f.*
import com.dc2f.util.*
import io.ktor.http.*
import mu.KotlinLogging
import java.io.StringWriter
import java.nio.file.*


private val logger = KotlinLogging.logger {}

class RenderPath private constructor(url: Url) : AbstractPath<RenderPath>(RenderPath.Companion, url) {

    companion object : AbstractPathCompanion<RenderPath>() {
        override val construct get() = ::RenderPath
    }

    fun absoluteUrl(config: UrlConfig) =
        url.copy(protocol = config.urlProtocol, host = config.host).toString()
}

class UrlConfig(
    val protocol: String = "https",
    val host: String = "example.org"
) {

    internal val urlProtocol: URLProtocol by lazy {
        URLProtocol.createOrDefault(protocol)
    }
}


class Renderer(
    private val theme: Theme,
    private val target: Path,
    val loaderContext: LoaderContext,
    val urlConfig: UrlConfig
) {

    val clearTiming = Timing("clear")
    val renderTiming = Timing("render")

    private fun clear() {
        if (!Files.exists(target)) {
            return;
        }
        Files.walk(target)
            .sorted(Comparator.reverseOrder())
//            .map(Path::toFile)
            .peek { logger.debug { "Deleting $it" }}
            .forEach(Files::delete);
    }

    fun renderWebsite(node: ContentDef, metadata: ContentDefMetadata) {
        // first clear target directory
        clearTiming.measure {
            clear()
        }
        renderTiming.measure {
            renderContent(node, metadata)
        }
        logger.info { "Finished rendering." }
        logger.info { Timing.allTimings.joinToString(System.lineSeparator() + "    ", prefix = "${System.lineSeparator()}    ") { it.toString() } }
        logger.info { "cache Stats: ${CacheUtil.allStatistics}" }
        CacheUtil.cacheManager.close()
    }

    fun findRenderPath(node: ContentDef): RenderPath {
        val contentPath = loaderContext.findContentPath(node)
        if (contentPath.isRoot) {
            return RenderPath.root
        }
        val parent = findRenderPath(requireNotNull(loaderContext.contentByPath[contentPath.parent()]))

        val slug = (node as? SlugCustomization)?.createSlug()
            ?:contentPath.name
        return parent.child(slug)
    }

    fun renderContent(node: ContentDef, metadata: ContentDefMetadata, previousContext: RenderContext<*>? = null) {
        val renderPath = findRenderPath(node)
        val dir = target.resolve(renderPath.toString())
        Files.createDirectories(dir)
        Files.newBufferedWriter(dir.resolve("index.html")).use { writer ->
            RenderContext(
                rootPath = previousContext?.rootPath ?: dir,
                node = node,
                metadata = previousContext?.metadata ?: metadata,
                theme = theme,
                out = writer,
                renderer = this
            ).renderToHtml()
        }
    }

    fun renderPartialContent(node: ContentDef, metadata: ContentDefMetadata, previousContext: RenderContext<*>): String {
        val writer = StringWriter()
        RenderContext(
            rootPath = previousContext.rootPath,
            node = node,
            metadata = previousContext.metadata,
            theme = theme,
            out = writer,
            renderer = this,
            enclosingNode = previousContext.node
        ).renderToHtml()
        return writer.toString()
    }

    fun absoluteUrl(path: RenderPath) =
        path.absoluteUrl(urlConfig)
}