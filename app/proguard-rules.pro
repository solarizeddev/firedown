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

# Readable release stack traces — the crash report IS the whole diagnostic.
#
# A Java crash is captured by CrashHandler, stored by CrashStorage, and shown on
# the next launch by CrashReportSheet, which prefills a GitHub issue with the
# trace text verbatim (CrashReportUrlBuilder). Release builds log nothing (see
# the -assumenosideeffects on android.util.Log below), so that pasted trace is
# all a maintainer ever gets.
#
# There is no store console to deobfuscate it for us: distribution is signed
# APKs on GitHub Releases (no Play Store, not on F-Droid), so a mapping.txt
# would have to be archived per versionCode by hand, forever, with no CI. Miss
# one and that version's crash reports are permanently unreadable.
#
# So the trace is kept readable in the artifact itself:
#
# 1. Keep SourceFile + LineNumberTable. Without these every frame reads
#    "(Unknown Source)" — no line number at all, which is the bigger half of the
#    problem: even with real names you would be guessing which line of a long
#    method threw. Costs tens of KB of line-number tables.
# 2. -dontobfuscate. This turns off R8's NAMING pass only; shrinking and
#    optimization still run (there is no -dontshrink / -dontoptimize here, and
#    proguard-android-optimize.txt still drives the optimization passes). Dex
#    dispatch resolves through index tables, not name strings, so renaming buys
#    no runtime speed — the cost of dropping it is a few hundred KB of dex
#    string pool on an APK dominated by GeckoView and ffmpeg native libs. It is
#    also protecting nothing: the source is public (MIT), and no secret is baked
#    into the APK. Removing it can only reduce reflection breakage, never add it.
#
# Deliberately NOT setting -renamesourcefileattribute: it rewrites the file name
# to the literal "SourceFile", which is what you want when a mapping file will
# restore it later. With no obfuscation there is nothing to restore and nothing
# to hide, so keeping the real name gives the most readable frame —
#   at com...GeckoRuntimeHelper.handleExtractionMessage(GeckoRuntimeHelper.java:1234)
#
# If obfuscation is ever re-enabled to reclaim the dex size, the mapping-archive
# discipline above becomes load-bearing — do not re-enable it without that.
-keepattributes SourceFile,LineNumberTable
-dontobfuscate

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



