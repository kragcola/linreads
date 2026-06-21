# LinReads ProGuard rules — OTA build
# Keep Compose
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# Keep Koin DI
-keep class org.koin.** { *; }
-keep class * extends org.koin.core.module.Module { *; }

# Keep Room entities & DAOs
-keep class dev.readflow.core.database.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }

# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.readflow.**$$serializer { *; }
-keepclassmembers class dev.readflow.** {
    *** Companion;
}
-keepclasseswithmembers class dev.readflow.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep callback lambdas from being stripped by R8
-keepclassmembers class dev.readflow.core.ui.BookGridKt {
    *** onItemClick(...);
    *** onDelete(...);
    *** onRename(...);
    *** onMoveToGroup(...);
    *** onReorder(...);
    *** onUngroup(...);
}

# Keep data classes used in serialization
-keep class dev.readflow.core.model.** { *; }

# Keep Ktor
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# Keep Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep Markwon
-keep class io.noties.markwon.** { *; }

# Keep jsoup
-keep class org.jsoup.** { *; }

# Keep Coil
-keep class coil.** { *; }

# General Android
-keep class * extends android.app.Application { *; }
-keep class * extends android.app.Activity { *; }
-keep class * extends android.app.Service { *; }
-keep class * extends android.content.BroadcastReceiver { *; }

# Keep data classes / enums / sealed interfaces
-keep class dev.readflow.core.model.** { *; }
-keep enum dev.readflow.core.model.** { *; }

# Remove logging in OTA builds
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
