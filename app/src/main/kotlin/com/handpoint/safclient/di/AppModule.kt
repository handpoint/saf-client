package com.handpoint.safclient.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.handpoint.api.HandpointCredentials
import com.handpoint.api.HapiFactory
import com.handpoint.api.Settings
import com.handpoint.safclient.BuildConfig
import com.handpoint.safclient.data.handpoint.HandpointSafTerminal
import com.handpoint.safclient.data.journal.RoomSalesJournal
import com.handpoint.safclient.data.journal.SafDatabase
import com.handpoint.safclient.data.journal.SaleDao
import com.handpoint.safclient.data.settings.AppSettings
import com.handpoint.safclient.data.logging.LogcatErrorReporter
import com.handpoint.safclient.domain.AppPreferences
import com.handpoint.safclient.domain.ErrorReporter
import com.handpoint.safclient.domain.ApplicationScope
import com.handpoint.safclient.domain.SalesJournal
import com.handpoint.safclient.domain.StoreAndForwardTerminal
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Clock
import javax.inject.Singleton

/** App-wide bindings. The only place outside `data/handpoint/` that touches the Handpoint SDK. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * The terminal. Creating this object is cheap; the SDK itself is created on the first
     * `connect()` (or other member call), which is heavy: callers do that off the main thread.
     */
    @Provides
    @Singleton
    fun provideTerminal(clock: Clock): StoreAndForwardTerminal = HandpointSafTerminal(
        apiFactory = { delegate ->
            HapiFactory.getStoreAndForwardPaymentsApi(
                delegate,
                HandpointCredentials(BuildConfig.SHARED_SECRET),
                Settings(),
            )
        },
        clock = clock,
    )

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SafDatabase =
        Room.databaseBuilder(context, SafDatabase::class.java, "saf-client.db").build()

    @Provides
    fun provideSaleDao(database: SafDatabase): SaleDao = database.saleDao()

    @Provides
    @Singleton
    fun provideJournal(dao: SaleDao): SalesJournal = RoomSalesJournal(dao)

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

    @Provides
    @Singleton
    fun providePreferences(dataStore: DataStore<Preferences>): AppPreferences =
        AppSettings(dataStore, BuildConfig.SAF_CURRENCY)

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemDefaultZone()

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(reporter: ErrorReporter): CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            // Safety net: an error in app-wide work is reported, not a crash.
            CoroutineExceptionHandler { _, error -> reporter.report("app scope", error) },
    )

    @Provides
    @Singleton
    fun provideErrorReporter(): ErrorReporter = LogcatErrorReporter()

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
