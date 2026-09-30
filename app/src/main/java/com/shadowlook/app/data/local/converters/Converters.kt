package com.shadowlook.app.data.local.converters

import android.util.Log
import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken

class Converters {

    private val gson = Gson()

    @TypeConverter
    fun fromFloatArrayToString(floatArray: FloatArray): String {
        return try {
            gson.toJson(floatArray.toList())
        } catch (e: Throwable) {
            Log.e("Converters", "خطأ في fromFloatArrayToString: ${e.message}")
            "[]"
        }
    }

    @TypeConverter
    fun fromStringToFloatArray(jsonString: String): FloatArray {
        return try {
            if (jsonString.isBlank() || jsonString == "[]") return FloatArray(128) { 0f }
            val type = object : TypeToken<List<Float>>() {}.type
            val list: List<Float> = gson.fromJson(jsonString, type)
            list.toFloatArray()
        } catch (e: Throwable) {
            Log.e("Converters", "خطأ في fromStringToFloatArray: ${e.message}, JSON: ${jsonString.take(100)}")
            FloatArray(128) { 0f }
        }
    }

    @TypeConverter
    fun fromFloatListToString(list: List<Float>): String {
        return try {
            gson.toJson(list)
        } catch (e: Throwable) {
            "[]"
        }
    }

    @TypeConverter
    fun fromStringToFloatList(jsonString: String): List<Float> {
        return try {
            if (jsonString.isBlank()) return List(128) { 0f }
            val type = object : TypeToken<List<Float>>() {}.type
            gson.fromJson(jsonString, type)
        } catch (e: Throwable) {
            List(128) { 0f }
        }
    }

    companion object {
        private const val TAG = "Converters"

        fun embeddingToJson(embedding: FloatArray): String {
            return try {
                Gson().toJson(embedding.toList())
            } catch (e: Throwable) {
                Log.e(TAG, "خطأ في embeddingToJson: ${e.message}")
                "[]"
            }
        }

        fun jsonToEmbedding(json: String): FloatArray {
            return try {
                if (json.isBlank() || json == "[]" || json == "null") {
                    return FloatArray(128) { 0f }
                }
                val type = object : TypeToken<List<Float>>() {}.type
                val list: List<Float> = Gson().fromJson(json, type)
                if (list.size != 128) {
                    Log.w(TAG, "حجم embedding غير متوقع: ${list.size} بدلاً من 128")
                    // إذا الحجم مختلف، أعد مصفوفة بحجم 128
                    return if (list.size > 128) list.take(128).toFloatArray() else FloatArray(128) { i -> list.getOrNull(i) ?: 0f }
                }
                list.toFloatArray()
            } catch (e: JsonSyntaxException) {
                Log.e(TAG, "خطأ في تحليل JSON: ${e.message}, JSON: ${json.take(200)}")
                FloatArray(128) { 0f }
            } catch (e: Throwable) {
                Log.e(TAG, "خطأ غير متوقع في jsonToEmbedding: ${e.message}")
                FloatArray(128) { 0f }
            }
        }

        fun calculateEuclideanDistance(a: FloatArray, b: FloatArray): Float {
            return try {
                if (a.size != b.size) {
                    Log.e(TAG, "أحجام embedding غير متطابقة: ${a.size} vs ${b.size}")
                    return Float.MAX_VALUE
                }
                var sum = 0f
                for (i in a.indices) {
                    val diff = a[i] - b[i]
                    sum += diff * diff
                }
                kotlin.math.sqrt(sum)
            } catch (e: Throwable) {
                Float.MAX_VALUE
            }
        }
    }
}
