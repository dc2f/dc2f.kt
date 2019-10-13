package com.dc2f.assets

import com.dc2f.render.RenderCharAsset
import java.io.Serializable

interface TransformerCacheKey : Serializable
interface TransformerValue : Serializable

data class StringTransformerCacheKey(val value: String) : TransformerCacheKey

interface Transformer<out T: TransformerValue> {
    val cacheKey: TransformerCacheKey
    @Suppress("UNUSED_PARAMETER")
    val value: T?
        get() = null
    fun transform(input: RenderCharAsset): RenderCharAsset
    fun updateValueFromCache(transformerValue: TransformerValue) { }
}
