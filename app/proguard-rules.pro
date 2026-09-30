# Keep TFLite
-keep class org.tensorflow.lite.** { *; }
# Keep Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn org.tensorflow.lite.**
