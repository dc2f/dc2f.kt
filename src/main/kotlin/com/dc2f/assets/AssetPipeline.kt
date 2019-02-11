package com.dc2f.assets

import java.net.URI

interface Transformer {
    fun transform(input: URI, output: URI)
}
