package com.handpoint.safclient.domain

import kotlin.coroutines.cancellation.CancellationException

/**
 * Port for errors the app recovers from but someone should be able to see later.
 * [where] names the work that failed, for example "reconcile".
 */
interface ErrorReporter {
    fun report(where: String, error: Throwable)
}

/**
 * Runs [block] as best-effort work: a failure is reported under [where] and gives `null`,
 * so the screen keeps going. Cancellation is not a failure and passes through.
 */
inline fun <T> ErrorReporter.reportingErrors(where: String, block: () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        report(where, e)
        null
    }
