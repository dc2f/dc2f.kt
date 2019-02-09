package com.dc2f

import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.mrbean.MrBeanModule
import io.ktor.http.*
import mu.KotlinLogging
import org.apache.commons.lang3.builder.*
import org.reflections.Reflections
import java.nio.file.*
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.full.*
import kotlin.streams.toList


private val logger = KotlinLogging.logger {}

data class LoadedContent<T : ContentDef>(
    val content: T,
    val metadata: ContentDefMetadata
)

/**
 * ContentPath's are used to identify the location of Content. Each content
 * has a unique path which can be used for identification. It works similar
 * to a file system path.
 *
 * Use [toString] to convert to an external form. The root will always be an
 * empty string `""`, while otherwise it will be `example/path` with path segments
 * encoded and separated by `/`. (Guaranteed that it will not start or end in `/`)
 */
class ContentPath private constructor(
    /// implementation detail: We use the ktor Url internally to handle resolving, etc.
    private val url: Url
) {

    companion object {
        val root get() = ContentPath(rootBuilder.build())

        private val rootBuilder
            get() = URLBuilder(
                protocol = URLProtocol("dc2f", 8822),
                host = "content"
            )
    }

    @Suppress("unused")
    val isRoot
        get() = url.encodedPath == "/"

    private fun builder() = URLBuilder().takeFrom(url)

    fun child(pathComponent: String) = ContentPath(
        builder()
            .takeFrom(pathComponent.encodeURLQueryComponent() + "/")
            .build()
    )

    override fun toString(): String =
        url.encodedPath.trim('/')

}

class ContentDefMetadata(
    val path: ContentPath,
    val childrenMetadata: Map<ContentDef, ContentDefMetadata> = emptyMap()
)

class ContentLoader<T : ContentDef>(private val klass: KClass<T>) {

    private fun childTypesForProperty(propertyName: String): Map<String, Class<out Any>>? {
        logger.trace { "Loading childTypes for $propertyName of $klass." }
        val typeArgument = klass.members.find { it.name == propertyName }?.let { member ->
            if ((member.returnType.classifier as? KClass<*>)?.isSubclassOf(Collection::class) == true) {
                member.returnType.arguments[0].type
            } else {
                member.returnType
            }
        } ?: return null
        logger.trace { "childTypes for $propertyName: typeArgument: {$typeArgument}" }
        val childrenClass = (typeArgument.classifier as KClass<*>).java
        return (setOf(childrenClass) + Reflections("app.anlage", "com.dc2f").getSubTypesOf(
            childrenClass
        )).mapNotNull { clazz ->
            val nestable =
                clazz.kotlin.findAnnotation<Nestable>()
                    ?: clazz.kotlin.allSuperclasses.mapNotNull { it.findAnnotation<Nestable>() }
                        .firstOrNull()
            //                    val nestable = it.kotlin.findAnnotation<Nestable>()
            logger.trace { "available class: $clazz --- $nestable" }
            nestable?.identifier?.to(clazz)
        }.toMap()
    }

    fun load(dir: Path) = _load(dir, ContentPath.root)

    private fun _load(dir: Path, contentPath: ContentPath): LoadedContent<T> {
        require(Files.isDirectory(dir))
        val idxYml = dir.resolve("_index.yml")
        val module = SimpleModule()
//        module.addDeserializer(Children::class.java, ChildrenDeserializer())

        val objectMapper = ObjectMapper(YAMLFactory())
            .registerModule(MrBeanModule())
            .registerModule(module)
        val childTypes = childTypesForProperty(ContentBranchDef<T>::children.name) ?: emptyMap()
        val children = Files.list(dir)
            .filter { Files.isDirectory(it) }
            .map { child ->
                logger.trace { "Checking child $child. $childTypes" }
                val folderArgs = child.fileName.toString().split('.')
                val (sort, slug, typeIdentifier) = when {
                    folderArgs.size == 3 -> folderArgs
                    folderArgs.size == 2 -> listOf(null) + folderArgs
                    else -> listOf(null, null, null)
                }
                logger.trace { "sort: $sort, slug: $slug, typeIdentifier: $typeIdentifier" }
                when {
                    sort != null -> childTypes[typeIdentifier]?.let { type ->
                        requireNotNull(slug)
                        @Suppress("UNCHECKED_CAST")
                        ContentLoader(type.kotlin as KClass<ContentDef>)
                            ._load(child, contentPath.child(slug))
                    }?.let { "children" to it }
                    slug != null -> childTypesForProperty(slug)?.get(typeIdentifier)?.let { type ->
                        @Suppress("UNCHECKED_CAST")
                        ContentLoader(type.kotlin as KClass<ContentDef>)
                            ._load(child, contentPath.child(slug))
                    }?.let { slug to it }
                    else -> null
                }
            }.filter { it != null }.toList().filterNotNull().groupBy { it.first }.toMutableMap()
        logger.info { "Children: $children -- ${ReflectionToStringBuilder.toString(children)}" }

        val injectableValues = object : InjectableValues() {
            override fun findInjectableValue(
                valueId: Any?,
                ctxt: DeserializationContext?,
                forProperty: BeanProperty?,
                beanInstance: Any?
            ): Any? {
                require(valueId is String)
                logger.debug {
                    "Need to inject ${ReflectionToStringBuilder.toString(
                        Optional.ofNullable(
                            valueId
                        ), ToStringStyle.SHORT_PREFIX_STYLE
                    )} --- $children"
                }
                return children[valueId]?.let { child ->
                    if (valueId == "children") {
                        logger.debug("injecting children: $child.")
                        child.map { it.second.content }
                    } else {
                        child[0].second.content
                    }
                }

            }

        }
//        children.forEach { key, value -> injectableValues.addValue(key, if (key == "children") { value } else { value[0] }) }

        val propertyTypes = Reflections("com.dc2f").getTypesAnnotatedWith(PropertyType::class.java)
            .mapNotNull { it.kotlin.findAnnotation<PropertyType>()?.identifier?.to(it.kotlin) }
            .toMap()
        Files.list(dir)
            .filter { Files.isRegularFile(it) }
            .map { file ->
                val extension = file.fileName.toString().substringAfterLast('.')
                val slug = file.fileName.toString().substringBeforeLast('.')
                propertyTypes[extension]?.let { propType ->
                    val companion = propType.companionObjectInstance
                    if (companion is Parsable<*>) {
                        file to LoadedContent(
                            companion.parseContent(file),
                            ContentDefMetadata(contentPath.child(slug))
                        )
                    } else {
                        null
                    }
                }
            }.forEach { pair ->
                if (pair?.second != null) {
                    val key = pair.first.fileName.toString().substringBefore('.')
//                    injectableValues.addValue(key, pair.second)
                    children[key] = listOf(key to pair.second)
                }
            }

        val tree = objectMapper.readTree(Files.readAllBytes(idxYml))
        logger.info { "tree: $tree" }

        val obj = objectMapper.reader(injectableValues)
            .forType(klass.java)
            .readValue<T>(tree)
        return LoadedContent(obj, ContentDefMetadata(
            contentPath,
            children.values
                .flatten()
                .map {
                    it.second.metadata.childrenMetadata.entries.toPairs() +
                        setOf(it.second.content to it.second.metadata)
                }
                .flatten()
                .toMap()
        ))
    }
}

fun <K, V> Iterable<Map.Entry<K, V>>.toPairs() = map { it.toPair() }
