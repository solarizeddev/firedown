package com.solarized.firedown.addons;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads what an .xpi says about itself before Gecko sees it: the ID (to
 * refuse a built-in one), whether it declares Android support (the prompt
 * warns when it doesn't) and whether it can update itself. Only
 * {@code manifest.json} is read, size-capped; the archive is never unpacked.
 *
 * <p>Nothing here decides whether the add-on may install: Gecko checks the
 * signature and the manifest again. {@link Info#signed} only lets the app
 * say "isn't signed" without starting an install Gecko would refuse.
 */
public final class XpiManifest {

    /** Larger than any real manifest; a bigger one is not read. */
    static final int MAX_MANIFEST_BYTES = 1024 * 1024;

    private XpiManifest() {}

    /** The file is not an add-on: no readable manifest.json at its root. */
    public static final class NotAnAddonException extends Exception {
        NotAnAddonException(String message) {
            super(message);
        }

        NotAnAddonException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class Info {
        /** The Gecko ID the manifest declares, or null (Gecko then takes it from the signature). */
        @Nullable public final String id;
        /** The manifest's name, possibly a {@code __MSG_key__} placeholder. */
        @NonNull public final String name;
        @NonNull public final String version;
        public final int manifestVersion;
        /** The manifest names Firefox for Android ({@code gecko_android}). */
        public final boolean declaresAndroid;
        /** The add-on names its own update address ({@code update_url}). */
        public final boolean selfUpdates;
        /** The archive carries Mozilla's signature files. */
        public final boolean signed;

        Info(@Nullable String id, @NonNull String name, @NonNull String version,
             int manifestVersion, boolean declaresAndroid, boolean selfUpdates, boolean signed) {
            this.id = id;
            this.name = name;
            this.version = version;
            this.manifestVersion = manifestVersion;
            this.declaresAndroid = declaresAndroid;
            this.selfUpdates = selfUpdates;
            this.signed = signed;
        }
    }

    @NonNull
    public static Info read(@NonNull File xpi) throws IOException, NotAnAddonException {
        ZipFile zip;
        try {
            zip = new ZipFile(xpi);
        } catch (IOException e) {
            throw new NotAnAddonException("not a zip archive", e);
        }
        try {
            ZipEntry entry = zip.getEntry("manifest.json");
            if (entry == null || entry.isDirectory()) {
                throw new NotAnAddonException("no manifest.json");
            }
            if (entry.getSize() > MAX_MANIFEST_BYTES) {
                throw new NotAnAddonException("manifest.json too large");
            }
            byte[] bytes;
            try (InputStream in = zip.getInputStream(entry)) {
                bytes = readCapped(in, MAX_MANIFEST_BYTES);
            }
            boolean signed = zip.getEntry("META-INF/mozilla.rsa") != null
                    || zip.getEntry("META-INF/cose.sig") != null;
            return parse(new String(bytes, StandardCharsets.UTF_8), signed);
        } finally {
            zip.close();
        }
    }

    @NonNull
    static Info parse(@NonNull String text, boolean signed) throws NotAnAddonException {
        String json = text;
        if (json.startsWith("﻿")) {
            json = json.substring(1);
        }
        JSONObject manifest;
        try {
            manifest = new JSONObject(json);
        } catch (JSONException e) {
            // Firefox reads manifests with comments in them.
            try {
                manifest = new JSONObject(stripComments(json));
            } catch (JSONException again) {
                throw new NotAnAddonException("manifest.json is not JSON", again);
            }
        }
        int manifestVersion = manifest.optInt("manifest_version", 0);
        if (manifestVersion <= 0) {
            throw new NotAnAddonException("manifest.json has no manifest_version");
        }
        JSONObject specific = manifest.optJSONObject("browser_specific_settings");
        if (specific == null) {
            specific = manifest.optJSONObject("applications");
        }
        String id = null;
        boolean selfUpdates = false;
        boolean declaresAndroid = false;
        if (specific != null) {
            JSONObject gecko = specific.optJSONObject("gecko");
            if (gecko != null) {
                String declared = gecko.optString("id", "").trim();
                if (!declared.isEmpty()) {
                    id = declared;
                }
                String updateUrl = gecko.optString("update_url", "").trim();
                selfUpdates = !updateUrl.isEmpty();
            }
            declaresAndroid = specific.optJSONObject("gecko_android") != null;
        }
        String name = manifest.optString("name", "").trim();
        String version = manifest.optString("version", "").trim();
        return new Info(id, name, version, manifestVersion, declaresAndroid, selfUpdates, signed);
    }

    /** Drops // and block comments outside strings. */
    @NonNull
    static String stripComments(@NonNull String json) {
        StringBuilder out = new StringBuilder(json.length());
        boolean inString = false;
        int i = 0;
        int n = json.length();
        while (i < n) {
            char c = json.charAt(i);
            if (inString) {
                out.append(c);
                if (c == '\\' && i + 1 < n) {
                    out.append(json.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                i++;
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
                i++;
                continue;
            }
            if (c == '/' && i + 1 < n && json.charAt(i + 1) == '/') {
                i += 2;
                while (i < n && json.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && json.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(json.charAt(i) == '*' && json.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    @NonNull
    private static byte[] readCapped(@NonNull InputStream in, int max) throws IOException, NotAnAddonException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int total = 0;
        while (true) {
            int read = in.read(buf);
            if (read < 0) {
                break;
            }
            total += read;
            if (total > max) {
                throw new NotAnAddonException("manifest.json too large");
            }
            out.write(buf, 0, read);
        }
        return out.toByteArray();
    }
}
