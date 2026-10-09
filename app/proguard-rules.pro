# Shadow Injector
# Keep the native entry points reachable from JNI.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep the overlay service (started by explicit class name).
-keep class com.shadow.injector.OverlayService { *; }
