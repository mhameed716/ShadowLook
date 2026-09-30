package com.shadowlook.app.data.local.converters

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class Converters {

    private val gson = Gson()

    @TypeConverter
    fun fromFloatArrayToString(floatArray: FloatArray): String {
        return gson.toJson(floatArray.toList())
    }

    @TypeConverter
    fun fromStringToFloatArray(jsonString: String): FloatArray {
        return try {
            val type = object : TypeToken<List<Float>>() {}.type
            val list: List<Float> = gson.fromJson(jsonString, type)
            list.toFloatArray()
        } catch (e: Exception) {
            FloatArray(128) { 0f }
        }
    }

    @TypeConverter
    fun fromFloatListToString(list: List<Float>): String {
        return gson.toJson(list)
    }

    @TypeConverter
    fun fromStringToFloatList(jsonString: String): List<Float> {
        return try {
            val type = object : TypeToken<List<Float>>() {}.type
            gson.fromJson(jsonString, type)
        } catch (e: Exception) {
            List(128) { 0f }
        }
    }

    companion object {
        fun embeddingToJson(embedding: FloatArray): String {
            return Gson().toJson(embedding.toList())
        }

        fun jsonToEmbedding(json: String): FloatArray {
            return try {
                val type = object : TypeToken<List<Float>>() {}.type
                val list: List<Float> = Gson().fromJson(json, type)
                list.toFloatArray()
            } catch (e: Exception) {
                FloatArray(128) { 0f }
            }
        }

        fun calculateEuclideanDistance(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size) return Float.MAX_VALUE
            var sum = 0f
            for (i in a.indices) {
                val diff = a[i] - b[i]
                sum += diff * diff
            }
            return kotlin.math.sqrt(sum)
        }
    }
}
