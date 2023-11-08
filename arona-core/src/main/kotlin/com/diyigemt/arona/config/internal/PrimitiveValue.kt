package com.diyigemt.arona.config.internal

interface PrimitiveValue<T> : Value<T>

//interface ByteValue : PrimitiveValue<Byte>
//
//interface ShortValue : PrimitiveValue<Short>
//
//interface IntValue : PrimitiveValue<Int>
//
//interface LongValue : PrimitiveValue<Long>
//
//interface FloatValue : PrimitiveValue<Float>
//
//interface DoubleValue : PrimitiveValue<Double>
//
//interface CharValue : PrimitiveValue<Char>
//
//interface BooleanValue : PrimitiveValue<Boolean>
//
//interface StringValue : PrimitiveValue<String>

interface CompositeValue<T> : Value<T>

interface ListValue<E> : CompositeValue<List<E>>

interface CompositeListValue<E> : ListValue<E>

interface PrimitiveListValue<E> : ListValue<E>

interface PrimitiveIntListValue : PrimitiveListValue<Int>

interface PrimitiveLongListValue : PrimitiveListValue<Long>

interface SetValue<E> : CompositeValue<Set<E>>

interface CompositeSetValue<E> : SetValue<E>

interface PrimitiveSetValue<E> : SetValue<E>

interface PrimitiveIntSetValue : PrimitiveSetValue<Int>

interface PrimitiveLongSetValue : PrimitiveSetValue<Long>

interface MapValue<K, V> : CompositeValue<Map<K, V>>

interface CompositeMapValue<K, V> : MapValue<K, V>

interface PrimitiveMapValue<K, V> : MapValue<K, V>

interface PrimitiveIntIntMapValue : PrimitiveMapValue<Int, Int>

interface PrimitiveIntLongMapValue : PrimitiveMapValue<Int, Long>

interface ReferenceValue<T> : Value<T>
