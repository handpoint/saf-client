package com.handpoint.safclient.testutil

import com.handpoint.safclient.domain.ErrorReporter

/** [ErrorReporter] that keeps every report, so tests can assert on them. */
class RecordingErrorReporter : ErrorReporter {
    val reports = mutableListOf<Pair<String, Throwable>>()

    override fun report(where: String, error: Throwable) {
        reports += where to error
    }

    /**
     * Each report as (where, message). Compare these, not the exceptions: coroutines may hand
     * over a copy of an exception (stack-trace recovery), so identity is not kept.
     */
    fun summary(): List<Pair<String, String?>> = reports.map { (where, error) -> where to error.message }
}
