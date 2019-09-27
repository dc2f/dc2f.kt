package com.dc2f.render

import com.dc2f.*
import com.dc2f.util.*
import com.fasterxml.jackson.annotation.JsonIgnore
import io.ktor.http.*
import kotlinx.html.*
import kotlinx.html.stream.appendHTML
import mu.KotlinLogging
import java.io.StringWriter
import java.nio.file.*


private val logger = KotlinLogging.logger {}

enum class RenderPathType {
    Content,
    StaticAsset
}

/**
 * Path which is used during rendering to decide where to write content in the file system.
 */
class RenderPath private constructor(url: Url, val type: RenderPathType = RenderPathType.Content) :

    AbstractPath<RenderPath>(RenderPath.Companion, url) {

    companion object : AbstractPathCompanion<RenderPath>() {
        override val construct get() = { url: Url -> RenderPath(url) }
    }

    fun asType(type: RenderPathType) = RenderPath(url, type)
}

/**
 * Path used to reference content from OUTSIDE. ie. which will be used in links.
 */
class UriReferencePath private constructor(url: Url, private val urlConfig: UrlConfig) :
    AbstractPath<UriReferencePath>(UriReferencePath.Companion, url) {
    companion object : AbstractPathCompanion<UriReferencePath>() {
        fun fromRenderPath(renderPath: RenderPath, urlConfig: UrlConfig) =
            renderPath.transform(this) {
                when (renderPath.type) {
                    RenderPathType.Content -> UriReferencePath(it.copy(encodedPath = urlConfig.pathPrefixWithoutTrailingSlash + it.encodedPath), urlConfig)
                    RenderPathType.StaticAsset -> UriReferencePath(it.copy(encodedPath = urlConfig.staticFilesPrefixWithoutTrailingSlash + it.encodedPath), urlConfig)
                }
            }

        override val construct: (url: Url) -> UriReferencePath
            get() = throw UnsupportedOperationException("UriReferencePath's cannot be created without a urlConfig.")
    }

    fun absoluteUrl(config: UrlConfig = urlConfig) =
        url.copy(
            protocol = config.urlProtocol,
            host = config.host,
            encodedPath = config.pathPrefix + url.encodedPath
        ).toString()

    fun absoluteUrlWithoutHost(config: UrlConfig = urlConfig) =
        when (isRoot) {
            true -> "/"
            false -> "/${toStringExternal()}"
        }
}

open class UrlConfig(
    open val protocol: String = "https",
    open val host: String = "example.org",
    /**
     * Optionally a path prefix which is used to prefix all urls.
     * If defined, must not begin with /, but must end with '/'
     * e.g. `example/`
     */
    open val pathPrefix: String = ""
) : ContentDef {

    @get:JsonIgnore
    open val pathPrefixWithoutTrailingSlash get() = pathPrefix.trimEnd('/')

    @get:JsonIgnore
    open val staticFilesPrefix get() = pathPrefix

    @get:JsonIgnore
    open val staticFilesPrefixWithoutTrailingSlash get() = staticFilesPrefix.trimEnd('/')

    @get:JsonIgnore
    open val urlProtocol: URLProtocol by lazy {
        URLProtocol.createOrDefault(protocol)
    }
}

abstract class Renderer(val loaderContext: LoaderContext, val urlConfig: UrlConfig) {
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

        if ((parentContent as? WithContentSymlink)?.contentSymlink() == node) {
            return parentPath
        }

        val slug = (node as? SlugCustomization)?.createSlug()
            ?: contentPath.name
        return parentPath.child(slug)
    }

    fun findUriReferencePath(node: ContentDef): UriReferencePath {
        (node as? WithUriReferencePathOverride)
            ?.uriReferencePath(this)
            ?.let {
                return it
            }
        return UriReferencePath.fromRenderPath(findRenderPath(node), urlConfig)
    }

    protected fun absoluteUrl(path: UriReferencePath) =
        path.absoluteUrl(urlConfig)

    fun href(renderPath: RenderPath, absolute: Boolean = false) = href(UriReferencePath.fromRenderPath(renderPath, urlConfig), absolute)

    fun href(page: ContentDef, absoluteUrl: Boolean): String =
        href(findUriReferencePath(page), absoluteUrl)

    fun href(uriReferencePath: UriReferencePath, absolute: Boolean): String =
        absolute.then { absoluteUrl(uriReferencePath) }
            ?: uriReferencePath.absoluteUrlWithoutHost(urlConfig)

    abstract fun renderContent(
        node: ContentDef,
        metadata: ContentDefMetadata,
        previousContext: RenderContext<*>? = null,
        forOutputType: OutputType
    )


    fun renderPartialContent(
        node: ContentDef,
        metadata: ContentDefMetadata,
        previousContext: RenderContext<*>
    ): String {
        val writer = StringWriter()

        previousContext.createSubContext(
            node = node,
            out = AppendableOutput(writer),
            enclosingNode = previousContext.node
        ).render()

        return writer.toString()
    }
}

class SinglePageStreamRenderer(
    private val theme: Theme,
    loaderContext: LoaderContext,
    urlConfig: UrlConfig,
    private val renderOutput: RenderOutput,
    private val resourcesOutputPath: Path
) : Renderer(loaderContext, urlConfig) {

    override fun renderContent(
        node: ContentDef,
        metadata: ContentDefMetadata,
        previousContext: RenderContext<*>?,
        forOutputType: OutputType
    ) {
    }

    fun renderRootContent(
        node: ContentDef,
        metadata: ContentDefMetadata,
        forOutputType: OutputType
    ) {
        InMemoryRenderContext(
            BaseRenderContextData(
                node = node,
                metadata = metadata,
                theme = theme,
                out = renderOutput,
                renderer = this,
                forOutputType = forOutputType
            ),
            resourcesOutputPath
        ).render()
    }

}


class FileOutputRenderer(
    private val theme: Theme,
    private val target: Path,
    loaderContext: LoaderContext,
    urlConfig: UrlConfig
) : Renderer(loaderContext, urlConfig) {

    val clearTiming = Timing("clear")
    val renderTiming = Timing("render")

    private fun clear() {
        if (!Files.exists(target)) {
            return
        }
        Files.walk(target)
            .sorted(Comparator.reverseOrder())
//            .map(Path::toFile)
            .peek { logger.debug { "Deleting $it" } }
            .forEach(Files::delete);
    }

    fun renderWebsite(node: ContentDef, metadata: ContentDefMetadata) {
        // first clear target directory
        clearTiming.measure {
            clear()
        }
        renderTiming.measure {
            renderContent(node, metadata, forOutputType = OutputType.html)
            renderContent(node, metadata, forOutputType = OutputType.robotsTxt)
        }
        logger.info { "Finished rendering." }
        logger.info { "cache Stats: ${loaderContext.cache.allStatistics}" }
        logger.info { Timing.allToString() }
    }


    override fun renderContent(
        node: ContentDef,
        metadata: ContentDefMetadata,
        previousContext: RenderContext<*>?,
        forOutputType: OutputType
    ) {
        val renderPath = findRenderPath(node)
        val renderPathFile = forOutputType.fileForRenderPath(renderPath)

        val dir = target.resolve(renderPathFile.parent().toString())
        Files.createDirectories(dir)
        try {
            LazyFileRenderOutput(dir.resolve(renderPathFile.name)).use { writer ->
                val previousFileContext = previousContext as? FileRenderContext
                FileRenderContext(
                    BaseRenderContextData(
                        node = node,
                        metadata = previousContext?.metadata ?: metadata,
                        theme = theme,
                        out = writer,
                        renderer = this,
                        forOutputType = forOutputType
                    ),
                    rootPath = previousFileContext?.rootPath ?: dir
                ).render()
            }

            (node as? WithRenderPathAliases)?.renderPathAliases(this)?.let {
                writeRenderPathAliases(node, it)
            }
        } catch (e: Throwable) {
            throw Exception("Error while rendering into $renderPath: ${e.message}", e)
        }
    }

    private fun <T> writeRenderPathAliases(
        node: T,
        renderPathAliases: List<RenderPath>
    ) where T : ContentDef {
        val targetRenderPath = findRenderPath(node)
        val targetUrl = absoluteUrl(UriReferencePath.fromRenderPath(targetRenderPath, urlConfig))
        renderPathAliases.forEach { alias ->
            val renderPathFile = alias.childLeaf("index.html")
            val dir = target.resolve(renderPathFile.parent().toString())
            Files.createDirectories(dir)
            Files.newBufferedWriter(dir.resolve(renderPathFile.name)).use { writer ->
                writer.appendHTML(prettyPrint = false).html {
                    head {
                        link(href = targetUrl) {
                            rel = "canonical"
                        }
                        meta("robots", "noindex")
                        meta(charset = "utf-8")
                        meta {
                            httpEquiv = "refresh"
                            content = "0; url=$targetUrl"
                        }
                    }
                }
            }
        }
    }


}