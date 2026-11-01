# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}

# Keep JNI native methods and classes for decent USB audio
-keepclasseswithmembernames class com.decent.usbaudio.** {
    native <methods>;
}
-keep class com.decent.usbaudio.** { *; }
