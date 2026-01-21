package com.ericjesse.videotranslator.infrastructure.logging

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class StructuredLoggerTest {

    @Test
    fun `LogContextBuilder builds context string with single pair`() {
        val context = LogContextBuilder().apply {
            "key" with "value"
        }.build()

        assertEquals("key=value", context)
    }

    @Test
    fun `LogContextBuilder builds context string with multiple pairs`() {
        val context = LogContextBuilder().apply {
            "event" with "test"
            "count" with 42
            "success" with true
        }.build()

        assertEquals("event=test, count=42, success=true", context)
    }

    @Test
    fun `LogContextBuilder handles null values`() {
        val context = LogContextBuilder().apply {
            "key" with null
            "other" with "value"
        }.build()

        assertEquals("key=null, other=value", context)
    }

    @Test
    fun `LogContextBuilder returns empty string for no pairs`() {
        val context = LogContextBuilder().build()

        assertEquals("", context)
    }

    @Test
    fun `LogContextBuilder toMap returns correct map`() {
        val map = LogContextBuilder().apply {
            "a" with 1
            "b" with "two"
        }.toMap()

        assertEquals(2, map.size)
        assertEquals(1, map["a"])
        assertEquals("two", map["b"])
    }

    @Test
    fun `logContext helper creates formatted string`() {
        val result = logContext {
            "stage" with "download"
            "progress" with 50
        }

        assertEquals(" | stage=download, progress=50", result)
    }

    @Test
    fun `logContext helper returns empty string for no context`() {
        val result = logContext {}

        assertEquals("", result)
    }

    @Test
    fun `LogContextBuilder handles various types`() {
        val context = LogContextBuilder().apply {
            "string" with "hello"
            "int" with 42
            "long" with 1000L
            "double" with 3.14
            "boolean" with true
            "list" with listOf(1, 2, 3)
        }.build()

        assertTrue(context.contains("string=hello"))
        assertTrue(context.contains("int=42"))
        assertTrue(context.contains("long=1000"))
        assertTrue(context.contains("double=3.14"))
        assertTrue(context.contains("boolean=true"))
        assertTrue(context.contains("list=[1, 2, 3]"))
    }

    @Test
    fun `LogContextBuilder put method adds pairs`() {
        val context = LogContextBuilder().apply {
            put("key1", "value1")
            put("key2", 123)
        }.build()

        assertEquals("key1=value1, key2=123", context)
    }
}
