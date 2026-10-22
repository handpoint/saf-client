package com.handpoint.safclient

import android.app.Application
import com.handpoint.safclient.domain.PaymentModeCoordinator
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * The application. It starts the payment mode rule ([PaymentModeCoordinator]) when the process
 * starts, so the app follows the network and switches between online and offline sales whatever
 * screen opens first. `start()` only launches work in the app scope: it does not create the SDK.
 */
@HiltAndroidApp
class SafClientApp : Application() {

    @Inject
    lateinit var payments: PaymentModeCoordinator

    override fun onCreate() {
        super.onCreate()
        payments.start()
    }
}
