package com.dc2f.assets

import java.net.URI

interface Asset {
    fun transform(input: URI, output: URI)
}
