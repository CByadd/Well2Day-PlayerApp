package com.example.playerapp.data.local

import androidx.room.*

@Dao
interface BmiDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: BmiEntity)

    @Query("SELECT * FROM bmi_records ORDER BY timestamp ASC")
    suspend fun getAllRecords(): List<BmiEntity>

    @Delete
    suspend fun delete(record: BmiEntity)

    @Query("DELETE FROM bmi_records")
    suspend fun deleteAll()
}
