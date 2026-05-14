/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.shuttle

import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import java.lang.reflect.Modifier

internal typealias CtxFun<R> = Context.() -> R

internal class Closure(private val functionClass: Class<CtxFun<*>>,
                       private val variables: Array<Any?>) : Parcelable {

    /** Invoke the deserialized closure with the given context. */
    fun invoke(context: Context): Any? {
        val constructor = functionClass.declaredConstructors[0].apply { isAccessible = true }
        val args: Array<Any?> = constructor.parameterTypes.map(::getDefaultValue).toTypedArray()
        val block = constructor.newInstance(*args) as CtxFun<*>
        val memberFields = block.javaClass.declaredFields.filter {
            !Modifier.isStatic(it.modifiers)
        }.onEach { it.isAccessible = true }
        memberFields.forEachIndexed { index, field ->
            field.set(block, when (field.type) {
                Context::class.java -> context
                Closure::class.java -> (variables[index] as? Closure)?.invoke(context)
                else -> variables[index]
            })
        }
        return block(context)
    }

    constructor(procedure: CtxFun<*>) : this(
        procedure.javaClass,
        procedure.javaClass.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) }
            .onEach { it.isAccessible = true }
            .map { f ->
                val v = f.get(procedure)
                if (v is Context) null else v
            }
            .toTypedArray()
    )

    private fun getDefaultValue(type: Class<*>) =
        if (type.isPrimitive) java.lang.reflect.Array.get(java.lang.reflect.Array.newInstance(type, 1), 0)
        else null

    override fun toString() = "Closure{${functionClass.name}}"
    override fun describeContents() = 0
    override fun writeToParcel(dest: Parcel, flags: Int) =
        dest.run { writeString(functionClass.name); writeArray(variables) }

    @Suppress("UNCHECKED_CAST")
    constructor(parcel: Parcel, cl: ClassLoader) : this(
        cl.loadClass(parcel.readString()) as Class<CtxFun<*>>,
        parcel.readArray(cl)!!
    )

    companion object CREATOR : Parcelable.ClassLoaderCreator<Closure> {
        override fun createFromParcel(parcel: Parcel, classLoader: ClassLoader) = Closure(parcel, classLoader)
        override fun createFromParcel(parcel: Parcel) = Closure(parcel, Closure::class.java.classLoader!!)
        override fun newArray(size: Int): Array<Closure?> = arrayOfNulls(size)
    }
}
