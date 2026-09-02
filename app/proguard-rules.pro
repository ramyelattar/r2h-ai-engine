# R2H AI Engine application R8 rules.
#
# Do not add broad package-wide keep rules or global dontwarn rules here.
# Every keep below protects a runtime contract that cannot be inferred safely
# from ordinary JVM reachability.

# Kotlin metadata, generic signatures, nested-class structure and runtime
# annotations used by Android/Kotlin-generated code.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod

# JNI entry points use native method and class names. Keep only classes that
# actually declare native methods, plus descriptor classes referenced by them.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# r2h_native.cpp creates these Kotlin objects by binary class name and invokes
# their constructors from C++.
-keep class io.r2h.engine.nativebridge.NativeGenerateResult { *; }
-keep class io.r2h.engine.nativebridge.NativeModelMetadata { *; }

# The native generator calls TokenCallback.onToken(String) through JNI.
-keep interface io.r2h.engine.nativebridge.TokenCallback { *; }
-keepclassmembers class * implements io.r2h.engine.nativebridge.TokenCallback {
    public void onToken(java.lang.String);
}
