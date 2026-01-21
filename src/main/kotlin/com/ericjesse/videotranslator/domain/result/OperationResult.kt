package com.ericjesse.videotranslator.domain.result

import com.ericjesse.videotranslator.domain.exception.DomainException

/**
 * A discriminated union representing the result of an operation that can fail.
 * Provides a type-safe way to handle success and failure cases without exceptions.
 *
 * @param T The type of the successful value.
 */
sealed class OperationResult<out T> {

    /**
     * Represents a successful operation with a value.
     *
     * @param value The successful result value.
     */
    data class Success<T>(val value: T) : OperationResult<T>()

    /**
     * Represents a failed operation with an exception.
     *
     * @param exception The domain exception that caused the failure.
     */
    data class Failure(val exception: DomainException) : OperationResult<Nothing>()

    /**
     * Returns true if this is a Success.
     */
    val isSuccess: Boolean get() = this is Success

    /**
     * Returns true if this is a Failure.
     */
    val isFailure: Boolean get() = this is Failure

    /**
     * Returns the value if Success, or null if Failure.
     */
    fun getOrNull(): T? = when (this) {
        is Success -> value
        is Failure -> null
    }

    /**
     * Returns the exception if Failure, or null if Success.
     */
    fun exceptionOrNull(): DomainException? = when (this) {
        is Success -> null
        is Failure -> exception
    }

    /**
     * Returns the value if Success, or throws the exception if Failure.
     */
    fun getOrThrow(): T = when (this) {
        is Success -> value
        is Failure -> throw exception
    }

    /**
     * Returns the value if Success, or the default value if Failure.
     *
     * @param default The default value to return on failure.
     */
    fun getOrDefault(default: @UnsafeVariance T): T = when (this) {
        is Success -> value
        is Failure -> default
    }

    /**
     * Returns the value if Success, or computes a default value if Failure.
     *
     * @param defaultValue Function to compute the default value.
     */
    inline fun getOrElse(defaultValue: (DomainException) -> @UnsafeVariance T): T = when (this) {
        is Success -> value
        is Failure -> defaultValue(exception)
    }

    /**
     * Transforms the value if Success, keeping Failure unchanged.
     *
     * @param transform Function to transform the success value.
     * @return A new OperationResult with the transformed value.
     */
    inline fun <R> map(transform: (T) -> R): OperationResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    /**
     * Transforms the value if Success with a function that returns an OperationResult.
     *
     * @param transform Function to transform the success value to another OperationResult.
     * @return The transformed OperationResult.
     */
    inline fun <R> flatMap(transform: (T) -> OperationResult<R>): OperationResult<R> = when (this) {
        is Success -> transform(value)
        is Failure -> this
    }

    /**
     * Transforms the exception if Failure, keeping Success unchanged.
     *
     * @param transform Function to transform the failure exception.
     * @return A new OperationResult with the transformed exception.
     */
    inline fun mapFailure(transform: (DomainException) -> DomainException): OperationResult<T> = when (this) {
        is Success -> this
        is Failure -> Failure(transform(exception))
    }

    /**
     * Executes the action if Success.
     *
     * @param action The action to execute with the success value.
     * @return This OperationResult for chaining.
     */
    inline fun onSuccess(action: (T) -> Unit): OperationResult<T> {
        if (this is Success) action(value)
        return this
    }

    /**
     * Executes the action if Failure.
     *
     * @param action The action to execute with the failure exception.
     * @return This OperationResult for chaining.
     */
    inline fun onFailure(action: (DomainException) -> Unit): OperationResult<T> {
        if (this is Failure) action(exception)
        return this
    }

    /**
     * Folds the result into a single value.
     *
     * @param onSuccess Function to execute on success.
     * @param onFailure Function to execute on failure.
     * @return The result of either function.
     */
    inline fun <R> fold(
        onSuccess: (T) -> R,
        onFailure: (DomainException) -> R,
    ): R = when (this) {
        is Success -> onSuccess(value)
        is Failure -> onFailure(exception)
    }

    /**
     * Recovers from a failure by providing an alternative value.
     *
     * @param recovery Function to recover from the failure.
     * @return Success with the original or recovered value.
     */
    inline fun recover(recovery: (DomainException) -> @UnsafeVariance T): OperationResult<T> = when (this) {
        is Success -> this
        is Failure -> Success(recovery(exception))
    }

    /**
     * Recovers from a failure by providing an alternative result.
     *
     * @param recovery Function to provide an alternative result.
     * @return The original Success or the recovery result.
     */
    inline fun recoverWith(recovery: (DomainException) -> OperationResult<@UnsafeVariance T>): OperationResult<T> =
        when (this) {
            is Success -> this
            is Failure -> recovery(exception)
        }

    companion object {
        /**
         * Creates a Success result.
         */
        fun <T> success(value: T): OperationResult<T> = Success(value)

        /**
         * Creates a Failure result.
         */
        fun <T> failure(exception: DomainException): OperationResult<T> = Failure(exception)

        /**
         * Wraps a potentially throwing block in an OperationResult.
         * Only catches DomainException; other exceptions are rethrown.
         *
         * @param block The block to execute.
         * @return Success with the block's result, or Failure if DomainException is thrown.
         */
        inline fun <T> runCatching(block: () -> T): OperationResult<T> =
            try {
                Success(block())
            } catch (e: DomainException) {
                Failure(e)
            }
    }
}

/**
 * Combines two OperationResults, returning Success only if both are successful.
 */
fun <A, B, R> OperationResult<A>.zip(
    other: OperationResult<B>,
    transform: (A, B) -> R,
): OperationResult<R> = when (this) {
    is OperationResult.Success -> when (other) {
        is OperationResult.Success -> OperationResult.Success(transform(this.value, other.value))
        is OperationResult.Failure -> other
    }

    is OperationResult.Failure -> this
}

/**
 * Combines a list of OperationResults into a single OperationResult with a list.
 * Returns Failure if any element fails.
 */
fun <T> List<OperationResult<T>>.sequence(): OperationResult<List<T>> {
    val results = mutableListOf<T>()
    for (result in this) {
        when (result) {
            is OperationResult.Success -> results.add(result.value)
            is OperationResult.Failure -> return result
        }
    }
    return OperationResult.Success(results)
}
