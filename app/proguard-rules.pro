# Keep JS Interface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# Keep BuildConfig
-keep class com.dichthuat.pro.BuildConfig { *; }
