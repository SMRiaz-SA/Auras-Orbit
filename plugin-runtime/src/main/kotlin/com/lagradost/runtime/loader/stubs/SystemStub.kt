package com.lagradost.runtime.loader.stubs

/** Bytecode compatibility shims that preserve normal JVM System behavior. */
object SystemStub {
    @JvmStatic
    fun exit(status: Int) = System.exit(status)

    @JvmStatic
    fun loadLibrary(libname: String) = System.loadLibrary(libname)

    @JvmStatic
    fun load(filename: String) = System.load(filename)

    @JvmStatic
    fun setSecurityManager(s: SecurityManager?) = System.setSecurityManager(s)

    @JvmStatic
    fun getProperty(key: String): String? = System.getProperty(key)

    @JvmStatic
    fun getProperty(key: String, def: String?): String? = System.getProperty(key, def)

    @JvmStatic
    fun getenv(name: String): String? = System.getenv(name)

    @JvmStatic
    fun getenv(): Map<String, String> = System.getenv()

    @JvmStatic
    fun getProperties(): java.util.Properties = System.getProperties()
}
