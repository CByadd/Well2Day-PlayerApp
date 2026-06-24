package com.example.playerapp.utils

import android.content.Context
import java.io.File

fun getOrCreateMachineId(context: Context): String {
    val file = File(context.filesDir, "machine_id.txt")
    if (file.exists()) return file.readText()
    val id = (10000000..99999999).random().toString()
    file.writeText(id)
    return id
}
