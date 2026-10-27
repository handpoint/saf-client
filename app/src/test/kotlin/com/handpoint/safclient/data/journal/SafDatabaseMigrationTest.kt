package com.handpoint.safclient.data.journal

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.handpoint.safclient.domain.model.ProcessingStatus
import com.handpoint.safclient.domain.model.SaleChannel
import com.handpoint.safclient.domain.model.SaleReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Opens a database written by version 1 of the app with the current Room schema. Room checks
 * the migrated table against the entity when it opens, so a migration that leaves the table in
 * another shape fails here.
 */
@RunWith(RobolectricTestRunner::class)
class SafDatabaseMigrationTest {
    private val context = RuntimeEnvironment.getApplication() as Context
    private val name = "migration-test.db"

    @Before
    fun setUp() {
        context.deleteDatabase(name)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test
    fun version1SalesBecomeOfflineSales() = runBlocking {
        createVersion1Database()

        val db = Room.databaseBuilder(context, SafDatabase::class.java, name)
            .addMigrations(SafDatabase.MIGRATION_1_2, SafDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            val sale = RoomSalesJournal(db.saleDao()).find(SaleReference(REFERENCE))!!
            assertEquals(SaleChannel.OFFLINE, sale.channel)
            assertEquals(ProcessingStatus.STORED, sale.status)
            assertEquals(1250L, sale.amount.minorUnits)
        } finally {
            db.close()
        }
    }

    /** The version 1 table exactly as `schemas/.../1.json` describes it, with one stored sale. */
    private fun createVersion1Database() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `sales` (`reference` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, " +
                            "`currencyCode` TEXT NOT NULL, `maskedCard` TEXT, `entryMode` TEXT NOT NULL, " +
                            "`approvedAtEpochMs` INTEGER NOT NULL, `sentAtEpochMs` INTEGER, `status` TEXT NOT NULL, " +
                            "`statusCheckedAtEpochMs` INTEGER, `merchantReceipt` TEXT, `customerReceipt` TEXT, " +
                            "PRIMARY KEY(`reference`))",
                    )
                    db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                    db.execSQL(
                        "INSERT OR REPLACE INTO room_master_table (id,identity_hash) " +
                            "VALUES(42, 'e7164fee16225e0c6d02c0ffedbe375e')",
                    )
                    db.execSQL(
                        "INSERT INTO sales VALUES ('$REFERENCE', 1250, 'USD', '•••• 4242', 'CHIP', 1000, NULL, " +
                            "'STORED', NULL, NULL, NULL)",
                    )
                }

                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    Unit
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(config).run {
            writableDatabase
            close()
        }
    }

    private companion object {
        const val REFERENCE = "6f1c2a52-3a4f-4b9e-9a51-7d2b8c0e4f11"
    }
}
