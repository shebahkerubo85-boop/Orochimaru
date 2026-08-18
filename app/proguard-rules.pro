#############################################
# General / Debugging
#############################################

#-keepattributes SourceFile,LineNumberTable
#-renamesourcefileattribute SourceFile

-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

-dontobfuscate


#############################################
# Kotlin / Coroutines / Serialization
#############################################

-keep class kotlin.** { *; }
-dontwarn kotlin.**

-keep class kotlinx.** { *; }

# Serializable Companion handling
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

-keep class kotlinx.serialization.** { *; }

#############################################
# Core App / Extensions
#############################################

-keep class ani.sanin.** { *; }
-keep class ani.sanin.download.DownloadsManager { *; }

-keep class eu.kanade.** { *; }
-keep class uy.kohesive.injekt.** { *; }

-keepclassmembers class uy.kohesive.injekt.api.FullTypeReference {
    <init>(...);
}

#############################################
# Firebase
#############################################

-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

#############################################
# Networking (OkHttp + Okio)
#############################################

-keep class okhttp3.** { *; }
-dontwarn okhttp3.**

-keep class okio.** { *; }
-dontwarn okio.**


#############################################
# Android / Jetpack
#############################################

-keep class androidx.preference.** { *; }

# WorkManager database
-keep class androidx.work.impl.WorkDatabase_Impl { *; }


#############################################
# Gson / JSON / HTML Parsing
#############################################

-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }

-keep class org.jsoup.** { *; }
-keepclassmembers class org.jsoup.nodes.Document { *; }


#############################################
# QuickJS / Native / Unsafe
#############################################

-keep,allowoptimization class app.cash.quickjs.** { public protected *; }

-keep class rx.internal.util.unsafe.** { *; }

-dontwarn sun.misc.Unsafe
-dontwarn org.graalvm.nativeimage.**
-dontwarn com.oracle.svm.core.annotate.**



# Keep RxJava unsafe internals
-keep class rx.internal.util.unsafe.** { *; }

# Keep fields (VERY IMPORTANT)
-keepclassmembers class rx.internal.util.unsafe.** {
    long producerIndex;
    long consumerIndex;
}

# Keep all rx internal operators (safe side)
-keep class rx.internal.** { *; }

# Prevent stripping Unsafe usage
-dontwarn sun.misc.Unsafe

#############################################
# Charts (AAChart)
#############################################

-keep class com.github.aachartmodel.** { *; }
-dontwarn com.github.aachartmodel.**


#############################################
# CloudStream .cs3 plugin runtime
# Plugins are compiled dex archives that link against these class names
# by reflection/classloader, so nothing here may be stripped or renamed.
#############################################

-keep class com.lagradost.** { *; }
-dontwarn com.lagradost.**

-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**

-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**

-keep class dev.whyoleg.cryptography.** { *; }
-dontwarn dev.whyoleg.cryptography.**

-keep class com.fleeksoft.ksoup.** { *; }
-dontwarn com.fleeksoft.ksoup.**

-keep class org.schabi.newpipe.** { *; }
-dontwarn org.schabi.newpipe.**

-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

-keep class kotlinx.datetime.** { *; }
-dontwarn kotlinx.datetime.**

-keep class kotlinx.io.** { *; }
-dontwarn kotlinx.io.**

-keep class kotlinx.atomicfu.** { *; }
-dontwarn kotlinx.atomicfu.**

# Keep org.json classes used for parsing API responses (system class on Android)
-keep class org.json.** { *; }
-dontwarn org.json.**
