package com.lagradost.runtime.loader.stubs

import java.io.InputStream
import java.net.URL
import java.net.URLConnection

object URLStub {
    @JvmStatic
    fun openConnection(url: URL): URLConnection = url.openConnection()

    @JvmStatic
    fun openStream(url: URL): InputStream = url.openStream()

    @JvmStatic
    fun getContent(url: URL): Any = url.content
}
