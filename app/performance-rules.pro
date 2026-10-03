# The instrumentation runner shares this app dependency at runtime.
-keep class androidx.tracing.Trace { *; }
# Preserve shared runtime APIs and app entry points used by the separate test APK.
-keep,allowoptimization class kotlin.** { *; }
-keep,allowoptimization class kotlinx.coroutines.** { *; }
-keep,allowoptimization class coil.** { *; }
-keep,allowoptimization class com.secretvault.app.** { public protected *; }
