package com.lagradost.runtime.loader.stubs

import java.lang.reflect.AccessibleObject
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/** Bytecode compatibility shims that preserve normal JVM reflection behavior. */
object ReflectionStub {
    @JvmStatic
    fun isReflectionAllowed(targetClass: Class<*>, memberName: String? = null): Boolean = true

    @JvmStatic
    fun invoke(method: Method, obj: Any?, args: Array<Any?>?): Any? =
        method.invoke(obj, *(args ?: emptyArray()))

    @JvmStatic
    fun get(field: Field, obj: Any?): Any? = field.get(obj)

    @JvmStatic
    fun set(field: Field, obj: Any?, value: Any?) = field.set(obj, value)

    @JvmStatic
    fun newInstance(constructor: Constructor<*>, args: Array<Any?>?): Any =
        constructor.newInstance(*(args ?: emptyArray()))

    @JvmStatic
    fun setAccessible(accessibleObject: AccessibleObject, flag: Boolean) {
        accessibleObject.isAccessible = flag
    }
}
