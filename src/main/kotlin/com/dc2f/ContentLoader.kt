package com.dc2f

import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.mrbean.MrBeanModule
import mu.KotlinLogging
import org.apache.commons.lang3.builder.ReflectionToStringBuilder
import org.reflections.Reflections
import java.nio.file.*
import kotlin.reflect.*
import kotlin.reflect.full.*
import kotlin.streams.toList
import com.fasterxml.jackson.databind.module.SimpleModule



private val logger = KotlinLogging.logger {}

class ContentLoader<T : ContentDef>(val klass: KClass<T>) {


    fun load(dir: Path): T? {
        require(Files.isDirectory(dir))
        val idxYml = dir.resolve("_index.yml")
        val module = SimpleModule()
//        module.addDeserializer(Children::class.java, ChildrenDeserializer())

        val objectMapper = ObjectMapper(YAMLFactory())
            .registerModule(MrBeanModule())
            .registerModule(module)
        val children = if (ContentBranchDef::class.java.isAssignableFrom(klass.java)) {
//        if (obj is ContentBranchDef<*>) {
//            logger.trace { "type parameters ${(obj::children.returnType.classifier as KClass<*>).typeParameters}" }
            val member = klass.members.find { it.name == ContentBranchDef<T>::children.name }
            val typeArgument = member?.returnType?.arguments?.get(0)?.type
            if (typeArgument != null) {
                logger.trace { "{$typeArgument}" }
                val childrenClass = (typeArgument.classifier as KClass<*>).java
                val childTypes = Reflections("com.dc2f").getSubTypesOf(childrenClass).map {
                    val nestable =
                        it.kotlin.allSuperclasses.mapNotNull { it.findAnnotation<Nestable>() }
                        .firstOrNull()
//                    val nestable = it.kotlin.findAnnotation<Nestable>()
                    logger.trace { "available class: ${it} --- ${nestable}" }
                    nestable?.identifier?.to(it)
                }.filterNotNull().toMap()
                val children = Files.list(dir)
                    .filter { Files.isDirectory(it) }
                    .map { child ->
                        logger.trace { "Checking child ${child}. ${childTypes}" }
                        val (sort, slug, typeIdentifier) = child.fileName.toString().split('.')
                        childTypes[typeIdentifier]?.let { type ->
                            @Suppress("UNCHECKED_CAST")
                            ContentLoader(type.kotlin as KClass<ContentDef>)
                                .load(child)
                        }
                    }.filter { it != null }.toList().filterNotNull()
                logger.info { "Children: ${ReflectionToStringBuilder.toString(children)}" }
                children
            } else { null }
        } else { null }

        val injectableValues = InjectableValues.Std().addValue("children", children)

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
                    injectableValues.addValue(key, pair.second)
                }
            }

        val obj = objectMapper.reader(injectableValues)
            .forType(klass.java)
            .readValue<T>(Files.readAllBytes(idxYml))
        return obj
    }
}