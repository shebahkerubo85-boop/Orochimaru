#############################################
# Attributes
#############################################

-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Keep file + line info so Firebase Crashlytics and the in-app crash screen
# (ani.sanin.others.CrashActivity) show readable stack traces. Obfuscation renames
# classes and methods, but without these two lines the reports lose the source file
# and line number entirely, which makes them far harder to act on.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

#############################################
# Looked up by name at runtime
#############################################

# Custom views are instantiated by fully-qualified tag name when a layout is inflated,
# so aapt2 knows the name but R8 does not. These are the only app classes in res/layout.
-keep class ani.sanin.FadingEdgeRecyclerView { public <init>(...); }
-keep class ani.sanin.NoGestureSubsamplingImageView { public <init>(...); }
-keep class ani.sanin.SpinnerNoSwipe { public <init>(...); }
-keep class ani.sanin.home.status.CircleView { public <init>(...); }
-keep class ani.sanin.home.status.Stories { public <init>(...); }
-keep class ani.sanin.others.Xpandable { public <init>(...); }
-keep class ani.sanin.others.Xubtitle { public <init>(...); }
-keep class ani.sanin.ui.components.SnakeNavRailView { public <init>(...); }

# CrashlyticsFactory does Class.forName("com.google.firebase.crashlytics.FirebaseCrashlytics")
-keep class com.google.firebase.crashlytics.FirebaseCrashlytics { public *; }
-keep class com.google.firebase.crashlytics.ktx.** { public *; }
-dontwarn com.google.firebase.**

# WorkManager loads its Room-generated implementation by name
-keep class androidx.work.impl.WorkDatabase_Impl { *; }

#############################################
# Serialization
#############################################

# Companion/serializer handling for @Serializable classes. The serializers themselves
# are generated at compile time and reference members directly, so they survive renaming.
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

# Gson reads fields reflectively, so a renamed field turns parsed data into empty values.
# These are the only app classes handed to a reflective serializer:
#   ani.sanin.notifications.comment.MediaResponse  - Gson in MediaNameFetch
#   ani.sanin.connections.**                        - AniList/MAL/Simkl DTOs
# The Jackson fallback in AppUtils.toJsonLiteral()/parseJson() goes through public
# accessors, which the public-member keeps below already pin.
-keep class ani.sanin.notifications.comment.** { public *; }
-keep class ani.sanin.connections.** { public *; }

# Gson reads fields directly, including the private backing field of a Kotlin val,
# so the class and member keeps above are not enough on their own.
-keepclassmembers class ani.sanin.notifications.** { <fields>; }
-keepclassmembers class ani.sanin.connections.** { <fields>; }

#############################################
# Kotlin
#############################################

# kotlin-reflect is reached through KClass by the plugin API and the extractor JS bridge,
# so its internals have to stay. The rest of the stdlib keeps only its public API.
-keep class kotlin.reflect.** { *; }
-keep class kotlin.** { public *; }
-dontwarn kotlin.**

-keep class kotlinx.** { public *; }
-dontwarn kotlinx.**

#############################################
# Plugin and extension ABI
#
# Plugins (.cs3 dex) and anime extensions are compiled separately and call these
# classes and their public members by name through a classloader, so nothing public may
# be stripped or renamed. Private and internal members are not reachable from outside and
# stay shrinkable, which is where most of the size comes from.
#############################################

-keep class com.lagradost.** { public *; }
-dontwarn com.lagradost.**

-keep class eu.kanade.** { public *; }
-dontwarn eu.kanade.**

-keep class tachiyomi.** { public *; }
-dontwarn tachiyomi.**

# The "**" above does not cover nested classes, and a plugin compiled against
# Kotlin may call Foo$Companion or Foo$DefaultImpls by name, so pin those too.
-keep class com.lagradost.**$* { public *; }
-keep class eu.kanade.**$* { public *; }
-keep class tachiyomi.**$* { public *; }

-keep class uy.kohesive.injekt.** { public *; }
-dontwarn uy.kohesive.injekt.**

-keepclassmembers class uy.kohesive.injekt.api.FullTypeReference {
    <init>(...);
}

-keep class com.fasterxml.jackson.** { public *; }
-dontwarn com.fasterxml.jackson.**

-keep class com.google.gson.** { public *; }
-keep class com.google.gson.reflect.TypeToken { *; }

-keep class org.jsoup.** { public *; }
-dontwarn org.jsoup.**

-keep class org.json.** { public *; }
-dontwarn org.json.**

-keep class com.fleeksoft.ksoup.** { public *; }
-dontwarn com.fleeksoft.ksoup.**

-keep class io.ktor.** { public *; }
-dontwarn io.ktor.**

-keep class dev.whyoleg.cryptography.** { public *; }
-dontwarn dev.whyoleg.cryptography.**

-keep class org.schabi.newpipe.** { public *; }
-dontwarn org.schabi.newpipe.**

-keep class org.mozilla.javascript.** { public *; }
-dontwarn org.mozilla.javascript.**

-keep class com.github.aachartmodel.** { public *; }
-dontwarn com.github.aachartmodel.**

# RxJava 1, used by the local anime source
-keep class rx.** { public *; }
-dontwarn rx.**

# QuickJS runs extractor scripts
-keep,allowoptimization class app.cash.quickjs.** { public protected *; }

#############################################
# AndroidX / Material
#
# Plugin-provided activities link against these public APIs from their own dex. Public
# members are pinned; everything else shrinks normally.
#############################################

-keep class androidx.appcompat.** { public *; }
-dontwarn androidx.appcompat.**

-keep class androidx.fragment.** { public *; }
-dontwarn androidx.fragment.**

-keep class androidx.activity.** { public *; }
-dontwarn androidx.activity.**

-keep class androidx.lifecycle.** { public *; }
-dontwarn androidx.lifecycle.**

-keep class androidx.savedstate.** { public *; }
-dontwarn androidx.savedstate.**

-keep class androidx.preference.** { public *; }
-dontwarn androidx.preference.**

-keep class com.google.android.material.bottomsheet.** { public *; }
-keep class com.google.android.material.dialog.** { public *; }
-dontwarn com.google.android.material.**

-keep class androidx.core.content.res.ResourcesCompat { public *; }
-keepclassmembers,allowoptimization class androidx.core.content.res.ResourcesCompat {
    public static ** getDrawable(...);
    public static ** getColor(...);
    public static ** getColorStateList(...);
}
-keepclassmembers,allowoptimization class androidx.core.content.res.ContextCompat {
    public static ** getDrawable(...);
    public static ** getColor(...);
}
-keepclassmembers,allowoptimization class androidx.core.content.res.ColorStateListInflaterCompat { *; }

#############################################
# Networking
#############################################

-keep class okhttp3.** { public *; }
-dontwarn okhttp3.**

-keep class okio.** { public *; }
-dontwarn okio.**

#############################################
# Suppressions
#############################################

-dontwarn sun.misc.Unsafe
-dontwarn org.graalvm.nativeimage.**
-dontwarn com.oracle.svm.core.annotate.**
-dontwarn jdk.internal.misc.**

# Obfuscation off for now: a release build is crashing on startup before the splash
# screen, so class names are being held stable while that is diagnosed. Shrinking and
# resource shrinking stay on, so only name rewriting changes between the two builds.
-dontobfuscate
