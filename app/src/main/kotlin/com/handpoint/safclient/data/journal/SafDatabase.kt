package com.handpoint.safclient.data.journal

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The local journal of sales.
 *
 * Version 3 adds the surcharge columns ([MIGRATION_2_3]); a sale without a surcharge keeps them NULL.
 * Version 2 adds `channel`: how each sale was taken (online or offline). Version 1 only took
 * offline sales, so [MIGRATION_1_2] marks every existing sale as offline.
 */
@Database(entities = [SaleEntity::class], version = 5, exportSchema = true)
abstract class SafDatabase : RoomDatabase() {
    abstract fun saleDao(): SaleDao

    companion object {
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sales ADD COLUMN moto INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sales ADD COLUMN transactionId TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sales ADD COLUMN surchargePercent TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE sales ADD COLUMN surchargeSentMinor INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE sales ADD COLUMN surchargeApplied INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE sales ADD COLUMN surchargeReason TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sales ADD COLUMN channel TEXT NOT NULL DEFAULT 'OFFLINE'")
            }
        }
    }
}
