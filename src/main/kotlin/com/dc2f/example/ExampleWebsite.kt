package com.dc2f.example

import com.dc2f.*
import com.fasterxml.jackson.annotation.JacksonInject
import mu.KotlinLogging
import org.apache.commons.lang3.builder.*
import java.nio.file.*

private val logger = KotlinLogging.logger {}


@Nestable("page")
interface SimplePage: ContentDef {
    val title: String

    @set:JacksonInject("body")
    var body: Markdown
}

sealed class SimpleContentFolderChild: ContentDef
abstract class SimpleContentX: SimpleContentFolderChild(), SimplePage
abstract class SimpleContentY: SimpleContentFolderChild(), SimpleContentFolder

@Nestable("folder")
interface SimpleContentFolder: ContentBranchDef<SimpleContentFolderChild>

abstract class ExampleWebsite: Website<SimpleContentFolderChild>

fun main() {
    logger.info { "Starting ..." }

    val website = ContentLoader(ExampleWebsite::class).load(FileSystems.getDefault().getPath("example", "example_website"))
    logger.info { "loaded website ${website}."}
    logger.info { "reflected: ${ReflectionToStringBuilder.toString(website, MultilineRecursiveToStringStyle())}" }
}
