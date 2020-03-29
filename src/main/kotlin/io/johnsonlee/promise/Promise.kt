package io.johnsonlee.promise

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray

private val NCPU = Runtime.getRuntime().availableProcessors()

internal typealias Tether<I, E> = (Function<I, Unit>, Function<E, Unit>) -> Unit

fun <I, E : Throwable> Thenable<I, E>.toTether() = when (this) {
    is Promise<I, E> -> this.tether
    else -> { resolve, reject ->
        then(resolve, reject)
    }
}

/**
 * @author johnsonlee
 */
class Promise<I, E : Throwable> : Thenable<I, E> {

    private val status = AtomicReference<Status>(Status.PENDING)

    private val value = AtomicReference<I>()

    private val reason = AtomicReference<E>()

    private val executor: ExecutorService

    internal val tether: Tether<I, E>

    constructor(thenable: Thenable<I, E>) : this(thenable.toTether())

    constructor(tether: Tether<I, E>) {
        this.executor = DEFAULT_EXECUTOR
        this.tether = { resolve, reject ->
            tether({
                this.status.compareAndSet(Status.PENDING, Status.FULFILLED)
                this.value.compareAndSet(null, it)
                resolve(it)
            }, {
                this.status.compareAndSet(Status.PENDING, Status.REJECTED)
                this.reason.compareAndSet(null, it)
                reject(it)
            })
        }
    }

    private constructor(reason: E) {
        this.status.set(Status.REJECTED)
        this.reason.set(reason)
        this.executor = DEFAULT_EXECUTOR
        this.tether = { _, reject ->
            reject(reason)
        }
    }

    private constructor(value: I) {
        this.status.set(Status.FULFILLED)
        this.value.set(value)
        this.executor = DEFAULT_EXECUTOR
        this.tether = { resolve, _ ->
            resolve(value)
        }
    }

    companion object {

        private val counter = AtomicLong(0)

        val DEFAULT_THREAD_FACTORY = ThreadFactory {
            Thread(it, "promise-${counter.getAndIncrement()}").apply {
                isDaemon = true
            }
        }

        private val DEFAULT_EXECUTOR = Executors.newCachedThreadPool(DEFAULT_THREAD_FACTORY)

        fun <E : Throwable> resolve(): Promise<Unit, E> {
            return resolve(Unit)
        }

        fun <I, E : Throwable> resolve(value: I): Promise<I, E> {
            return Promise(value)
        }

        fun <I, E : Throwable> reject(reason: E): Promise<I, E> {
            return Promise(reason)
        }

        @JvmName("allWithThenables")
        inline fun <reified I, reified E : Throwable> all(thenables: Iterable<Thenable<I, E>>): Promise<List<I>, E> {
            if (!thenables.iterator().hasNext()) {
                return resolve(emptyList())
            }

            return compose(thenables.map(Thenable<I, E>::toTether))
        }

        inline fun <reified I, reified E : Throwable> all(vararg thenables: Thenable<I, E>): Promise<List<I>, E> {
            return all(thenables.asIterable())
        }

        @JvmName("allWithTethers")
        inline fun <reified I, reified E : Throwable> all(tethers: Iterable<Tether<I, E>>): Promise<List<I>, E> {
            if (!tethers.iterator().hasNext()) {
                return resolve(emptyList())
            }

            return compose(tethers)
        }

        inline fun <reified I, reified E : Throwable> all(vararg tethers: Tether<I, E>): Promise<List<I>, E> {
            return this.all(tethers.asIterable())
        }

        inline fun <reified I, E : Throwable> compose(tethers: Iterable<Tether<I, E>>) = Promise<List<I>, E> { onFulfilled, onRejected ->
            val count = tethers.count()
            val values = AtomicReferenceArray<I>(arrayOfNulls<I>(count))
            val failed = AtomicReference<E?>()
            val executor = Executors.newCachedThreadPool(DEFAULT_THREAD_FACTORY)
            val signal = CountDownLatch(count)

            tethers.mapIndexed { index, tether ->
                executor.submit {
                    val reject: Rejection<E> = { reason ->
                        signal.countDown()
                        failed.set(reason)
                        repeat(executor.shutdownNow().size) {
                            signal.countDown()
                        }
                    }
                    val resolve: Fulfillment<I> = { value ->
                        signal.countDown()
                        values.set(index, value)
                    }
                    tether(resolve, reject)
                }
            }.forEach {
                it.get()
            }

            signal.await()
            executor.shutdown()
            executor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
            failed.get()?.let(onRejected) ?: onFulfilled((0 until count).map { values[it] })
        }

    }

    override fun <R> then(onFulfilled: Function<I, R>, onRejected: Rejection<E>): Promise<R, E> {
        return when (this.status.get()) {
            Status.PENDING -> {
                val signal = CountDownLatch(1)
                val value = AtomicReference<R>()
                val reason = AtomicReference<E>()

                executor.execute {
                    this.tether({ v ->
                        executor.submit {
                            value.set(onFulfilled(v))
                            signal.countDown()
                        }
                    }, { e ->
                        executor.submit {
                            onRejected(e)
                            reason.set(e)
                            signal.countDown()
                        }
                    })
                }

                signal.await()

                Promise<R, E> { resolve, reject ->
                    when (this.status.get()) {
                        Status.FULFILLED -> {
                            onFulfilled(this.value.get()).let { result ->
                                @Suppress("UNCHECKED_CAST")
                                when (result) {
                                    is Promise<*, *> -> {
                                        result.tether(resolve as Fulfillment<Any?>, reject as Rejection<Throwable>)
                                    }
                                    else -> {
                                        resolve(result)
                                    }
                                }
                            }
                        }
                        Status.REJECTED -> {
                            this.reason.get().run {
                                onRejected(this)
                                reject(this)
                            }
                        }
                    }
                }

            }
            Status.FULFILLED -> Promise<R, E> { resolve, _ ->
                executor.execute {
                    resolve(onFulfilled(this.value.get()))
                }
            }
            Status.REJECTED -> Promise<R, E> { _, reject ->
                executor.execute {
                    onRejected(this.reason.get())
                    reject(this.reason.get())
                }
            }
        }
    }

    override fun <R> then(onFulfilled: Function<I, R>) = then(onFulfilled, {})

    override fun catch(onRejected: Rejection<E>): Promise<I, E> = then({ value: I ->
        value
    }, onRejected)

    override fun finally(onFinally: Finalization): Promise<I, E> = then({ value: I ->
        onFinally(Unit)
        value
    }, {
        onFinally(Unit)
    })

    @Suppress("HasPlatformType")
    operator fun invoke(): I = when (this.status.get()) {
        Status.FULFILLED -> this.value.get()
        Status.REJECTED -> throw this.reason.get()
        Status.PENDING -> {
            val signal = CountDownLatch(1)

            executor.submit {
                this.tether({ _ ->
                    signal.countDown()
                }, { _ ->
                    signal.countDown()
                })
            }

            signal.await()

            when (this.status.get()) {
                Status.FULFILLED -> this.value.get()
                Status.REJECTED -> throw this.reason.get()
                else -> throw IllegalStateException("Invalid status ${this.status.get()}")
            }
        }
    }

}
