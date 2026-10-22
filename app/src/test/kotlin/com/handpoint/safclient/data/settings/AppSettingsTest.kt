package com.handpoint.safclient.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Currency

class AppSettingsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    private fun settings(defaultCurrency: String = "USD") = AppSettings(
        PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "settings.preferences_pb") },
        defaultCurrency,
    )

    @Test
    fun defaultsApplyWhenNothingStored() = runBlocking {
        val s = settings("EUR")

        assertEquals("My Store", s.merchantName.first())
        assertEquals(Currency.getInstance("EUR"), s.safCurrency.first())
    }

    @Test
    fun valuesRoundTrip() = runBlocking {
        val s = settings()

        s.setMerchantName("Corner Cafe")
        s.setSafCurrency(Currency.getInstance("GBP"))

        assertEquals("Corner Cafe", s.merchantName.first())
        assertEquals(Currency.getInstance("GBP"), s.safCurrency.first())
    }
}
