# Matron release (R8) keep rules.
#
# Most of the protocol layer parses JSON as JsonElement trees (see
# journal/JournalJson.kt), which needs no keep rules. The rules below cover the
# few reflection-sensitive spots: kotlinx.serialization's generated serializers
# and Tink (backing androidx.security.crypto's EncryptedSharedPreferences, where
# the persisted session token lives).

# ---- kotlinx.serialization (official rules) ----------------------------------
# Keep annotations used at runtime for (de)serialization.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Keep `Companion` object fields of serializable classes.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects (both default and named) of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Belt-and-braces: keep the generated $serializer for every @Serializable type.
-keepclasseswithmembers class **$$serializer {
    *** INSTANCE;
}

# ---- Tink / androidx.security.crypto -----------------------------------------
# EncryptedSharedPreferences is backed by Tink, which registers key managers by
# reflection. Losing these silently breaks reading the stored session token.
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.api.client.http.**
-dontwarn org.joda.time.**

# ---- OkHttp ------------------------------------------------------------------
# OkHttp ships its own consumer rules; these just silence optional-dep warnings.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
