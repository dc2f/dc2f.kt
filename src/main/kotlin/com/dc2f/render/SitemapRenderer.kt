package com.dc2f.render

import com.dc2f.*
import com.dc2f.git.CommitInfo
import com.fasterxml.jackson.annotation.JacksonInject
import com.redfin.sitemapgenerator.*
import io.ktor.http.URLBuilder
import java.nio.file.Path
import java.util.*

interface WithSitemapInfo {
    @JvmDefault
    fun includeInSitemap() = true

    @set:JacksonInject
    var commitInfo: CommitInfo?
}

/**
 * TODO somehow combine this with the [com.dc2f.render.Renderer]?
 */
class SitemapRenderer(
    private val target: Path,
    val loaderContext: LoaderContext,
    val renderer: Renderer,
    val urlConfig: UrlConfig
) {

    fun render() {
        val url = URLBuilder(
            protocol = urlConfig.urlProtocol,
            host = urlConfig.host
        ).build()
        val sitemap = WebSitemapGenerator(url.toString(), target.toFile())
        renderRecursive(loaderContext.rootNode, sitemap)
        sitemap.write()//toFile(target.resolve("sitemap.xml"))
    }

    private fun renderRecursive(node: ContentDef, sitemap: WebSitemapGenerator) {
        if (node is WithSitemapInfo && node.includeInSitemap()) {
            sitemap.addUrl(
                WebSitemapUrl(
                    WebSitemapUrl.Options(renderer.href(node, true))
                        .lastMod(
                            // TODO for now simply take the current date as modification date, if there
                            //      is no git information. We maybe should change this to the file system file,
                            //      or a fallback property defined by the user.
                            node.commitInfo?.authorDate?.toInstant()?.let(Date::from) ?: Date())
                )
            )
        }
        if (node is ContentBranchDef<*>) {
            node.children.forEach {
                renderRecursive(it, sitemap)
            }
        }
    }

}

