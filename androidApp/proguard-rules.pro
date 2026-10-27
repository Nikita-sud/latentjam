# ONNX Runtime's Java objects cross into native code. Keep this small API surface stable while R8
# still optimizes the application, Compose, playback, and smart-engine call sites around it.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# WorkManager (the Map's deferred rebuild) creates its Room database reflectively through the generated
# WorkDatabase_Impl no-arg constructor. R8 removed it, and the release build crashed at launch in
# WorkManagerInitializer (NoSuchMethodException: WorkDatabase_Impl.<init>).
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# SmartRanker.planLocked sits at the 255-register limit (tools/release/check-dex-registers.py), and an over-limit
# method is mis-encoded by R8 (crash at launch). R8 inlined its single-use suspend helpers into it, so their locals
# joined its state machine. Keep them out of line: without allowoptimization R8 does not inline them. Kotlin mangles
# names that take a value class, hence the wildcards.
-keepclassmembers,allowobfuscation,allowshrinking class io.github.nikitasud.latentjam.smart.ranker.SmartRanker {
    *** semChainFor*(...);
    *** hubPenalties*(...);
    *** retrieve*(...);
}
