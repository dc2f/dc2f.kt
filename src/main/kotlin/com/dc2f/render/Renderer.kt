package com.dc2f.render

import com.dc2f.*
import com.dc2f.util.*
import io.ktor.http.*
import mu.KotlinLogging
import java.io.StringWriter
import java.nio.file.*


private val logger = KotlinLogging.logger {}

/**
 * Path which is used during rendering to decide where to write content in the file system.
 */
class RenderPath private constructor(url: Url) : AbstractPath<RenderPath>(RenderPath.Companion, url) {

    companion object : AbstractPathCompanion<RenderPath>() {
        override val construct get() = ::RenderPath
    }
}

/**
 * Path used to reference content from OUTSIDE. ie. which will be used in links.
 */
class UriReferencePath private constructor(url: Url) : AbstractPath<UriReferencePath>(UriReferencePath.Companion, url) {
    companion object : AbstractPathCompanion<UriReferencePath>() {
        fun fromRenderPath(renderPath: RenderPath) =
            renderPath.transform(this)

        override val construct: (url: Url) -> UriReferencePath
            get() = ::UriReferencePath
    }

    fun absoluteUrl(config: UrlConfig) =
        url.copy(protocol = config.urlProtocol, host = config.host).toString()
}

class UrlConfig(
    val protocol: String = "https",
    val host: String = "example.org"
) {

    val urlProtocol: URLProtocol by lazy {
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
        logger.info { "cache Stats: ${loaderContext.cache.allStatistics}" }
        logger.info { Timing.allToString() }
    }

    fun findRenderPath(node: ContentDef): RenderPath {
        (node as? WithRenderPathOverride)
            ?.renderPath(this)
            ?.let {
                return it
            }

        val contentPath = loaderContext.findContentPath(node)
        if (contentPath.isRoot) {
            return RenderPath.root
        }
        val parentContent = requireNotNull(loaderContext.contentByPath[contentPath.parent()])
        val parentPath = findRenderPath(parentContent)

        if ((parentContent as? WithRenderAlias)?.renderAlias() == node) {
            return parentPath
        }

        val slug = (node as? SlugCustomization)?.createSlug()
            ?:contentPath.name
        return parentPath.child(slug)
    }

    fun findUriReferencePath(node: ContentDef): UriReferencePath {
        (node as? WithUriReferencePathOverride)
            ?.uriReferencePath(this)
            ?.let {
                return it
            }
        return UriReferencePath.fromRenderPath(findRenderPath(node))
    }

    fun renderContent(node: ContentDef, metadata: ContentDefMetadata, previousContext: RenderContext<*>? = null) {
        val renderPath = findRenderPath(node)
        val renderPathFile = if (renderPath.isLeaf) {
            renderPath
        } else {
            renderPath.childLeaf("index.html")
        }

        val dir = target.resolve(renderPathFile.parent().toString())
        Files.createDirectories(dir)
        try {
            LazyFileRenderOutput(dir.resolve(renderPathFile.name)).use { writer ->
                RenderContext(
                    rootPath = previousContext?.rootPath ?: dir,
                    node = node,
                    metadata = previousContext?.metadata ?: metadata,
                    theme = theme,
                    out = writer,
                    renderer = this
                ).renderToHtml()
            }
        } catch (e: Throwable) {
            throw Exception("Error while rendering into $renderPath: ${e.message}", e)
        }
    }

    fun renderPartialContent(node: ContentDef, metadata: ContentDefMetadata, previousContext: RenderContext<*>): String {
        val writer = StringWriter()
        RenderContext(
            rootPath = previousContext.rootPath,
            node = node,
            metadata = previousContext.metadata,
            theme = theme,
            out = AppendableOutput(writer),
            renderer = this,
            enclosingNode = previousContext.node
        ).renderToHtml()
        return writer.toString()
    }

    private fun absoluteUrl(path: UriReferencePath) =
        path.absoluteUrl(urlConfig)

//    /** absolute url https://example.org/path/ */
//    fun absoluteUrl(page: ContentDef) =
//        absoluteUrl(findUriReferencePath(page))

    fun href(page: ContentDef, absoluteUrl: Boolean): String =
        href(findUriReferencePath(page), absoluteUrl)
//        (page as? WithRenderPathOverride)?.renderPath(this)?.let { href(it, absoluteUrl) }
//            ?: absoluteUrl.then { absoluteUrl(page) }
//            ?: when (val path = findRenderPath(page)) {
//                RenderPath.root -> "/"
//                else -> "/$path/"

    fun href(uriReferencePath: UriReferencePath, absolute: Boolean): String =
        absolute.then { absoluteUrl(uriReferencePath) }
            ?: when (uriReferencePath) {
                UriReferencePath.root -> "/"
                else -> "/$uriReferencePath/"
            }
}