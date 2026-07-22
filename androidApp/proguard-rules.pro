# ONNX Runtime's Java objects cross into native code. Keep this small API surface stable while R8
# still optimizes the application, Compose, playback, and smart-engine call sites around it.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
