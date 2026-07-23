# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.raznoe.katana.** {
    *** Companion;
}
-keepclasseswithmembers class com.raznoe.katana.** {
    kotlinx.serialization.KSerializer serializer(...);
}
