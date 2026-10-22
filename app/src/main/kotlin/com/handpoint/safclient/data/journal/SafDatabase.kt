package com.handpoint.safclient.data.journal

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [SaleEntity::class], version = 1, exportSchema = true)
abstract class SafDatabase : RoomDatabase() {
    abstract fun saleDao(): SaleDao
}
