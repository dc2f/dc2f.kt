package com.dc2f

import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.mrbean.MrBeanModule
import mu.KotlinLogging
import org.reflections.Reflections
import java.nio.file.*
import kotlin.reflect.*
import kotlin.reflect.full.*
import kotlin.streams.toList
import com.fasterxml.jackson.databind.module.SimpleModule
import org.apache.commons.lang3.builder.*
import java.util.*


private val logger = KotlinLogging.logger {}

class ContentLoader<T : ContentDef>(val klass: KClass<T>) {

    fun childTypesForProperty(propertyName: String): Map<String, Class<out Any>>? {
        logger.trace { "Loading childTypes for ${propertyName} of ${klass}."}
        val typeArgument = klass.members.find { it.name == propertyName }?.let { member ->
            if ((member.returnType.classifier as? KClass<*>)?.isSubclassOf(Collection::class) == true) {
                member.returnType.arguments[0].type
            } else {
                member.returnType
            }
        } ?: return null
        logger.trace { "childTypes for ${propertyName}: typeArgument: {$typeArgument}" }
        val childrenClass = (typeArgument.classifier as KClass<*>).java
        return (setOf(childrenClass) + Reflections("app.anlage", "com.dc2f").getSubTypesOf(childrenClass)).map {
            val nestable =
                it.kotlin.findAnnotation<Nestable>() ?:
                it.kotlin.allSuperclasses.mapNotNull { it.findAnnotation<Nestable>() }
                    .firstOrNull()
//                    val nestable = it.kotlin.findAnnotation<Nestable>()
            logger.trace { "available class: ${it} --- ${nestable}" }
            nestable?.identifier?.to(it)
        }.filterNotNull().toMap()
    }

    fun load(dir: Path): T? {
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
                logger.trace { "Checking child ${child}. ${childTypes}" }
                val folderArgs = child.fileName.toString().split('.')
                val (sort, slug, typeIdentifier) = when {
                    folderArgs.size == 3 -> folderArgs
                    folderArgs.size == 2 -> listOf(null) + folderArgs
                    else -> listOf(null, null, null)
                }
                logger.trace { "sort: ${sort}, slug: ${slug}, typeIdentifier: ${typeIdentifier}" }
                when {
                    sort != null -> childTypes[typeIdentifier]?.let { type ->
                        @Suppress("UNCHECKED_CAST")
                        ContentLoader(type.kotlin as KClass<ContentDef>)
                            .load(child)
                    }?.let { "children" to it }
                    slug != null -> childTypesForProperty(slug)?.get(typeIdentifier)?.let { type ->
                        @Suppress("UNCHECKED_CAST")
                        ContentLoader(type.kotlin as KClass<ContentDef>)
                            .load(child)
                    }?.let { slug to it }
                    else -> null
                }
            }.filter { it != null }.toList().filterNotNull().groupBy { it.first }.toMutableMap()
        logger.info { "Children: ${children} -- ${ReflectionToStringBuilder.toString(children)}" }

        val injectableValues = object : InjectableValues() {
            override fun findInjectableValue(
                valueId: Any?,
                ctxt: DeserializationContext?,
                forProperty: BeanProperty?,
                beanInstance: Any?
            ): Any? {
                logger.debug { "Need to inject ${ReflectionToStringBuilder.toString(Optional.ofNullable(valueId), ToStringStyle.SHORT_PREFIX_STYLE)} --- ${children}" }
                return (valueId as? String)?.let { children[it]?.let { child ->
                    if (valueId == "children") {
                        logger.debug("injecting children: ${child}.")
                        child.map { it.second }
                    } else {
                        child[0].second
                    }
                } }
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
                propertyTypes[extension]?.let { propType ->
                    val companion = propType.companionObjectInstance
                    if (companion is Parsable<*>) {
                        file to companion.parseContent(file)
                    } else {
                        null
                    }
                }
            }.forEach { pair ->
                if (pair?.second != null) {
                    val key = pair.first.fileName.toString().substringBefore('.')
//                    injectableValues.addValue(key, pair.second)
                    children[key] = listOf(key to pair.second as ContentDef)
                }
            }

        val tree = objectMapper.readTree(Files.readAllBytes(idxYml))
        logger.info { "tree: ${tree}" }

        val obj = objectMapper.reader(injectableValues)
            .forType(klass.java)
            .readValue<T>(tree)
        return obj
    }
}