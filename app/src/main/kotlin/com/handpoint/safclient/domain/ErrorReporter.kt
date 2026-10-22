package com.handpoint.safclient.domain

/**
 * Port for errors the app recovers from but that someone should be able to see later.
 * [where] names the work that failed, for example "reconcile".
 */
interface ErrorReporter {
    fun report(where: String, error: Throwable)
}
