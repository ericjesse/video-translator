package com.ericjesse.videotranslator.domain.result

import com.ericjesse.videotranslator.domain.exception.ConfigurationException
import com.ericjesse.videotranslator.domain.exception.DomainException
import com.ericjesse.videotranslator.domain.exception.OperationTimeoutException
import com.ericjesse.videotranslator.domain.exception.ResourceNotFoundException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class OperationResultTest {

    // Test exception for use in tests
    private val testException = ResourceNotFoundException(
        resourceName = "test-resource",
        userMessage = "Test resource not found"
    )

    // ==================== Creation Tests ====================

    @Nested
    inner class CreationTest {

        @Test
        fun `success creates Success result`() {
            val result = OperationResult.success("hello")

            assertTrue(result.isSuccess)
            assertFalse(result.isFailure)
            assertEquals("hello", result.getOrNull())
        }

        @Test
        fun `failure creates Failure result`() {
            val result = OperationResult.failure<String>(testException)

            assertFalse(result.isSuccess)
            assertTrue(result.isFailure)
            assertEquals(testException, result.exceptionOrNull())
        }

        @Test
        fun `runCatching returns Success when block succeeds`() {
            val result = OperationResult.runCatching { 42 }

            assertTrue(result.isSuccess)
            assertEquals(42, result.getOrNull())
        }

        @Test
        fun `runCatching returns Failure when DomainException is thrown`() {
            val result = OperationResult.runCatching<Int> {
                throw testException
            }

            assertTrue(result.isFailure)
            assertEquals(testException, result.exceptionOrNull())
        }

        @Test
        fun `runCatching rethrows non-DomainException`() {
            assertFailsWith<IllegalArgumentException> {
                OperationResult.runCatching<Int> {
                    throw IllegalArgumentException("not a domain exception")
                }
            }
        }
    }

    // ==================== Accessor Tests ====================

    @Nested
    inner class AccessorTest {

        @Test
        fun `getOrNull returns value for Success`() {
            val result = OperationResult.success("value")
            assertEquals("value", result.getOrNull())
        }

        @Test
        fun `getOrNull returns null for Failure`() {
            val result = OperationResult.failure<String>(testException)
            assertNull(result.getOrNull())
        }

        @Test
        fun `exceptionOrNull returns null for Success`() {
            val result = OperationResult.success("value")
            assertNull(result.exceptionOrNull())
        }

        @Test
        fun `exceptionOrNull returns exception for Failure`() {
            val result = OperationResult.failure<String>(testException)
            assertEquals(testException, result.exceptionOrNull())
        }

        @Test
        fun `getOrThrow returns value for Success`() {
            val result = OperationResult.success("value")
            assertEquals("value", result.getOrThrow())
        }

        @Test
        fun `getOrThrow throws exception for Failure`() {
            val result = OperationResult.failure<String>(testException)

            val thrown = assertFailsWith<ResourceNotFoundException> {
                result.getOrThrow()
            }
            assertEquals(testException, thrown)
        }

        @Test
        fun `getOrDefault returns value for Success`() {
            val result = OperationResult.success("value")
            assertEquals("value", result.getOrDefault("default"))
        }

        @Test
        fun `getOrDefault returns default for Failure`() {
            val result = OperationResult.failure<String>(testException)
            assertEquals("default", result.getOrDefault("default"))
        }

        @Test
        fun `getOrElse returns value for Success`() {
            val result = OperationResult.success("value")
            assertEquals("value", result.getOrElse { "computed" })
        }

        @Test
        fun `getOrElse computes default for Failure`() {
            val result = OperationResult.failure<String>(testException)
            assertEquals(
                "computed from: Test resource not found",
                result.getOrElse { e -> "computed from: ${e.userMessage}" })
        }
    }

    // ==================== Transform Tests ====================

    @Nested
    inner class TransformTest {

        @Test
        fun `map transforms Success value`() {
            val result = OperationResult.success(5)
            val mapped = result.map { it * 2 }

            assertTrue(mapped.isSuccess)
            assertEquals(10, mapped.getOrNull())
        }

        @Test
        fun `map preserves Failure`() {
            val result = OperationResult.failure<Int>(testException)
            val mapped = result.map { it * 2 }

            assertTrue(mapped.isFailure)
            assertEquals(testException, mapped.exceptionOrNull())
        }

        @Test
        fun `flatMap chains Success operations`() {
            val result = OperationResult.success(5)
            val flatMapped = result.flatMap { OperationResult.success(it * 2) }

            assertTrue(flatMapped.isSuccess)
            assertEquals(10, flatMapped.getOrNull())
        }

        @Test
        fun `flatMap propagates inner Failure`() {
            val innerException = OperationTimeoutException("inner", 1000)
            val result = OperationResult.success(5)
            val flatMapped: OperationResult<Int> = result.flatMap { OperationResult.failure(innerException) }

            assertTrue(flatMapped.isFailure)
            assertEquals(innerException, flatMapped.exceptionOrNull())
        }

        @Test
        fun `flatMap preserves outer Failure`() {
            val result = OperationResult.failure<Int>(testException)
            val flatMapped = result.flatMap { OperationResult.success(it * 2) }

            assertTrue(flatMapped.isFailure)
            assertEquals(testException, flatMapped.exceptionOrNull())
        }

        @Test
        fun `mapFailure transforms Failure exception`() {
            val result = OperationResult.failure<String>(testException)
            val mapped = result.mapFailure { OperationTimeoutException("mapped", 1000) }

            assertTrue(mapped.isFailure)
            assertTrue(mapped.exceptionOrNull() is OperationTimeoutException)
        }

        @Test
        fun `mapFailure preserves Success`() {
            val result = OperationResult.success("value")
            val mapped = result.mapFailure { OperationTimeoutException("mapped", 1000) }

            assertTrue(mapped.isSuccess)
            assertEquals("value", mapped.getOrNull())
        }
    }

    // ==================== Side Effect Tests ====================

    @Nested
    inner class SideEffectTest {

        @Test
        fun `onSuccess executes action for Success`() {
            var executed = false
            var receivedValue: String? = null

            val result = OperationResult.success("value")
            result.onSuccess {
                executed = true
                receivedValue = it
            }

            assertTrue(executed)
            assertEquals("value", receivedValue)
        }

        @Test
        fun `onSuccess does not execute action for Failure`() {
            var executed = false

            val result = OperationResult.failure<String>(testException)
            result.onSuccess { executed = true }

            assertFalse(executed)
        }

        @Test
        fun `onFailure executes action for Failure`() {
            var executed = false
            var receivedException: DomainException? = null

            val result = OperationResult.failure<String>(testException)
            result.onFailure {
                executed = true
                receivedException = it
            }

            assertTrue(executed)
            assertEquals(testException, receivedException)
        }

        @Test
        fun `onFailure does not execute action for Success`() {
            var executed = false

            val result = OperationResult.success("value")
            result.onFailure { executed = true }

            assertFalse(executed)
        }

        @Test
        fun `onSuccess and onFailure can be chained`() {
            var successExecuted = false
            var failureExecuted = false

            val result = OperationResult.success("value")
            result
                .onSuccess { successExecuted = true }
                .onFailure { failureExecuted = true }

            assertTrue(successExecuted)
            assertFalse(failureExecuted)
        }
    }

    // ==================== Fold Tests ====================

    @Nested
    inner class FoldTest {

        @Test
        fun `fold executes onSuccess for Success`() {
            val result = OperationResult.success(5)
            val folded = result.fold(
                onSuccess = { "Success: $it" },
                onFailure = { "Failure: ${it.userMessage}" }
            )

            assertEquals("Success: 5", folded)
        }

        @Test
        fun `fold executes onFailure for Failure`() {
            val result = OperationResult.failure<Int>(testException)
            val folded = result.fold(
                onSuccess = { "Success: $it" },
                onFailure = { "Failure: ${it.userMessage}" }
            )

            assertEquals("Failure: Test resource not found", folded)
        }
    }

    // ==================== Recovery Tests ====================

    @Nested
    inner class RecoveryTest {

        @Test
        fun `recover provides alternative value for Failure`() {
            val result = OperationResult.failure<String>(testException)
            val recovered = result.recover { "recovered" }

            assertTrue(recovered.isSuccess)
            assertEquals("recovered", recovered.getOrNull())
        }

        @Test
        fun `recover preserves Success`() {
            val result = OperationResult.success("original")
            val recovered = result.recover { "recovered" }

            assertTrue(recovered.isSuccess)
            assertEquals("original", recovered.getOrNull())
        }

        @Test
        fun `recoverWith provides alternative result for Failure`() {
            val result = OperationResult.failure<String>(testException)
            val recovered = result.recoverWith { OperationResult.success("recovered") }

            assertTrue(recovered.isSuccess)
            assertEquals("recovered", recovered.getOrNull())
        }

        @Test
        fun `recoverWith can return Failure`() {
            val newException = OperationTimeoutException("recovery", 1000)
            val result = OperationResult.failure<String>(testException)
            val recovered = result.recoverWith { OperationResult.failure(newException) }

            assertTrue(recovered.isFailure)
            assertEquals(newException, recovered.exceptionOrNull())
        }

        @Test
        fun `recoverWith preserves Success`() {
            val result = OperationResult.success("original")
            val recovered = result.recoverWith { OperationResult.success("recovered") }

            assertTrue(recovered.isSuccess)
            assertEquals("original", recovered.getOrNull())
        }
    }

    // ==================== Zip Tests ====================

    @Nested
    inner class ZipTest {

        @Test
        fun `zip combines two Success results`() {
            val result1 = OperationResult.success(5)
            val result2 = OperationResult.success(10)
            val zipped = result1.zip(result2) { a, b -> a + b }

            assertTrue(zipped.isSuccess)
            assertEquals(15, zipped.getOrNull())
        }

        @Test
        fun `zip returns first Failure`() {
            val result1 = OperationResult.failure<Int>(testException)
            val result2 = OperationResult.success(10)
            val zipped = result1.zip(result2) { a, b -> a + b }

            assertTrue(zipped.isFailure)
            assertEquals(testException, zipped.exceptionOrNull())
        }

        @Test
        fun `zip returns second Failure when first is Success`() {
            val otherException = OperationTimeoutException("other", 1000)
            val result1 = OperationResult.success(5)
            val result2 = OperationResult.failure<Int>(otherException)
            val zipped = result1.zip(result2) { a, b -> a + b }

            assertTrue(zipped.isFailure)
            assertEquals(otherException, zipped.exceptionOrNull())
        }
    }

    // ==================== Sequence Tests ====================

    @Nested
    inner class SequenceTest {

        @Test
        fun `sequence combines list of Success results`() {
            val results = listOf(
                OperationResult.success(1),
                OperationResult.success(2),
                OperationResult.success(3)
            )
            val sequenced = results.sequence()

            assertTrue(sequenced.isSuccess)
            assertEquals(listOf(1, 2, 3), sequenced.getOrNull())
        }

        @Test
        fun `sequence returns first Failure`() {
            val results = listOf(
                OperationResult.success(1),
                OperationResult.failure(testException),
                OperationResult.success(3)
            )
            val sequenced = results.sequence()

            assertTrue(sequenced.isFailure)
            assertEquals(testException, sequenced.exceptionOrNull())
        }

        @Test
        fun `sequence returns Success for empty list`() {
            val results = emptyList<OperationResult<Int>>()
            val sequenced = results.sequence()

            assertTrue(sequenced.isSuccess)
            assertEquals(emptyList(), sequenced.getOrNull())
        }
    }

    // ==================== Pattern Matching Tests ====================

    @Nested
    inner class PatternMatchingTest {

        @Test
        fun `when expression works with Success`() {
            val result: OperationResult<Int> = OperationResult.success(42)

            val message = when (result) {
                is OperationResult.Success -> "Got ${result.value}"
                is OperationResult.Failure -> "Failed: ${result.exception.userMessage}"
            }

            assertEquals("Got 42", message)
        }

        @Test
        fun `when expression works with Failure`() {
            val result: OperationResult<Int> = OperationResult.failure(testException)

            val message = when (result) {
                is OperationResult.Success -> "Got ${result.value}"
                is OperationResult.Failure -> "Failed: ${result.exception.userMessage}"
            }

            assertEquals("Failed: Test resource not found", message)
        }
    }

    // ==================== Real-world Usage Tests ====================

    @Nested
    inner class RealWorldUsageTest {

        @Test
        fun `chain of operations with map and flatMap`() {
            fun fetchValue(): OperationResult<Int> = OperationResult.success(10)
            fun process(value: Int): OperationResult<String> =
                if (value > 0) OperationResult.success("processed: $value")
                else OperationResult.failure(ConfigurationException("value", userMessage = "Value must be positive"))

            val result = fetchValue()
                .map { it * 2 }
                .flatMap { process(it) }

            assertTrue(result.isSuccess)
            assertEquals("processed: 20", result.getOrNull())
        }

        @Test
        fun `error handling with recovery`() {
            fun riskyOperation(): OperationResult<String> =
                OperationResult.failure(OperationTimeoutException("risky", 1000))

            val result = riskyOperation()
                .recover { "fallback value" }

            assertTrue(result.isSuccess)
            assertEquals("fallback value", result.getOrNull())
        }

        @Test
        fun `collecting results from multiple operations`() {
            fun operation1(): OperationResult<Int> = OperationResult.success(1)
            fun operation2(): OperationResult<Int> = OperationResult.success(2)
            fun operation3(): OperationResult<Int> = OperationResult.success(3)

            val results = listOf(operation1(), operation2(), operation3()).sequence()
            val sum = results.map { it.sum() }

            assertTrue(sum.isSuccess)
            assertEquals(6, sum.getOrNull())
        }
    }
}
