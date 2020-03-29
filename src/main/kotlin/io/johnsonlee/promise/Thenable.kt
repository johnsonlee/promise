package io.johnsonlee.promise

typealias Function<I, O> = (I) -> O

typealias Fulfillment<I> = Function<I, Unit>

typealias Rejection<E> = Function<E, Unit>

typealias Finalization = Function<Unit, Unit>

/**
 * @author johnsonlee
 */
interface Thenable<I, E : Throwable> {

    /**
     * @param <I> The argument type of [onFulfilled] and [onRejected]
     * @param <R> the return type of [onFulfilled]
     * @param <E> the return type of [onRejected]
     */
    fun <R> then(onFulfilled: Function<I, R>, onRejected: Rejection<E> = {}): Thenable<R, E>

    fun <R> then(onFulfilled: Function<I, R>): Thenable<R, E>

    fun catch(onRejected: Rejection<E>): Thenable<I, E>

    fun finally(onFinally: Finalization): Promise<I, E>

}