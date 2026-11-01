package com.solarized.firedown.update;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Half of the Gradle part of scripts/update-harness: makes APK-shaped release
 * files for a scratch project, then checks what the REAL app/release.gradle
 * `releaseStatus` wrote — status.json, release-history.json and the upload
 * folder — with the app's own UpdatePatch.
 *
 *   make DIR                          old-1202..1205.apk, new-1206.apk in DIR
 *   full PROJ OLD_DIR                 a run with patches and the .xz
 *   bare PROJ                         a run with -Pfiredown.updateDeltas=false
 *
 * Exits 1 on any failed check.
 */
public final class ReleaseCheck {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "make":
                make(new File(args[1]));
                return;
            case "full":
                full(new File(args[1]), new File(args[2]));
                break;
            case "bare":
                bare(new File(args[1]));
                break;
            default:
                throw new IllegalArgumentException(args[0]);
        }
        System.out.println("release-check " + args[0] + ": " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    private static void check(boolean ok, String what) {
        if (ok) {
            pass++;
            System.out.println("ok   " + what);
        } else {
            fail++;
            System.out.println("FAIL " + what);
        }
    }

    /** Four "releases" sharing a large stored block, each with its own code. */
    private static void make(File dir) throws IOException {
        dir.mkdirs();
        Random random = new Random(42);
        byte[] lib = new byte[3_000_000];
        random.nextBytes(lib);
        for (int code = 1202; code <= 1206; code++) {
            byte[] code0 = new byte[300_000 + code];
            new Random(code).nextBytes(code0);
            byte[] apk = new byte[code0.length + lib.length];
            System.arraycopy(code0, 0, apk, 0, code0.length);
            System.arraycopy(lib, 0, apk, code0.length, lib.length);
            String name = code == 1206 ? "new-1206.apk" : "old-" + code + ".apk";
            Files.write(new File(dir, name).toPath(), apk);
        }
    }

    private static void full(File proj, File oldDir) throws Exception {
        File newApk = new File(proj, "app/release/app-release.apk");
        String newSha = sha(newApk);
        JSONObject status = new JSONObject(read(new File(proj, "status.json")));
        File updateDir = new File(proj, "app/build/outputs/update/v1.2.6");

        check(status.getInt("versionCode") == 1206, "status.json versionCode 1206");
        check(newSha.equals(status.getString("sha256")), "status.json sha256 is the APK's");
        check(status.getString("updateUrl").endsWith("/v1.2.6/app-release.apk"), "status.json updateUrl is the GitHub asset");
        check("• a bullet".equals(status.getString("changelog")), "status.json changelog from CHANGELOG.md");
        check(status.has("keepMe"), "a key releaseStatus doesn't own is kept");

        JSONArray patches = status.getJSONArray("patches");
        check(patches.length() == 2, "two patches: 1205 and 1204 (1203's file doesn't match its hash, 1202 is past three)");
        int[] froms = {1205, 1204};
        for (int i = 0; i < patches.length() && i < froms.length; i++) {
            JSONObject p = patches.getJSONObject(i);
            int from = p.getInt("fromVersionCode");
            File old = new File(oldDir, "old-" + from + ".apk");
            File patch = new File(updateDir, "patch-" + from + "-1206.fdpatch");
            check(from == froms[i], "patch " + i + " is from " + froms[i] + ", newest first");
            check(sha(old).equals(p.getString("fromSha256")), "patch " + from + " fromSha256 is the old APK's");
            check(patch.isFile() && sha(patch).equals(p.getString("sha256")) && patch.length() == p.getLong("size"),
                    "patch " + from + " sha256 and size match the file");
            JSONArray urls = p.getJSONArray("urls");
            check(urls.length() == 2 && urls.getString(0).endsWith("/v1.2.6/" + patch.getName())
                            && urls.getString(1).equals("https://mirror.test/updates/" + patch.getName()),
                    "patch " + from + " URLs: the GitHub release, then the mirror");
            File out = new File(proj, "rebuilt-" + from);
            String rebuilt = UpdatePatch.apply(old, patch, out, p.getString("fromSha256"), null);
            check(rebuilt.equals(newSha), "patch " + from + " rebuilds the new APK with the app's applier");
            check(patch.length() < newApk.length() / 5, "patch " + from + " is small (" + patch.length() + " bytes)");
        }

        JSONObject compressed = status.getJSONObject("compressed");
        File xz = new File(updateDir, "firedown-1206.apk.xz");
        check("xz".equals(compressed.getString("format")) && xz.isFile()
                && sha(xz).equals(compressed.getString("sha256")) && xz.length() == compressed.getLong("size"),
                "compressed block names the .xz file");
        check(UpdatePatch.unxz(xz, new File(proj, "unxz-out"), UpdatePatch.MAX_FILE_SIZE, null).equals(newSha),
                ".xz unpacks to the APK with the app's code");

        // The upload folder: exactly the files, with SHA256SUMS listing each.
        List<String> names = new ArrayList<>();
        for (File f : updateDir.listFiles()) {
            names.add(f.getName());
        }
        names.sort(null);
        check(names.toString().equals("[SHA256SUMS, app-release.apk, firedown-1206.apk.xz, "
                + "patch-1204-1206.fdpatch, patch-1205-1206.fdpatch]"), "upload folder holds exactly " + names);
        String sums = read(new File(updateDir, "SHA256SUMS"));
        boolean sumsOk = true;
        for (String name : names) {
            if (!name.equals("SHA256SUMS") && !sums.contains(sha(new File(updateDir, name)) + "  " + name + "\n")) {
                sumsOk = false;
            }
        }
        check(sumsOk, "SHA256SUMS lists every file correctly");

        JSONArray history = new JSONArray(read(new File(proj, "release-history.json")));
        check(history.getJSONObject(0).getInt("versionCode") == 1206
                && history.getJSONObject(0).getString("sha256").equals(newSha)
                && history.getJSONObject(0).getString("apkUrl").endsWith("/v1.2.6/app-release.apk"),
                "release-history.json gains 1.2.6 on top");
        check(history.length() == 5 && history.getJSONObject(3).getInt("versionCode") == 1203,
                "release-history.json keeps the older entries, newest first");
    }

    private static void bare(File proj) throws Exception {
        JSONObject status = new JSONObject(read(new File(proj, "status.json")));
        check(!status.has("patches") && !status.has("compressed"),
                "with deltas off, status.json lists neither patches nor the .xz (the last run's are dropped)");
        File updateDir = new File(proj, "app/build/outputs/update/v1.2.6");
        String[] names = updateDir.list();
        java.util.Arrays.sort(names);
        check(java.util.Arrays.toString(names).equals("[SHA256SUMS, app-release.apk]"),
                "with deltas off, the upload folder holds the APK and SHA256SUMS only");
        JSONArray history = new JSONArray(read(new File(proj, "release-history.json")));
        int count1206 = 0;
        for (int i = 0; i < history.length(); i++) {
            if (history.getJSONObject(i).getInt("versionCode") == 1206) {
                count1206++;
            }
        }
        check(count1206 == 1, "a rebuild under the same version replaces its history entry");
    }

    private static String sha(File f) throws IOException {
        return UpdatePatch.sha256Hex(f, null);
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
