package com.dc2f.assets

import com.dc2f.render.RenderCharAsset
import java.net.URI

interface Transformer {
    fun transform(input: RenderCharAsset): RenderCharAsset
}
