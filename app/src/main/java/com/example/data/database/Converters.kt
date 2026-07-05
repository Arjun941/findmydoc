package com.example.data.database

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromFloatArray(array: FloatArray?): String? {
        if (array == null) return null
        return array.joinToString(separator = ",")
    }

    @TypeConverter
    fun toFloatArray(data: String?): FloatArray? {
        if (data.isNullOrEmpty()) return null
        return try {
            data.split(",").map { it.toFloat() }.toFloatArray()
        } catch (e: Exception) {
            FloatArray(0)
        }
    }
}
