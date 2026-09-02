# Add module-specific consumer ProGuard rules here.
# These rules are applied to the consuming app when this module is included as a dependency.

# R2H JNI runtime contracts.
#
# Native symbol lookup depends on class and method names for classes declaring
# external/native methods.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Constructed by r2h_native.cpp via FindClass/NewObject.
-keep class io.r2h.engine.nativebridge.NativeGenerateResult { *; }
-keep class io.r2h.engine.nativebridge.NativeModelMetadata { *; }

# Called from r2h_native.cpp through CallVoidMethod.
-keep interface io.r2h.engine.nativebridge.TokenCallback { *; }
-keepclassmembers class * implements io.r2h.engine.nativebridge.TokenCallback {
    public void onToken(java.lang.String);
}
