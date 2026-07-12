# JNI: native methods are resolved by their fully-qualified name at runtime, so
# R8 must not rename or strip the NativeOpus bridge or any native method.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.soundmirror.app.NativeOpus { *; }

# Pure-Java Opus fallback (used when libopusnative.so fails to load). Keep its
# public API intact so the fallback path keeps working under minification.
-keep class io.github.jaredmdobson.concentus.** { *; }
-dontwarn io.github.jaredmdobson.concentus.**


# Jetpack Compose and kotlinx-coroutines ship their own consumer ProGuard rules,
# so no extra keeps are needed for them here.
