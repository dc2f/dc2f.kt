package com.dc2f.util

import mu.KotlinLogging
import org.ehcache.*
import org.ehcache.config.builders.CacheManagerBuilder
import org.ehcache.core.spi.service.StatisticsService
import org.ehcache.spi.service.*
import java.io.File
import kotlin.reflect.KClass

private val logger = KotlinLogging.logger {}

@ServiceDependencies(StatisticsService::class)
class AppService: Service {
    lateinit var stats: StatisticsService

    override fun start(serviceProvider: ServiceProvider<Service>) {
        stats = serviceProvider.getService(StatisticsService::class.java)
        logger.debug { "Service was started, got statistics service ${stats}" }
    }

    override fun stop() {
    }

}

object CacheUtil {

    private val dummy = AppService()

    val cacheDirectory by lazy {
        File(".dc2f", "cache")
    }

    val cacheManager by lazy {

        CacheManagerBuilder.newCacheManagerBuilder()
            .using(dummy)
            .with(CacheManagerBuilder.persistence(File(".dc2f", "cache")))
            .build(true)
    }

    val stats get() = dummy.stats


    val allStatistics get() =
        cacheManager.runtimeConfiguration.cacheConfigurations
                .keys
                .joinToString(System.lineSeparator()) { cacheName ->
                    "${cacheName}: ${System.lineSeparator()}${produceStatistics(cacheName).prependIndent("    ")}"
                }

    private fun produceStatistics(cacheName: String) =
        stats.getCacheStatistics(cacheName)
                .knownStatistics
                .entries
                .joinToString(System.lineSeparator()) {
                    entry -> "${entry.key } = ${entry.value.value()}"
                }

    fun clearAllCaches() {
        cacheManager.runtimeConfiguration.cacheConfigurations
                .entries
                .forEach {  entry ->
                    cacheManager.getCache(entry.key, entry.value.keyType, entry.value.valueType)?.clear()
                }
    }
}

private fun <T : CacheManager?, U : Service> CacheManagerBuilder<T>.using(kClass: KClass<U>) =
        using {
            @Suppress("UNCHECKED_CAST")
            kClass.java as Class<Service>?
        }

val cacheUtilLoggerPublicName = logger

inline fun <KEY, VALUE>Cache<KEY, VALUE>.cached(key: KEY, producer: () -> VALUE) =
    this.get(key)?.also { cacheUtilLoggerPublicName.debug { "Found value for ${key}." } }
            ?: producer().also { value -> this.put(key, value); cacheUtilLoggerPublicName.debug { "Had to recompute ${key}" } }

