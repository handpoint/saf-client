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
import com.handpoint.safclient.data.handpoint.HandpointOnlineTerminal
import com.handpoint.safclient.data.handpoint.HandpointSafTerminal
import com.handpoint.safclient.data.journal.RoomSalesJournal
import com.handpoint.safclient.data.journal.SafDatabase
import com.handpoint.safclient.data.journal.SaleDao
import com.handpoint.safclient.data.network.AndroidNetworkMonitor
import com.handpoint.safclient.data.settings.AppSettings
import com.handpoint.safclient.data.logging.LogcatErrorReporter
import com.handpoint.safclient.domain.AppPreferences
import com.handpoint.safclient.domain.ErrorReporter
import com.handpoint.safclient.domain.ApplicationScope
import com.handpoint.safclient.domain.NetworkMonitor
import com.handpoint.safclient.domain.OnlineTerminal
import com.handpoint.safclient.domain.PaymentModeCoordinator
import com.handpoint.safclient.domain.SalesJournal
import com.handpoint.safclient.domain.StoreAndForwardTerminal
import com.handpoint.safclient.domain.usecase.ReconcileStoredSales
import com.handpoint.safclient.domain.usecase.RefreshOpenStatuses
import com.handpoint.safclient.domain.usecase.SendStoredSales
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

    /**
     * The online API. Created only when the app switches to online sales: creating it replaces
     * the Store and Forward API in the SDK (see `PaymentTerminal`).
     */
    @Provides
    @Singleton
    fun provideOnlineTerminal(clock: Clock): OnlineTerminal = HandpointOnlineTerminal(
        apiFactory = { delegate ->
            HapiFactory.getAsyncInterface(
                delegate,
                credentials = HandpointCredentials(BuildConfig.SHARED_SECRET),
                settings = Settings(),
            )
        },
        clock = clock,
    )

    /** Chooses online or Store and Forward and switches between them (see its KDoc). */
    @Provides
    @Singleton
    @Suppress("LongParameterList") // one dependency per input of the mode rule
    fun providePaymentModeCoordinator(
        saf: StoreAndForwardTerminal,
        online: OnlineTerminal,
        network: NetworkMonitor,
        journal: SalesJournal,
        reconcile: ReconcileStoredSales,
        send: SendStoredSales,
        statuses: RefreshOpenStatuses,
        errors: ErrorReporter,
        @ApplicationScope scope: CoroutineScope,
        @IoDispatcher io: CoroutineDispatcher,
    ): PaymentModeCoordinator = PaymentModeCoordinator(saf, online, network, journal, reconcile, send, statuses, errors, scope, io)

    @Provides
    @Singleton
    fun provideNetworkMonitor(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
    ): NetworkMonitor = AndroidNetworkMonitor(context, scope)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SafDatabase =
        Room.databaseBuilder(context, SafDatabase::class.java, "saf-client.db")
            .addMigrations(SafDatabase.MIGRATION_1_2)
            .build()

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
