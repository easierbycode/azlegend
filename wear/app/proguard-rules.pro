# kotlinx.serialization: keep generated serializers and their lookup members.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.azlegend.wear.**$$serializer { *; }
-keepclassmembers class com.azlegend.wear.** {
    *** Companion;
}
-keepclasseswithmembers class com.azlegend.wear.** {
    kotlinx.serialization.KSerializer serializer(...);
}
