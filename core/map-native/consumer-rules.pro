# JNI entry point and cancellation method looked up by name.
-keep class io.github.nikitasud.latentjam.smart.cluster.NativeMapBridge { *; }
-keep interface io.github.nikitasud.latentjam.smart.cluster.MapCancellation { *; }
-keepclassmembers class * implements io.github.nikitasud.latentjam.smart.cluster.MapCancellation {
    public boolean isActive();
}

