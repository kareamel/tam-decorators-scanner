package com.tamdecorators.scanner.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanDao {
    @Insert
    suspend fun insert(scan: ScanEntity): Long

    @Query("SELECT * FROM scans ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<ScanEntity>>
}

