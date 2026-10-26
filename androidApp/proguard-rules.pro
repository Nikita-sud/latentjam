# ONNX Runtime's Java objects cross into native code. Keep this small API surface stable while R8
# still optimizes the application, Compose, playback, and smart-engine call sites around it.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# WorkManager (the Map's deferred rebuild) creates its Room database reflectively through the generated
# WorkDatabase_Impl no-arg constructor. R8 removed it, and the release build crashed at launch in
# WorkManagerInitializer (NoSuchMethodException: WorkDatabase_Impl.<init>).
-keep class * extends androidx.room.RoomDatabase { <init>(); }
