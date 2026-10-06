package com.lagradost.runtime.loader.stubs

/** Bytecode compatibility shims that preserve normal JVM Runtime behavior. */
@Suppress("DEPRECATION") // Runtime.exec signatures are intentionally mirrored for transformed plugin bytecode.
object RuntimeStub {
    @JvmStatic
    fun exec(runtime: Runtime, command: String): Process = runtime.exec(command)

    @JvmStatic
    fun exec(runtime: Runtime, cmdarray: Array<String>): Process = runtime.exec(cmdarray)

    @JvmStatic
    fun exec(runtime: Runtime, cmdarray: Array<String>, envp: Array<String>?): Process = runtime.exec(cmdarray, envp)

    @JvmStatic
    fun exec(runtime: Runtime, cmdarray: Array<String>, envp: Array<String>?, dir: java.io.File?): Process =
        runtime.exec(cmdarray, envp, dir)

    @JvmStatic
    fun exec(runtime: Runtime, command: String, envp: Array<String>?): Process = runtime.exec(command, envp)

    @JvmStatic
    fun exec(runtime: Runtime, command: String, envp: Array<String>?, dir: java.io.File?): Process =
        runtime.exec(command, envp, dir)

    @JvmStatic
    fun loadLibrary(runtime: Runtime, libname: String) = runtime.loadLibrary(libname)

    @JvmStatic
    fun load(runtime: Runtime, filename: String) = runtime.load(filename)

    @JvmStatic
    fun exit(runtime: Runtime, status: Int) = runtime.exit(status)

    @JvmStatic
    fun halt(runtime: Runtime, status: Int) = runtime.halt(status)

    @JvmStatic
    fun availableProcessors(runtime: Runtime): Int = runtime.availableProcessors()

    @JvmStatic
    fun maxMemory(runtime: Runtime): Long = runtime.maxMemory()

    @JvmStatic
    fun totalMemory(runtime: Runtime): Long = runtime.totalMemory()

    @JvmStatic
    fun freeMemory(runtime: Runtime): Long = runtime.freeMemory()
}
