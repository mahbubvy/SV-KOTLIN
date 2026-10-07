# The instrumentation runner shares this app dependency at runtime.
-keep class androidx.tracing.Trace { *; }
-keep class androidx.core.content.FileProvider { public static android.net.Uri getUriForFile(android.content.Context, java.lang.String, java.io.File); }
-keep class androidx.compose.runtime.internal.ComposableLambdaKt { public static *** *(...); }
-keep interface androidx.compose.runtime.Composer { *; }
-keep class androidx.compose.runtime.Composer$Companion { *; }
-keep class androidx.compose.runtime.ComposerKt {
    public static boolean isTraceInProgress();
    public static void traceEventStart(...);
    public static void traceEventEnd();
}
-keep class androidx.activity.compose.ComponentActivityKt { public static *** setContent(...); }
# Preserve shared runtime APIs and app entry points used by the separate test APK.
-keep,allowoptimization class kotlin.** { *; }
-keep,allowoptimization class kotlinx.coroutines.** { *; }
-keep,allowoptimization class coil.** { *; }
-keep,allowoptimization class com.secretvault.app.** { public protected *; }
