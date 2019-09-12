package com.dc2f.loader

import com.dc2f.*
import com.dc2f.util.*
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.*
import mu.KotlinLogging
import org.reflections.Reflections
import java.lang.reflect.Modifier
import kotlin.reflect.*
import kotlin.reflect.full.*
import kotlin.reflect.jvm.*

private val logger = KotlinLogging.logger {}

@ApiDto
class ContentDefReflection<T : ContentDef>(@JsonIgnore val klass: KClass<T>) {

    @Suppress("unused")
    val type
        get() = klass.qualifiedName

    @Suppress("unused")
    val typeIdentifier: String? by lazy {
        klass.findAnnotation<Nestable>()?.identifier
    }

    @JsonIgnore
    val contentLoader = ContentLoader(klass)

    @Suppress("unused")
    val defaultValues by lazy {
        if (!klass.isSubclassOf(ContentDef::class)) {
            logger.debug("Discovered a class which is not a subclass of ContentDef. not reflecting default values. $klass")
            return@lazy emptyMap<String, Any?>()
        }
        if (klass.isSealed) {
            // sealed classes must not be used directly, but only their children.
            logger.debug { "Discovered a sealed class. $klass" }
            return@lazy emptyMap<String, Any?>()
        }
        val reader = ContentLoader.objectMapper.reader(object : InjectableValues() {
            override fun findInjectableValue(
                valueId: Any?,
                ctxt: DeserializationContext?,
                forProperty: BeanProperty?,
                beanInstance: Any?
            ): Any? = null
        }).forType(klass.java)
        val emptyInstance = reader.readValue<ContentDef>("{}")
        properties.map { prop ->
            prop.name to prop.getValue(emptyInstance)
        }.filter { it.second != null }.toMap()
    }

    val property by lazy {
        properties.map { it.name to it }.toMap()
    }
    val properties by lazy {
        klass.memberProperties.filter { !it.returnType.isJavaType }
            .filter { prop ->
                if (!prop.isOpen && !prop.isAbstract) {
                    logger.error { "property must be open: $prop ${prop.isAbstract}" }
                }
                // ignore lateinit properties.
                if (prop.isLateinit) {
                    if (!prop.isTransient) {
                        throw IllegalArgumentException("a lateinit field must be marked as transient.")
                    }
                    return@filter false
                }
                if (prop.getter.findAnnotation<JsonIgnore>()?.value == true) {
                    return@filter false
                }
                return@filter true
            }
            .map { prop ->
                val elementJavaClass = prop.elementJavaClass
                if (ParsableObjectDef::class.java.isAssignableFrom(prop.elementJavaClass)) {
                    require(!prop.isMultiValue) {
                        "MultiValue parsable values are not (yet) supported. $klass::${prop.name}"
                    }
                    val propertyTypes = findPropertyTypesFor(elementJavaClass)
                    ContentDefPropertyReflectionParsable(
                        prop.name,
                        prop.returnType.isMarkedNullable,
                        prop.isMultiValue,
                        propertyTypes.keys.joinToString(","),
                        propertyTypes
                    )
                } else if (ContentDef::class.java.isAssignableFrom(prop.elementJavaClass)) {
//                    if (elementJavaClass.kotlin.companionObjectInstance is Parsable<*>) {

                    elementJavaClass.kotlin.isAbstract
                    ContentDefPropertyReflectionNested(
                        prop.name,
                        prop.returnType.isMarkedNullable,
                        prop.isMultiValue,
                        (findChildTypesForProperty(prop.name)?.map { type ->
                            type.key to type.value
                        }?.toMap() ?: emptyMap()) +
                            // TODO i don't think this is necessary actually?
                            findPropertyTypesFor(
                                elementJavaClass
                            ).mapValues { it.value.java },
                        elementJavaClass.name
                    )
                } else if (Map::class.java.isAssignableFrom(elementJavaClass)) {
                    ContentDefPropertyReflectionMap(
                        prop.name,
                        prop.returnType.isMarkedNullable,
                        prop.isMultiValue,
                        requireNotNull(prop.returnType.arguments[1].type?.javaType?.typeName)
                    )
                } else if (BaseFileAsset::class.java.isAssignableFrom(elementJavaClass)) {
                    ContentDefPropertyReflectionFileAsset(
                        prop.name,
                        prop.returnType.isMarkedNullable,
                        prop.isMultiValue,
                        ImageAsset::class.java.isAssignableFrom(elementJavaClass)
                            .then { ContentDefPropertyReflectionFileAsset.Type.Image }
                            ?: ContentDefPropertyReflectionFileAsset.Type.File
                    )
                } else if (ContentReference::class.java.isAssignableFrom(elementJavaClass)) {
                    ContentDefPropertyReflectionContentReference(
                        prop.name,
                        prop.returnType.isMarkedNullable,
                        prop.isMultiValue
                    )
                } else if (elementJavaClass.isEnum) {
                    logger.debug("$elementJavaClass is an enum. ${elementJavaClass is Enum<*>} vs. ${elementJavaClass.kotlin is Enum<*>}")
//                    logger.debug("$elementJavaClass is an enum. ${elementJavaClass is Enum<*>} vs. ${elementJavaClass.kotlin is Enum<*>}")

                    ContentDefPropertyReflectionEnum(
                        prop.name,
                        prop.returnType.isMarkedNullable,
                        prop.isMultiValue,
                        elementJavaClass.enumConstants.map { (it as Enum<*>).name }
                    )
                } else {
                    ContentDefPropertyReflectionPrimitive(
                        prop.name,
                        prop.returnType.isMarkedNullable,
                        prop.isMultiValue,
                        PrimitiveType.fromJavaClass(
                            elementJavaClass,
                            "${klass}::${prop.name}"
                        )
                    )
                }
            }
            .sortedWith(compareBy({ it.optional }, { it.name }))
//            .sortedBy { it.name }
    }

    private fun findPropertyTypesFor(clazz: Class<*>) =
        contentLoader.findPropertyTypes().filter { clazz.isAssignableFrom(it.value.java) }

    private fun findChildTypesForProperty(propertyName: String) =
        childTypesForProperty(propertyName)

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

}

@ApiDto
sealed class ContentDefPropertyReflection(
    val name: String,
    val optional: Boolean,
    val multiValue: Boolean
) {
    fun getValue(content: ContentDef): Any? {
        val property =
            requireNotNull(content::class.memberProperties.find { it.name == name && !it.returnType.isJavaType }) {
                "Unable to find member property $name on $content (${content::class})"
            }
        return property.getter.run {
            isAccessible = true
            call(content)
        }
    }

    @Suppress("unused")
    val kind
        get() = when (this) {
            is ContentDefPropertyReflectionParsable -> "Parsable"
            is ContentDefPropertyReflectionPrimitive -> "Primitive"
            is ContentDefPropertyReflectionMap -> "Map"
            is ContentDefPropertyReflectionNested -> "Nested"
            is ContentDefPropertyReflectionFileAsset -> "File"
            is ContentDefPropertyReflectionContentReference -> "ContentReference"
            is ContentDefPropertyReflectionEnum -> "Enum"
        }
}

class ContentDefPropertyReflectionEnum(
    name: String, optional: Boolean, multiValue: Boolean,
    @Suppress("unused")
    val enumValues: List<String>
) : ContentDefPropertyReflection(name, optional, multiValue)


class ContentDefPropertyReflectionPrimitive(
    name: String, optional: Boolean, multiValue: Boolean,
    @Suppress("unused")
    val type: PrimitiveType
) : ContentDefPropertyReflection(name, optional, multiValue)

enum class PrimitiveType(vararg val clazz: KClass<*>) {
    Boolean(kotlin.Boolean::class),
    String(kotlin.String::class, Slug::class),
    //    Integer(Integer::class),
    ZonedDateTime(java.time.ZonedDateTime::class),
    Unknown(Any::class)

    ;

    companion object {
        fun fromJavaClass(elementJavaClass: Class<*>, debugMessage: kotlin.String): PrimitiveType {
            val kotlinClazz = elementJavaClass.kotlin
            val type = values().find { it.clazz.contains(kotlinClazz) }
            if (type == null) {
                logger.error { "UNKNOWN PrimitiveType: $kotlinClazz ($debugMessage)" }
                return Unknown
            }
            return type
        }

    }
}

class ContentDefPropertyReflectionParsable(
    name: String, optional: Boolean, multiValue: Boolean,
    @Suppress("unused")
    val parsableHint: String, @JsonIgnore val parsableTypes: Map<String, KClass<out Any>>
) : ContentDefPropertyReflection(name, optional, multiValue)

class ContentDefPropertyReflectionMap(
    name: String, optional: Boolean, multiValue: Boolean,
    @Suppress("unused")
    val mapValueType: String
) : ContentDefPropertyReflection(name, optional, multiValue)

class ContentDefPropertyReflectionNested(
    name: String, optional: Boolean, multiValue: Boolean,
    @JsonIgnore
    val allowedTypesClasses: Map<String, Class<*>>, val baseType: String
) : ContentDefPropertyReflection(name, optional, multiValue) {
    val allowedTypes = allowedTypesClasses.mapValues { it.value.name }
}

class ContentDefPropertyReflectionFileAsset(
    name: String, optional: Boolean, multiValue: Boolean,
    @Suppress("unused")
    val fileType: Type
) : ContentDefPropertyReflection(name, optional, multiValue) {
    enum class Type {
        File,
        Image
    }
}

class ContentDefPropertyReflectionContentReference(
    name: String, optional: Boolean, multiValue: Boolean
) : ContentDefPropertyReflection(name, optional, multiValue)

private val <R> KProperty<R>.isTransient: Boolean
    get() =
        javaField?.modifiers?.let {
            Modifier.isTransient(it)
        } == true


/// either the class of the return value, or the class of elements in the collection, if multivalue (ie. a collection)
private val <R> KProperty<R>.elementJavaClass: Class<*>
    get() =
        requireNotNull(
            if ((returnType.classifier as? KClass<*>)?.isSubclassOf(Map::class) == true) {
                Map::class
            } else {
                if (isMultiValue) {
                    returnType.arguments[0].type
                } else {
                    returnType
                }?.classifier as? KClass<*>
            }?.java
        ) {
            "Return Type is not a class: $returnType / ${returnType.javaType} (of property $this)"
        }

private val <R> KProperty<R>.isMultiValue: Boolean
    get() =
        (returnType.classifier as? KClass<*>)?.isSubclassOf(Collection::class) == true

