package io.johnsonlee.promise

/**
 * @author johnsonlee
 */
sealed class Status {

    /**
     * The initial state of [Promise]
     */
    object PENDING : Status()

    /**
     * The state that indicates the operation completed successfully
     */
    object FULFILLED : Status()

    /**
     * The state that indicates the operation failed
     */
    object REJECTED : Status()

}