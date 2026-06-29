package com.solarized.firedown;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.util.Log;

import com.solarized.firedown.utils.BuildUtils;

import org.apache.commons.codec.digest.DigestUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Integrity checks for a downloaded update APK, shared by the download path.
 *
 * Both checks run AFTER DownloadManager reports the file complete (in
 * UpdateDownloadReceiver), on a background thread — they read the whole file
 * from disk. The SHA-256 anchors trust to the status.json manifest; the
 * signature check anchors trust to the device, so a tampered/mirrored APK
 * with a matching manifest digest still can't slip through. (The OS installer
 * would reject a signature mismatch at install time anyway, but checking here
 * means we never even post an install prompt for one.)
 */
public final class UpdateApkVerifier {

    private static final String TAG = UpdateApkVerifier.class.getName();

    private UpdateApkVerifier() {}

    /** True iff the file's SHA-256 matches the hex digest from the manifest. */
    public static boolean verifySha256(File apk, String expectedHex) {
        if (expectedHex == null || expectedHex.isEmpty()) {
            return false;
        }
        try (FileInputStream fis = new FileInputStream(apk)) {
            String localSha = DigestUtils.sha256Hex(fis);
            return localSha.equalsIgnoreCase(expectedHex);
        } catch (IOException e) {
            Log.e(TAG, "SHA-256 read failed", e);
            return false;
        }
    }

    /**
     * True iff the downloaded APK is signed by the same key(s) as the
     * currently installed app. Moved verbatim from the old in-worker OkHttp
     * download path.
     */
    public static boolean verifySignature(Context context, File apk) {
        try {
            PackageManager pm = context.getPackageManager();
            String installedPackage = context.getPackageName();
            Signature[] downloaded;
            Signature[] installed;

            if (BuildUtils.hasAndroidP()) {
                PackageInfo dl = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                        PackageManager.GET_SIGNING_CERTIFICATES);
                PackageInfo cur = pm.getPackageInfo(installedPackage,
                        PackageManager.GET_SIGNING_CERTIFICATES);
                if (dl == null || dl.signingInfo == null || cur.signingInfo == null) {
                    return false;
                }

                SigningInfo dlInfo = dl.signingInfo;
                SigningInfo curInfo = cur.signingInfo;
                downloaded = dlInfo.hasMultipleSigners()
                        ? dlInfo.getApkContentsSigners()
                        : dlInfo.getSigningCertificateHistory();
                installed = curInfo.hasMultipleSigners()
                        ? curInfo.getApkContentsSigners()
                        : curInfo.getSigningCertificateHistory();
            } else {
                @SuppressWarnings("deprecation")
                PackageInfo dl = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                        PackageManager.GET_SIGNATURES);
                @SuppressWarnings("deprecation")
                PackageInfo cur = pm.getPackageInfo(installedPackage,
                        PackageManager.GET_SIGNATURES);
                if (dl == null) {
                    return false;
                }
                downloaded = dl.signatures;
                installed = cur.signatures;
            }

            if (downloaded == null || installed == null
                    || downloaded.length == 0 || installed.length == 0) {
                return false;
            }

            for (Signature d : downloaded) {
                boolean matched = false;
                for (Signature i : installed) {
                    if (d.equals(i)) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Signature verification failed", e);
            return false;
        }
    }
}
