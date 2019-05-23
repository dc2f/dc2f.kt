package com.dc2f.util

import com.dc2f.*
import com.dc2f.render.*
import java.nio.file.*
import kotlin.reflect.KClass

interface Dc2fSetup<ROOT_CONTENT : Website<*>> {
    val rootContent: KClass<ROOT_CONTENT>
    fun urlConfig(rootConfig: ROOT_CONTENT): UrlConfig
    val theme: Theme

    fun <T> loadWebsite(
        rootPath: String,
        cb: (website: LoadedContent<ROOT_CONTENT>, context: LoaderContext) -> T
    ) =
        ContentLoader(rootContent)
            .load(FileSystems.getDefault().getPath(rootPath)) { loadedWebsite, context ->
                cb(loadedWebsite, context)
            }

    fun renderToPath(
        targetPath: Path,
        loadedWebsite: LoadedContent<ROOT_CONTENT>,
        context: LoaderContext,
        cb: ((renderer: Renderer) -> Unit)? = null
    ) {
        FileOutputRenderer(
            theme,
            targetPath,
            loadedWebsite.context,
            urlConfig = urlConfig(loadedWebsite.content)
        ).let { renderer ->
            renderer.renderWebsite(loadedWebsite.content, loadedWebsite.metadata)
            SitemapRenderer(
                targetPath,
                loadedWebsite.context,
                renderer,
                urlConfig(loadedWebsite.content)
            )
                .render()
            if (cb != null) {
                cb(renderer)
            }
        }
    }
}
