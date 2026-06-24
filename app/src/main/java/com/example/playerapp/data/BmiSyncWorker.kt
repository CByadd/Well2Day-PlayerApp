package com.example.playerapp.data

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.playerapp.data.local.AppDatabase

class BmiSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val database = AppDatabase.getDatabase(applicationContext)
        val bmiDao = database.bmiDao()
        val records = bmiDao.getAllRecords()

        Log.i("BmiSyncWorker", "Starting sync for ${records.size} records")

        if (records.isEmpty()) return Result.success()

        var allSynced = true
        for (record in records) {
            try {
                val success = Repository.createBMI(
                    userId = record.userId,
                    weight = record.weight,
                    height = record.height,
                    bmi = record.bmi,
                    category = record.category,
                    screenId = record.screenId
                )
                if (success) {
                    bmiDao.delete(record)
                    Log.i("BmiSyncWorker", "Synced and deleted record: ${record.id}")
                } else {
                    allSynced = false
                }
            } catch (e: Exception) {
                Log.e("BmiSyncWorker", "Error syncing record ${record.id}", e)
                allSynced = false
            }
        }

        return if (allSynced) Result.success() else Result.retry()
    }
}
