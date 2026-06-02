# Default Android R8 rules cover most cases. App-specific rules below.

# Keep Media3 session classes — reflection-based callbacks
-keep class androidx.media3.** { *; }

# Keep ONNX Runtime JNI bindings
-keep class ai.onnxruntime.** { *; }

# Keep our copied native helper bridges so JNI lookups by name succeed.
-keep class com.isaivazhi.app.NativeAccelerator { *; }
-keep class com.isaivazhi.app.NativeBridgeInterface { *; }

# Keep Kotlin metadata
-keep class kotlin.Metadata { *; }
