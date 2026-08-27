# kotlinx.serialization: keep generated serializers for our model classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.riyaaz.tanpura.** {
    *** Companion;
}
-keepclasseswithmembers class com.riyaaz.tanpura.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.riyaaz.tanpura.**$$serializer { *; }
