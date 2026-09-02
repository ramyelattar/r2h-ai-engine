# Consumer ProGuard rules for engine-api.
# These rules are automatically applied to any app or module that depends on engine-api.

# ─── AIDL Stub classes ───────────────────────────────────────────────────────
# Binder stubs are referenced by class name at runtime via IBinder.queryLocalInterface().
# Removing or renaming them breaks IPC binding.
-keep class io.r2h.engine.api.IR2hEngineService { *; }
-keep class io.r2h.engine.api.IR2hEngineService$Stub { *; }
-keep class io.r2h.engine.api.IR2hEngineService$Stub$Proxy { *; }
-keep class io.r2h.engine.api.IR2hGenerateCallback { *; }
-keep class io.r2h.engine.api.IR2hGenerateCallback$Stub { *; }
-keep class io.r2h.engine.api.IR2hGenerateCallback$Stub$Proxy { *; }

# ─── Parcelable model classes ─────────────────────────────────────────────────
# @Parcelize-generated CREATOR fields and writeToParcel methods are invoked via
# reflection by the Android Parcel framework. Obfuscation would break marshalling.
-keep class io.r2h.engine.api.model.** implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
    public void writeToParcel(android.os.Parcel, int);
    public <init>(android.os.Parcel);
}

# Keep all model data classes intact (field names may appear in serialized debug output).
-keepnames class io.r2h.engine.api.model.**

# ─── Enum stability ───────────────────────────────────────────────────────────
# Enums serialized as ordinal or name across the IPC boundary must not be renamed.
-keepnames enum io.r2h.engine.api.model.**

# Modal inference callback introduced by the expanded engine contract.
-keep class io.r2h.engine.api.IModalInferenceCallback { *; }
-keep class io.r2h.engine.api.IModalInferenceCallback$Stub { *; }
-keep class io.r2h.engine.api.IModalInferenceCallback$Stub$Proxy { *; }

# Session/agent orchestration callback introduced by the expanded engine contract.
-keep class io.r2h.engine.api.ISessionOrchestratorCallback { *; }
-keep class io.r2h.engine.api.ISessionOrchestratorCallback$Stub { *; }
-keep class io.r2h.engine.api.ISessionOrchestratorCallback$Stub$Proxy { *; }
