# OneInsight IntelliJ/Android Studio plugin obfuscation.
#
# This build intentionally obfuscates only. Shrinking/optimization are disabled
# because IntelliJ and Kotlin serialization use runtime discovery and generated
# access paths that are not fully visible to a closed-world optimizer.
-dontshrink
-dontoptimize
-dontpreverify

-verbose
-useuniqueclassmembernames
-repackageclasses 'dev.oneinsight.internal'

# Preserve metadata needed by Kotlin, coroutines, serialization, and IntelliJ.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions
-keepattributes MethodParameters

# These names are referenced by META-INF/plugin.xml and instantiated by IntelliJ.
-keepnames class dev.packetins.studio.PacketInsProjectService
-keepnames class dev.packetins.studio.ui.PacketInsToolWindowFactory

# Preserve names of classes/members explicitly marked for IntelliJ services.
-keepnames @com.intellij.openapi.components.Service class *
-keepclassmembers,allowoptimization,allowobfuscation class * {
    @com.intellij.util.xmlb.annotations.** <fields>;
    @com.intellij.util.xmlb.annotations.** <methods>;
}

# Serialization is generated, but annotations and serializer entry points must
# remain discoverable across Kotlin/serialization versions.
-keepclassmembers,allowoptimization,allowobfuscation class * {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,allowoptimization,allowobfuscation class **$$serializer { *; }

# Keep enum machinery used by serializers and UI models.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep plugin resources and adapt any class references if more XML extensions
# are added later.
-adaptresourcefilecontents META-INF/plugin.xml

# Android Studio supplies these APIs at runtime; unresolved optional internals
# should not prevent producing the obfuscated artifact.
-dontwarn com.android.**
-dontwarn com.intellij.**
-dontwarn org.jetbrains.**
-dontwarn studio.network.inspection.**
-dontwarn kotlin.**
-dontwarn kotlinx.**
