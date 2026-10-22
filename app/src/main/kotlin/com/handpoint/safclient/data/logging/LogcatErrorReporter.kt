package com.handpoint.safclient.data.logging

import android.util.Log
import com.handpoint.safclient.domain.ErrorReporter

/** Writes reported errors to Logcat under the tag [TAG]. */
class LogcatErrorReporter : ErrorReporter {
    override fun report(where: String, error: Throwable) {
        Log.e(TAG, "Error in $where", error)
    }

    private companion object {
        const val TAG = "saf-client"
    }
}
