# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Keep real file names and line numbers in stack traces.
#
# Names are still obfuscated (minifyEnabled true, no -dontobfuscate), so a
# frame reads like `o71.d(BaseActivity.java:374)`: the class needs the
# mapping to decode, but the file and line are true and immediately useful.
# Without this R8 synthesises the source file as `r8-map-id-<hash>` and
# compresses line numbers, so a trace says nothing at all until it is
# retraced — which is how a shipped crash cost a round of guesswork before
# the build machine's mapping.txt turned up.
#
# This matters more here than in most apps because Firedown COLLECTS crash
# reports (crash/CrashUploader → the operator's endpoint). Every report that
# arrives without a matching mapping is otherwise undecodable forever.
-keepattributes SourceFile,LineNumberTable

# -renamesourcefileattribute is deliberately NOT set. It exists to hide
# original file names, which protects nothing here: Firedown is open source,
# so the file names are already public, and replacing them with the constant
# "SourceFile" would throw away the one part of a frame that is readable
# without the mapping. The r8-map-id marker is lost either way once
# SourceFile is kept — matching a report to its build is done by the
# versionCode/versionName that CrashReport already carries.
#
# STILL REQUIRED: archive app/build/outputs/mapping/release/mapping.txt with
# every release. Line numbers alone do not name the class, and no mapping
# means no retrace.

-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.swing.**
-dontwarn java.awt.**
-dontwarn java.beans.**

# GeckoView's WebAuthnTokenManager references the GMS FIDO2 / Tasks APIs, but we
# deliberately don't ship play-services-fido: patched GeckoView (firedown-geckoview
# patch 0001) routes WebAuthn through Android Credential Manager, so the GMS FIDO
# code path is dead. Debug builds skip R8 and tolerate the missing classes; release
# R8 errors on the dangling references. Tell R8 they're intentionally absent.
-dontwarn com.google.android.gms.fido.**
-dontwarn com.google.android.gms.tasks.**

-keep class java8.** { *; }
-dontwarn java8.**

-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}


-keepclassmembers class com.solarized.firedown.manager.services.** {
	public static *** getApiCall(...);
}

-keep class org.mozilla.gecko.**  { *; }

-keep class org.mozilla.javascript.** { *; }

#-keep class com.solarized.firedown.ffmpegutils.** {*;}
#-keep class com.solarized.firedown.ffmpegutils.**

-keepdirectories assets/*

-keep class com.solarized.firedown.ffmpegutils.FFmpegStreamInfo
-keep class com.solarized.firedown.ffmpegutils.FFmpegSVGDecoder
-keep class com.solarized.firedown.ffmpegutils.FFmpegOkhttp

-keepclassmembers class com.solarized.firedown.ffmpegutils.FFmpegStreamInfo {
	private <methods>;
    public <methods>;
}

-keepclassmembers class com.solarized.firedown.ffmpegutils.FFmpegMetaData {
	private <methods>;
    public <methods>;
}

-keepclassmembers class com.solarized.firedown.ffmpegutils.FFmpegGifMaker {
	private <methods>;
    public <methods>;
    *** mNativeGifMaker;
}

-keepclassmembers class com.solarized.firedown.ffmpegutils.FFmpegDownloader {
	native <methods>;
	private <methods>;
    *** mNativeDownloader;
}

-keepclassmembers class com.solarized.firedown.ffmpegutils.FFmpegEncoder {
	native <methods>;
	private <methods>;
    *** mNativeEncoder;
}

-keepclassmembers class com.solarized.firedown.ffmpegutils.FFmpegMetaDataReader {
	native <methods>;
	private <methods>;
    *** mNativeMetadataReader;
}

-keepclassmembers class com.solarized.firedown.ffmpegutils.FFmpegThumbnailer {
	native <methods>;
	private <methods>;
    *** mNativeThumbnailer;
}

-keepclassmembers class com.solarized.firedown.ffmpegutils.FFmpegSVGDecoder {
	native <methods>;
	private <methods>;
	public <init>(...);
}

-keepclassmembers class com.solarized.firedown.ffmpegutils.FFmpegOkhttp {
	native <methods>;
	private <methods>;
	public <init>(...);
}



