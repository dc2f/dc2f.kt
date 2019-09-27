package com.dc2f

import com.dc2f.git.*
import com.dc2f.loader.*
import com.dc2f.richtext.markdown.ValidationRequired
import com.dc2f.util.*
import com.fasterxml.jackson.annotation.JacksonInject
import com.fasterxml.jackson.core.Version
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.introspect.*
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.fasterxml.jackson.module.mrbean.MrBeanModule
import io.ktor.http.*
import kotlinx.io.core.Closeable
import mu.KotlinLogging
import net.sf.cglib.proxy.*
import org.apache.commons.lang3.builder.*
import org.reflections.Reflections
import java.lang.reflect.Method
import java.nio.file.*
import java.time.ZonedDateTime
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.full.*
import kotlin.reflect.jvm.javaSetter
import kotlin.streams.toList

val PROPERTY_CHILDREN = ContentBranchDef<*>::children.name
val INDEX_YAML_NAME = "_index.yml"

private val logger = KotlinLogging.logger {}

data class LoadedContent<T : ObjectDef>(
    val context: LoaderContext,
    val content: T,
    val metadata: ContentDefMetadata
)

abstract class AbstractPathCompanion<T: AbstractPath<T>> {

    abstract val construct: (url: Url) -> T

    val root get() = construct(rootBuilder.build())

    private val rootBuilder
        get() = URLBuilder(
            protocol = URLProtocol("dc2f", 8822),
            host = "content"
        )

    fun parse(path: String): T =
        // TODO should we use [String.decodedPathComponents] instead of [split]?
        fromPathComponents(path.trim('/').split('/'))

    /**
     *  a variant of [parse] which generates a "leaf" path if the string
     *  does not end in a `/`.
     *  (If it does not end in `/`, requires that the last path component
     *  contains a `.`)
     */
    fun parseLeafPath(path: String): T =
        path.endsWith('/').then { parse(path) }
            ?: path.decodedPathComponents.run {
                require(last().contains('.'))
                fromPathComponents(dropLast(1))
                    .childLeaf(last())
            }

    internal fun fromPathComponents(pathComponents: Iterable<String>) =
        construct(rootBuilder
            .takeFrom(
                pathComponents.joinToString("/") {
                    it.encodeURLQueryComponent()
                } + "/")
            .build()
        )
}

open class AbstractPath<T: AbstractPath<T>>
    protected constructor(
        private val companion: AbstractPathCompanion<T>,
    /// implementation detail: We use the ktor Url internally to handle resolving, etc.
        protected val url: Url) {

    init {
        assert(url.encodedPath.endsWith('/'))
    }

    @Suppress("unused")
    val isRoot
        get() = url.encodedPath == "/"

    val name get() = pathComponents.last()

    private fun builder() = URLBuilder().takeFrom(url)

    private val pathComponents get() =
        toString().decodedPathComponents

    fun parent() =
        companion.fromPathComponents(pathComponents.dropLast(1))

    fun child(pathComponent: String) = companion.construct(
        builder()
            .takeFrom(pathComponent.encodeURLQueryComponent() + "/")
            .build()
    )

    /**
     * Files/Leafs will not have a "/" rendered at the end.
     */
    fun childLeaf(pathComponent: String) = companion.construct(
        builder()
            .takeFrom(pathComponent.encodeURLQueryComponent())
            .build()
    )

    val isLeaf get() = !isRoot && !url.encodedPath.endsWith('/')

    fun <OTHER: AbstractPath<OTHER>, T: AbstractPathCompanion<OTHER>> transform(otherCompanion: T, construct: (url: Url) -> OTHER = otherCompanion.construct) =
        construct(url)

    fun sibling(pathComponent: String) = parent().child(pathComponent)

    override fun toString(): String =
        url.encodedPath.trim('/')

    /**
     * Same as [toString], but will not remove trailing `/` for non-leaf links.
     */
    fun toStringExternal() : String = url.encodedPath.trimStart('/')

    override fun equals(other: Any?): Boolean {
        if (other is AbstractPath<*>) {
            return other.url == url
        }
        return super.equals(other)
    }

    override fun hashCode(): Int {
        return url.hashCode()
    }

    fun resolve(relativePath: String): ContentPath {
        if (relativePath.startsWith('/')) {
            return ContentPath.parse(relativePath)
        }
        return ContentPath.fromPathComponents((pathComponents +
            relativePath.decodedPathComponents)
            .fold(listOf<String>()) { acc, item ->
                acc.let {
                    when(item) {
                        "." -> acc
                        ".." -> acc.dropLast(1)
                        else -> acc + item
                    }
                }
            })
    }

    fun startsWith(child: T): Boolean = url.encodedPath.startsWith(child.url.encodedPath)

    /**
     * Calculates the "distance" between the child `this` and the `parent`.
     * (It can be safely used to order content based on the hierarchy, but for nothing else.
     * Technically it is just the difference of number in characters in the content path.)
     *
     * If `this` is not a child of `parent`, will return null. if `parent` == `child`, will be 0,
     * otherwise > 0
     */
    fun subPathDistance(parent: T): Int? = startsWith(parent).then {
        this.url.encodedPath.length - parent.url.encodedPath.length
    }

}

/**
 * ContentPath's are used to identify the location of Content. Each content
 * has a unique path which can be used for identification. It works similar
 * to a file system path.
 *
 * Use [toString] to convert to an external form. The root will always be an
 * empty string `""`, while otherwise it will be `example/path` with path segments
 * encoded and separated by `/`. (Guaranteed that it will not start or end in `/`)
 */
open class ContentPath protected constructor(
    /// implementation detail: We use the ktor Url internally to handle resolving, etc.
    url: Url
) : AbstractPath<ContentPath>(Companion, url) {

    companion object : AbstractPathCompanion<ContentPath>() {
        override val construct = ::ContentPath
    }

}

private val String.decodedPathComponents
    get() = split('/').map { it.decodeURLQueryComponent() }

class ContentDefMetadata(
    val path: ContentPath,
    val childrenMetadata: Map<ObjectDef, ContentDefMetadata> = emptyMap(),
    // If the content is the root object of a file (_index.yml), this will contain the path to it.
    val fsPath: Path?,
    val directChildren: Map<String, List<ContentDefChild<*>>>,
    val contentDefClass: KClass<out ContentDef>? = null,
    /**
     * The "comment" part of the file name, if any.
     */
    val comment: String?
) {
    override fun toString(): String {
        return "ContentDefMetadata(path=$path, childrenMetadata=$childrenMetadata, fsPath=$fsPath, directChildren=$directChildren, contentDefClass=$contentDefClass, comment=$comment)"
    }
}

data class LoaderContext(
    val root: Path
) : Closeable {

    enum class LoaderPhase {
        Loading,
        Validating,
        Finished,
        ;

        fun isAfter(before: LoaderPhase) =
            (ordinal > before.ordinal)
    }

    val cache = CacheUtil()
    val imageCache = ImageCache(cache)

    var phase: LoaderPhase = LoaderPhase.Loading
        private set

    private val contentByPathMutable = mutableMapOf<ContentPath, ContentDef>()
    val contentByPath get(): Map<ContentPath, ContentDef> = contentByPathMutable
    private val validatorsCollector: MutableList<(loaderContext: LoaderContext) -> String?> = mutableListOf()
    val validators get(): List<(loaderContext: LoaderContext) -> String?> = validatorsCollector
    val registeredContent = mutableSetOf<ObjectDef>()
    private lateinit var metadataMap: Map<ObjectDef, ContentDefMetadata>
    val metadata get() = metadataMap
    private val contentByFsPath = mutableMapOf<Path, ContentPath>()
    internal val lastLoadingDuration = Timing("loading")
    internal val lastVerifyDuration = Timing("verify")

    val typeReflectionCache =
        mutableMapOf<KClass<out ContentDef>, ContentDefReflection<out ContentDef>>()

    fun <T : ContentDef> reflectionForType(clazz: KClass<out T>) =
        typeReflectionCache.computeIfAbsent(clazz) { ContentDefReflection(it) }


    /** Only valid after loading is finished. */
    val rootNode get() = requireNotNull(contentByPath[ContentPath.root]) {
        "wanted to resolve ${ContentPath.root} - available: ${contentByPath.entries}"
    }
    val gitInfo by lazy {
        GitInfoLoaderCmd(root).load()
    }

    fun <T: ContentDef>registerLoadedContent(content: LoadedContent<T>) {
        contentByPathMutable[content.metadata.path] = content.content
//        registerObjectDef(content, content.content)
    }

    fun findContentPath(fsPath: Path) = contentByFsPath[fsPath]

    fun <T: ContentDef> registerObjectDef(parent: LoadedContent<T>, content: ObjectDef) =
        if (registeredContent.add(content)) {
            if (parent.metadata.fsPath != null) {
                logger.trace { "putting fs path ${parent.metadata.fsPath.toAbsolutePath()}"}
                contentByFsPath.putIfAbsent(parent.metadata.fsPath.toAbsolutePath(), parent.metadata.path)
            }
            if (content is ValidationRequired) {
                validatorsCollector.add { loaderContext: LoaderContext ->
                    content.validate(loaderContext, parent)?.let { "${parent.metadata.path}: $it" }
                }
            }
            true
        } else { false}

    internal fun finishedLoadingStartValidate(metadataMap: Map<ObjectDef, ContentDefMetadata>) {
        this.metadataMap = metadataMap
        phase = LoaderPhase.Validating
        validators.mapNotNull { it(this) }
            .also { errors ->
                if (errors.isNotEmpty()) {
                    throw IllegalArgumentException("Error validating content. $errors")
                }
            }
        phase = LoaderPhase.Finished
    }

    fun findContentPath(content: ObjectDef) = run {
        assert(phase.isAfter(LoaderPhase.Loading))
        // every content must be registered in the metadataMap
        requireNotNull(metadataMap[content]) {
            "Unable to find path for content ${content.toStringReflective(maxDepth = 2)}"
        }.path
    }

    /**
     * @see [AbstractPath.subPathDistance]
     */
    fun subPageDistance(parent: ContentDef, child: ContentDef): Int? =
        findContentPath(child).subPathDistance(findContentPath(parent))

    fun reload(content: ContentDef) {
        val metadata = requireNotNull(metadataMap[content])
        @Suppress("UNCHECKED_CAST")
        val loader = ContentLoader(metadata.contentDefClass as KClass<ContentDef>)
        phase = LoaderPhase.Loading
        val loadedContent = loader.reload(this, content, metadata)
        val newMetadataMap = metadataMap + loadedContent.metadata.childrenMetadata + (loadedContent.content to loadedContent.metadata)
        finishedLoadingStartValidate(newMetadataMap)
    }

    override fun close() {
        cache.close()
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

data class ContentDefChild<T: ObjectDef>(
    val name: String,
    val loadedContent: LoadedContent<T>,
    val isProperty: Boolean
)

class ContentLoader<T : ContentDef>(private val klass: KClass<T>) {

    companion object {

        val objectMapper = ObjectMapper(YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, true)
            .registerModule(object : SimpleModule() {
                override fun setupModule(context: SetupContext) {
                    super.setupModule(context)
                    context.insertAnnotationIntrospector(object : AnnotationIntrospector() {
                        override fun version(): Version = Version.unknownVersion()

                        override fun findInjectableValue(m: AnnotatedMember): JacksonInject.Value? {

                            if (m.member is Method) {
                                if (ContentBranchDef::class.java.isAssignableFrom(m.declaringClass)) {
                                    val childrenSetter =
                                        requireNotNull(ContentBranchDef<*>::children.javaSetter)
                                    if (childrenSetter.name == m.member.name) {
                                        return JacksonInject.Value.forId(PROPERTY_CHILDREN)
                                    }
                                }
                            }

                            return super.findInjectableValue(m)
                        }
                    })
                }
            }.also { module ->
                module.addDeserializer(ZonedDateTime::class.java, TolerantZonedDateTime())
                module.addDeserializer(
                    ImageAsset::class.java,
                    FileAssetDeserializer(ImageAsset::class.java, ::ImageAsset)
                )
                module.addDeserializer(
                    FileAsset::class.java,
                    FileAssetDeserializer(FileAsset::class.java, ::FileAsset)
                )
            })
            .registerKotlinModule()
//            .registerModule(JavaTimeModule())
            .registerModule(MrBeanModule())


        private val availablePropertyTypes by lazy {
            Reflections("com.dc2f").getTypesAnnotatedWith(PropertyType::class.java)
                .mapNotNull { it.kotlin.findAnnotation<PropertyType>()?.identifier?.to(it.kotlin) }
                .toMap()
        }

    }

    fun findPropertyTypes() = availablePropertyTypes

    fun <RET> load(dir: Path, process: (content: LoadedContent<T>, context: LoaderContext) -> RET): RET =
        LoaderContext(dir).use { context ->
            process(
                load(context, dir),
                context
            )
        }

    private fun load(context: LoaderContext, dir: Path): LoadedContent<T> =
        context.lastLoadingDuration.measure { _load(context, dir, ContentPath.root, null) }
            .also { c ->
                context.lastVerifyDuration.measure {
                    c.context.finishedLoadingStartValidate(c.metadata.childrenMetadata + (c.content to c.metadata))
                }
            }

    fun loadWithoutClose(dir: Path): Pair<LoadedContent<T>, LoaderContext> {
        val context = LoaderContext(dir)
        return load(context, dir) to context
    }

    internal fun reload(context: LoaderContext, content: ContentDef, metadata: ContentDefMetadata) =
        _load(context, requireNotNull(metadata.fsPath?.parent), metadata.path, null).also {
            if (content is ProxyObjectDef) {
                content.replace(it.content)
            }
        }

    private fun _load(context: LoaderContext, dir: Path, contentPath: ContentPath, comment: String?): LoadedContent<T> {
        require(Files.isDirectory(dir)) { "Not a valid directory: ${dir.toAbsolutePath()}" }
        val idxYml = dir.resolve(INDEX_YAML_NAME)

        val propertyTypes = findPropertyTypes()
        val reflection = context.reflectionForType(clazz = klass)

//        klass.memberProperties.find { it.findAnnotation<JacksonInject>() }

        val children = Files.list(dir)
//            .filter { Files.isDirectory(it) }
            .sorted()
            .map { child ->

                logger.trace { "Checking child $child." }
                val fileName = child.fileName.toString()
                val isProperty = fileName.startsWith('@')
                val folderArgs = fileName
                    .substring(isProperty.then { 1 } ?: 0)
                    .split('.')

                val (comment, slug, typeIdentifier) = when {
                    folderArgs.size == 3 -> folderArgs
                    folderArgs.size == 2 -> listOf(null) + folderArgs
                    else -> listOf(null, "", null)
                }

                val prefix = if (isProperty) { "@" } else { "" }
                val propertyName = isProperty.then { slug } ?: PROPERTY_CHILDREN

                logger.trace { "comment: $comment, slug: $slug, typeIdentifier: $typeIdentifier" }

                if (Files.isRegularFile(child)) {
                    return@map propertyTypes[typeIdentifier]?.let { propType ->
                        val companion = propType.companionObjectInstance
                        if (companion is Parsable<*>) {
                            val childPath = contentPath.child(prefix + propertyName)
                            ContentDefChild(propertyName, LoadedContent(
                                context,
                                companion.parseContent(context, child, childPath),
                                ContentDefMetadata(
                                    childPath,
                                    fsPath = child,
                                    directChildren = emptyMap(),
                                    comment = comment
                                )
                            )/*.also(context::registerLoadedContent)*/, isProperty)
                        } else {
                            null
                        }
                    }
                }

                requireNotNull(slug)
                requireNotNull((reflection.property[propertyName] as? ContentDefPropertyReflectionNested)?.allowedTypesClasses?.get(typeIdentifier)) {
                    "Unable to fetch type for $propertyName and $typeIdentifier for $child -- ${reflection.property[propertyName]}"
                }.let { type ->
                    @Suppress("UNCHECKED_CAST")
                    ContentLoader(type.kotlin as KClass<ContentDef>)
                        ._load(context, child, contentPath.child(prefix + slug), comment)
                }.let { ContentDefChild(propertyName, it, isProperty) }.also { context.registerLoadedContent(it.loadedContent) }
            }.filter { it != null }.toList().filterNotNull().groupBy { it.name }.toMutableMap()
        logger.trace { "Children: $children" }

        val injectableValues = object : InjectableValues() {
            override fun findInjectableValue(
                valueId: Any,
                ctxt: DeserializationContext?,
                forProperty: BeanProperty?,
                beanInstance: Any?
            ): Any? {
                if (valueId == ContentLoaderDeserializeContext::class.java) {
                    return ContentLoaderDeserializeContext(context.root, dir, contentPath)
                }
                if (valueId == CommitInfo::class.qualifiedName) {
                    return context.gitInfo[context.root.relativize(idxYml).toString().also { logger.debug { "Looking up $it" }}]
                        ?.also { logger.debug { "found $it" } }
                }
//                if (valueId is Children) {
                require(valueId is String)
                logger.trace {
                    "Need to inject ${ReflectionToStringBuilder.toString(
                        Optional.ofNullable(
                            valueId
                        ), ToStringStyle.SHORT_PREFIX_STYLE
                    )} --- $children"
                }
                val isListProperty = (forProperty?.member as? AnnotatedMethod)?.getParameterType(0)?.isTypeOrSuperTypeOf(List::class.java) == true
                return children[valueId]?.let { child ->
                    logger.trace("   injecting children: $child.")
                    @Suppress("IMPLICIT_CAST_TO_ANY")
                    if (isListProperty) {
                        child.map { it.loadedContent.content }
                    } else {
                        child[0].loadedContent.content
                    }
                    // FIXME maybe just use a default value instead of this hard coded stuff?
                    //       should actually work by simply specifying `= emptyList()` for
                    //       abstract classes. but probably not for interfaces?
                } ?: (isListProperty).then { emptyList<ContentDef>() }.also { logger.debug("Not Found!! $it") }

            }

        }

        // Make _index.yml optional, if there are no required (non nestable) attributes.
        val fileContent = if (Files.exists(idxYml)) {
            Files.readAllBytes(idxYml)
        } else {
            "{}".toByteArray()
        }
        val tree = objectMapper.readTree(fileContent)

        val obj = try {
            objectMapper.reader(injectableValues)
                .forType(klass.java)
                .readValue<T>(tree)
        } catch (e: Throwable) {
            throw Exception("Error while parsing $idxYml: ${e.message}", e)
        }
        val enhancer = Enhancer()
        enhancer.setSuperclass(obj.javaClass)
        enhancer.setInterfaces(arrayOf(ProxyObjectDef::class.java))
        enhancer.setCallback(object : MethodInterceptor {
            var targetObject = obj
            override fun intercept(
                tmpObj: Any?,
                method: Method?,
                args: Array<out Any>?,
                proxy: MethodProxy?
            ): Any? {
                @Suppress("UNCHECKED_CAST")
                if (method?.name == "replace") {
                    require(args != null)
                    logger.debug { "Replacing targetObject." }
                    targetObject = args[0] as T
                    return null
                }
                if (proxy == null) {
                    logger.warn { "invoked with null proxy? weird. $method" }
                    return null
                }
                if (method?.name == "equals") {
                    val other = args?.first()
                    if (tmpObj === other || targetObject === other) {
                        return true
                    }
                }
                return proxy.invoke(targetObject, args)
            }

        })
        @Suppress("UNCHECKED_CAST") val objProxy = enhancer.create() as T
//        Proxy.newProxyInstance(obj.javaClass.classLoader, arrayOf(obj.javaClass), )
        return LoadedContent(context, objProxy, ContentDefMetadata(
            contentPath,
            children.values
                .flatten()
                .map {
                    it.loadedContent.metadata.childrenMetadata.entries.toPairs() +
                        setOf(it.loadedContent.content to it.loadedContent.metadata)
                }
                .flatten()
                .toMap(),
            idxYml,
            directChildren = children,
            contentDefClass = klass,
            comment = comment
        )).also { context.registerLoadedContent(it) }.also {
            try {
                validateContentDef(context, it, it.content, it.metadata)
            } catch (e: Throwable) {
                throw IllegalArgumentException("Error while checking $contentPath: ${e.message}", e)
            }
        }
    }

    private fun<T: ContentDef> validateBeanIfRequired(context: LoaderContext, parent: LoadedContent<T>, content: Any?) {
        when (content) {
            is ObjectDef -> validateContentDef(context, parent, content, parent.metadata.childrenMetadata[content])
            is Map<*, *> -> content.forEach { (key, value) ->
                validateBeanIfRequired(context, parent, key)
                validateBeanIfRequired(context, parent, value)
            }
            is Collection<*> -> content.forEach {
                validateBeanIfRequired(context, parent, it)
            }
        }
    }

    private fun<T: ContentDef> validateContentDef(
        context: LoaderContext,
        parent: LoadedContent<T>,
        content: ObjectDef,
        metadata: ContentDefMetadata?
    ) {
        context.registerObjectDef(parent, content)
            || return // if the content was already registered before, no need to check it again.

        if (content !is ContentDef) {
            return
        }

        @Suppress("USELESS_CAST")
        val reflection = context.reflectionForType(metadata?.contentDefClass ?: (content as ContentDef)::class)

        reflection.properties.forEach {
            val value = it.getValue(content)
            if (value == null) {
                if (!it.optional) {
                    throw IllegalArgumentException("Property ${it.name} is null (of ${content.javaClass.simpleName}).")
                }
            } else {
                validateBeanIfRequired(context, parent, value)
            }
        }
//        content.javaClass.kotlin.memberProperties.forEach {
//            if (it.returnType.isJavaType) {
//                return@forEach
//            }
//            if (it.isLateinit) {
//                logger.trace { "Ignoring lateinit property $it" }
//                return@forEach
//            }
//            if (!it.isAccessible) {
////                logger.warn { "Can't access property $it" }
//                it.isAccessible = true
////                return@forEach
//            }
//            if (it.getDelegate(content) != null) {
//                logger.trace { "Ignoring delegated property $it" }
//                return@forEach
//            }
//            try {
//                val x = it.get(content)
//                if (x == null) {
//                    if (!it.returnType.isMarkedNullable) {
//                        throw IllegalArgumentException("Property ${it.name} is null (of ${content.javaClass.simpleName}).")
//                    }
//                } else {
//                    validateBeanIfRequired(context, parent, x)
//                }
//            } catch (e: Throwable) {
//                throw ValidationException("Error while validating property $it: ${e.message}", e)
//            }
//        }
    }
}

interface ProxyObjectDef {
    fun replace(with: ObjectDef)
}

fun <K, V> Iterable<Map.Entry<K, V>>.toPairs() = map { it.toPair() }
