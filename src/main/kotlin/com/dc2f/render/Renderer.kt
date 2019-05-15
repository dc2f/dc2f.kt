package com.dc2f.render

import com.dc2f.*
import com.dc2f.util.*
import io.ktor.http.*
import kotlinx.html.*
import kotlinx.html.stream.appendHTML
import mu.KotlinLogging
import java.io.StringWriter
import java.nio.file.*


private val logger = KotlinLogging.logger {}

/**
 * Path which is used during rendering to decide where to write content in the file system.
 */
class RenderPath private constructor(url: Url) :
    AbstractPath<RenderPath>(RenderPath.Companion, url) {

    companion object : AbstractPathCompanion<RenderPath>() {
        override val construct get() = ::RenderPath
    }
}

/**
 * Path used to reference content from OUTSIDE. ie. which will be used in links.
 */
class UriReferencePath private constructor(url: Url) :
    AbstractPath<UriReferencePath>(UriReferencePath.Companion, url) {
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
        return UriReferencePath.fromRenderPath(findRenderPath(node))
    }

    protected fun absoluteUrl(path: UriReferencePath) =
        path.absoluteUrl(urlConfig)

    fun href(page: ContentDef, absoluteUrl: Boolean): String =
        href(findUriReferencePath(page), absoluteUrl)

    fun href(uriReferencePath: UriReferencePath, absolute: Boolean): String =
        absolute.then { absoluteUrl(uriReferencePath) }
            ?: when (uriReferencePath) {
                UriReferencePath.root -> "/"
                else -> "/$uriReferencePath/"
            }

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
    private val renderOutput: RenderOutput
) : Renderer(loaderContext, urlConfig) {

    override fun renderContent(
        node: ContentDef,
        metadata: ContentDefMetadata,
        previousContext: RenderContext<*>?,
        forOutputType: OutputType
    ) {
        BaseRenderContext(
            BaseRenderContextData(
                node = node,
                metadata = previousContext?.metadata ?: metadata,
                theme = theme,
                out = renderOutput,
                renderer = this,
                forOutputType = forOutputType
            )
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
        val targetUrl = absoluteUrl(UriReferencePath.fromRenderPath(targetRenderPath))
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