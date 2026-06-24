package com.example.playerapp.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bmi_records")
data class BmiEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val userId: String?,
    val weight: Double,
    val height: Double,
    val bmi: Double,
    val category: String,
    val screenId: String,
    val timestamp: Long = System.currentTimeMillis()
)
