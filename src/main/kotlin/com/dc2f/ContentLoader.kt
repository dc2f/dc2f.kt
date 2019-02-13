package com.dc2f

import com.dc2f.richtext.markdown.ValidationRequired
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.fasterxml.jackson.module.mrbean.MrBeanModule
import io.ktor.http.*
import mu.KotlinLogging
import org.apache.commons.lang3.builder.*
import org.reflections.Reflections
import java.nio.file.*
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.full.*
import kotlin.reflect.jvm.*
import kotlin.streams.toList


private val logger = KotlinLogging.logger {}

data class LoadedContent<T : ContentDef>(
    val context: LoaderContext,
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

        private fun fromPathComponents(pathComponents: Iterable<String>) =
            ContentPath(rootBuilder
                .takeFrom(
                    pathComponents.joinToString("/") {
                        it.encodeURLQueryComponent()
                    } + "/")
                .build()
            )

        fun parse(path: String): ContentPath =
            fromPathComponents(path.trim('/').split('/'))
    }

    init {
        assert(url.encodedPath.endsWith('/'))
    }

    @Suppress("unused")
    val isRoot
        get() = url.encodedPath == "/"

    private fun builder() = URLBuilder().takeFrom(url)

    private val pathComponents =
        toString().split('/').map { it.decodeURLQueryComponent() }

    fun parent() =
        fromPathComponents(pathComponents.dropLast(1))

    fun child(pathComponent: String) = ContentPath(
        builder()
            .takeFrom(pathComponent.encodeURLQueryComponent() + "/")
            .build()
    )

    fun sibling(pathComponent: String) = parent().child(pathComponent)

    override fun toString(): String =
        url.encodedPath.trim('/')

    override fun equals(other: Any?): Boolean {
        if (other is ContentPath) {
            return other.url == url
        }
        return super.equals(other)
    }

    override fun hashCode(): Int {
        return url.hashCode()
    }

}

class ContentDefMetadata(
    val path: ContentPath,
    val childrenMetadata: Map<ContentDef, ContentDefMetadata> = emptyMap()
)

data class LoaderContext(
    val root: Path
) {

    enum class LoaderPhase {
        Loading,
        Validating,
        Finished,
        ;

        fun isAfter(before: LoaderPhase) =
            (ordinal > before.ordinal)
    }

    var phase: LoaderPhase = LoaderPhase.Loading
        private set

    private val contentByPathMutable = mutableMapOf<ContentPath, ContentDef>()
    val contentByPath get(): Map<ContentPath, ContentDef> = contentByPathMutable
    private val validatorsCollector: MutableList<ValidationRequired> = mutableListOf()
    val validators get(): List<ValidationRequired> = validatorsCollector
    val registeredContent = mutableSetOf<ContentDef>()
    private lateinit var metadataMap: Map<ContentDef, ContentDefMetadata>

    fun <T: ContentDef>registerLoadedContent(content: LoadedContent<T>) {
        contentByPathMutable[content.metadata.path] = content.content
        registerContentDef(content, content.content)
    }

    fun <T: ContentDef> registerContentDef(parent: LoadedContent<T>, content: ContentDef) {
        if (registeredContent.add(content)) {
            if (content is ValidationRequired) {
                validatorsCollector.add(object : ValidationRequired {
                    override fun validate(loaderContext: LoaderContext): String? =
                        content.validate(loaderContext)?.let { "${parent.metadata.path}: $it" }
                })
            }
        }
    }

    internal fun finishedLoadingStartValidate(metadataMap: Map<ContentDef, ContentDefMetadata>) {
        this.metadataMap = metadataMap
        phase = LoaderPhase.Validating
        validators.mapNotNull { it.validate(this) }
            .also { errors ->
                if (errors.isNotEmpty()) {
                    throw IllegalArgumentException("Error validating content. $errors")
                }
            }
        phase = LoaderPhase.Finished
    }
}

/**
 * Can be requested by deserializers through [InjectableValues].
 */
data class ContentLoaderDeserializeContext(
    val root: Path,
    val currentFsPath: Path,
    val currentContentPath: ContentPath
) {
    fun resolveContentPath(path: ContentPath) =
        root.resolve(path.toString())
}

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

    fun load(dir: Path) =
        _load(LoaderContext(dir), dir, ContentPath.root)
            .also { c ->
                c.context.finishedLoadingStartValidate(c.metadata.childrenMetadata)
            }

    private fun _load(context: LoaderContext, dir: Path, contentPath: ContentPath): LoadedContent<T> {
        require(Files.isDirectory(dir))
        val idxYml = dir.resolve("_index.yml")
        val module = SimpleModule()
//        module.addDeserializer(Children::class.java, ChildrenDeserializer())

        module.addDeserializer(
            ImageAsset::class.java,
            FileAssetDeserializer(ImageAsset::class.java, ::ImageAsset)
        )
        module.addDeserializer(
            FileAsset::class.java,
            FileAssetDeserializer(FileAsset::class.java, ::FileAsset)
        )

        val objectMapper = ObjectMapper(YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, true)
            .registerKotlinModule()
            .registerModule(JavaTimeModule())
            .registerModule(MrBeanModule())
            .registerModule(module)
        val childTypes = childTypesForProperty(ContentBranchDef<T>::children.name) ?: emptyMap()
        val children = Files.list(dir)
            .filter { Files.isDirectory(it) }
            .sorted()
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
                            ._load(context, child, contentPath.child(slug))
                    }?.let { "children" to it }
                    slug != null -> childTypesForProperty(slug)?.get(typeIdentifier)?.let { type ->
                        @Suppress("UNCHECKED_CAST")
                        ContentLoader(type.kotlin as KClass<ContentDef>)
                            ._load(context, child, contentPath.child(slug))
                    }?.let { slug to it }
                    else -> null
                }?.also { context.registerLoadedContent(it.second) }
            }.filter { it != null }.toList().filterNotNull().groupBy { it.first }.toMutableMap()
        logger.info { "Children: $children -- ${ReflectionToStringBuilder.toString(children)}" }

        val injectableValues = object : InjectableValues() {
            override fun findInjectableValue(
                valueId: Any?,
                ctxt: DeserializationContext?,
                forProperty: BeanProperty?,
                beanInstance: Any?
            ): Any? {
                if (valueId == ContentLoaderDeserializeContext::class.java) {
                    return ContentLoaderDeserializeContext(context.root, dir, contentPath)
                }
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
                        val childPath = contentPath.child(slug)
                        file to LoadedContent(
                            context,
                            companion.parseContent(context, file, childPath),
                            ContentDefMetadata(childPath)
                        ).also(context::registerLoadedContent)
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

        // Make _index.yml optional, if there are no required (non nestable) attributes.
        val fileContent = if (Files.exists(idxYml)) {
            Files.readAllBytes(idxYml)
        } else {
            "{}".toByteArray()
        }
        val tree = objectMapper.readTree(fileContent)
        logger.info { "tree: $tree" }

        val obj = try {
            objectMapper.reader(injectableValues)
                .forType(klass.java)
                .readValue<T>(tree)
        } catch (e: Throwable) {
            throw Exception("Error while parsing $idxYml: ${e.message}", e)
        }
        return LoadedContent(context, obj, ContentDefMetadata(
            contentPath,
            children.values
                .flatten()
                .map {
                    it.second.metadata.childrenMetadata.entries.toPairs() +
                        setOf(it.second.content to it.second.metadata)
                }
                .flatten()
                .toMap()
        )).also { context.registerLoadedContent(it) }.also {
            try {
                validateContentDef(context, it, it.content)
            } catch (e: Throwable) {
                throw IllegalArgumentException("Error while checking $contentPath: ${e.message}", e)
            }
        }
    }

    private fun<T: ContentDef> validateBeanIfRequired(context: LoaderContext, parent: LoadedContent<T>, content: Any?) {
        if (content is ContentDef) {
            validateContentDef(context, parent, content)
        } else if (content is Collection<*>) {
            content.forEach {
                validateBeanIfRequired(context, parent, it)
            }
        }
    }

    private fun<T: ContentDef> validateContentDef(context: LoaderContext, parent: LoadedContent<T>, content: ContentDef) {
        context.registerContentDef(parent, content)

        content.javaClass.kotlin.memberProperties.forEach {
            if (it.returnType.toString().endsWith('!')) {
                // veeeery hackish.. we ignore java-types and only care about kotlin types.
                // There must be some better way to figure it out, but i haven't found it yet.
                return@forEach
            }
            if (it.isLateinit) {
                logger.trace { "Ignoring lateinit property $it" }
                return@forEach
            }
            if (!it.isAccessible) {
//                logger.warn { "Can't access property $it" }
                it.isAccessible = true
//                return@forEach
            }
            val x = it.get(content)
            if (x == null) {
                if (!it.returnType.isMarkedNullable) {
                    throw IllegalArgumentException("Property ${it.name} is null (of ${content.javaClass.simpleName}).")
                }
            } else {
                validateBeanIfRequired(context, parent, x)
            }
        }
    }
}

fun <K, V> Iterable<Map.Entry<K, V>>.toPairs() = map { it.toPair() }
