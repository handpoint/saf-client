package com.handpoint.safclient.data.journal

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The local journal of sales.
 *
 * Version 2 adds `channel`: how each sale was taken (online or offline). Version 1 only took
 * offline sales, so [MIGRATION_1_2] marks every existing sale as offline.
 */
@Database(entities = [SaleEntity::class], version = 2, exportSchema = true)
abstract class SafDatabase : RoomDatabase() {
    abstract fun saleDao(): SaleDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sales ADD COLUMN channel TEXT NOT NULL DEFAULT 'OFFLINE'")
            }
        }
    }
}
