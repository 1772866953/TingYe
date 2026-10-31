package com.tingye.app

import android.app.Application
import androidx.room.Room
import com.tingye.app.data.LibraryDatabase
import com.tingye.app.data.LibraryRepository
import com.tingye.app.data.LIBRARY_MIGRATION_1_2
import com.tingye.app.data.LIBRARY_MIGRATION_2_3
import com.tingye.app.data.LIBRARY_MIGRATION_3_4
import com.tingye.app.data.LIBRARY_MIGRATION_4_5
import com.tingye.app.data.LIBRARY_MIGRATION_5_6
import com.tingye.app.data.LIBRARY_MIGRATION_6_7

class TingYeApp : Application() {
    val database by lazy { Room.databaseBuilder(this, LibraryDatabase::class.java, "tingye.db")
        .addMigrations(LIBRARY_MIGRATION_1_2, LIBRARY_MIGRATION_2_3, LIBRARY_MIGRATION_3_4, LIBRARY_MIGRATION_4_5, LIBRARY_MIGRATION_5_6, LIBRARY_MIGRATION_6_7).build() }
    val library by lazy { LibraryRepository(this, database.dao()) }
}
