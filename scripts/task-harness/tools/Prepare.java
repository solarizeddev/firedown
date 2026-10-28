import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Builds the source tree the task harness compiles, from app/src, never by
 * re-typing the code under test:
 * <ul>
 * <li>COPIES TaskManager, TaskRunnable, GifMakerArgs and every task in
 *     manager/tasks, TaskRepository, TaskEvent, SingleLiveEvent, Download,
 *     ServiceActions, IntentActions, Keys, NotificationID, FFmpegListener;</li>
 * <li>copies FFmpegGifMaker and FFmpegEncoder with each
 *     {@code private native T name(args);} turned into a call to
 *     {@code FakeNative.name(this, args)} (the fake JNI side, stub/);</li>
 * <li>SPLICES Utils.isFileWriteable / Utils.changeExtension and
 *     UrlParser.parseFilePath (+ NAME_COUNTER) verbatim into small classes:
 *     the real Utils and UrlParser drag in half the app;</li>
 * <li>applies the named MUTATIONS (teeth checks), each by an anchor that
 *     must match exactly the expected number of times, or the build fails;</li>
 * <li>generates R with an id for every R.type.name the copies use.</li>
 * </ul>
 * usage: java tools/Prepare.java &lt;repo-root&gt; &lt;out-src&gt; [--mutate=a,b]
 */
public class Prepare {

    static Path app, out;

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        out = Paths.get(args[1]);
        Set<String> mutations = new LinkedHashSet<>();
        for (String a : Arrays.copyOfRange(args, 2, args.length)) {
            if (a.startsWith("--mutate=") && a.length() > 9) mutations.addAll(Arrays.asList(a.substring(9).split(",")));
        }
        app = root.resolve("app/src/main/java/com/solarized/firedown");

        try (Stream<Path> s = Files.list(app.resolve("manager/tasks"))) {
            for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".java")).sorted()::iterator) {
                copy("manager/tasks/" + p.getFileName());
            }
        }
        for (String f : List.of("data/repository/TaskRepository.java", "data/TaskEvent.java", "data/SingleLiveEvent.java",
                "data/Download.java", "manager/ServiceActions.java", "IntentActions.java", "Keys.java",
                "utils/NotificationID.java", "ffmpegutils/FFmpegListener.java")) {
            copy(f);
        }
        rewriteNatives("ffmpegutils/FFmpegGifMaker.java");
        rewriteNatives("ffmpegutils/FFmpegEncoder.java");
        spliceUtils();
        spliceUrlParser();
        for (String m : mutations) mutate(m);
        generateR();
    }

    /* ── io ────────────────────────────────────────────────────────────── */

    static String read(String rel) throws IOException {
        return Files.readString(app.resolve(rel), StandardCharsets.UTF_8);
    }

    static Path target(String rel) {
        return out.resolve("com/solarized/firedown").resolve(rel);
    }

    static void write(String rel, String text) throws IOException {
        Path t = target(rel);
        Files.createDirectories(t.getParent());
        Files.writeString(t, text, StandardCharsets.UTF_8);
    }

    static void copy(String rel) throws IOException {
        write(rel, read(rel));
    }

    /* ── natives ───────────────────────────────────────────────────────── */

    static final Pattern NATIVE = Pattern.compile("(?m)^(\\s*)private native ([\\w.<>\\[\\], ]+?)\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*;");

    static void rewriteNatives(String rel) throws IOException {
        String src = read(rel);
        Matcher m = NATIVE.matcher(src);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        while (m.find()) {
            String ret = m.group(2).trim();
            String name = m.group(3);
            String params = m.group(4).trim();
            List<String> names = new ArrayList<>();
            for (String p : splitParams(params)) {
                String[] toks = p.trim().split("\\s+");
                names.add(toks[toks.length - 1]);
            }
            String call = "FakeNative." + name + "(this" + (names.isEmpty() ? "" : ", " + String.join(", ", names)) + ")";
            String body = "void".equals(ret) ? call + ";" : "return " + call + ";";
            String rep = m.group(1) + "/* HARNESS: native -> FakeNative */ private " + ret + " " + name + "(" + params + ") { " + body + " }";
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
            n++;
        }
        m.appendTail(sb);
        if (n == 0) throw new IllegalStateException("no native methods found in " + rel);
        write(rel, sb.toString());
    }

    static List<String> splitParams(String params) {
        List<String> parts = new ArrayList<>();
        if (params.isEmpty()) return parts;
        int depth = 0, start = 0;
        for (int i = 0; i < params.length(); i++) {
            char c = params.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) { parts.add(params.substring(start, i)); start = i + 1; }
        }
        parts.add(params.substring(start));
        return parts;
    }

    /* ── splicing ──────────────────────────────────────────────────────── */

    /** The member whose declaration matches {@code signature}, from the
     *  start of its line to its matching closing brace. */
    static String extractMethod(String src, String signature) {
        Matcher m = Pattern.compile(signature).matcher(src);
        if (!m.find()) throw new IllegalStateException("method not found: " + signature);
        int lineStart = src.lastIndexOf('\n', m.start()) + 1;
        if (m.find()) throw new IllegalStateException("method matched twice: " + signature);
        int open = src.indexOf('{', lineStart);
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '"' || c == '\'') {
                char q = c;
                for (i++; i < src.length() && src.charAt(i) != q; i++) if (src.charAt(i) == '\\') i++;
                continue;
            }
            if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') { i = src.indexOf('\n', i); continue; }
            if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '*') { i = src.indexOf("*/", i) + 1; continue; }
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) return src.substring(lineStart, i + 1);
        }
        throw new IllegalStateException("unbalanced braces after " + signature);
    }

    static String extractField(String src, String declaration) {
        Matcher m = Pattern.compile("(?m)^[ \\t]*" + declaration + "[^;]*;").matcher(src);
        if (!m.find()) throw new IllegalStateException("field not found: " + declaration);
        return m.group();
    }

    static void spliceUtils() throws IOException {
        String src = read("utils/Utils.java");
        String text = "package com.solarized.firedown.utils;\n\n"
                + "import android.util.Log;\n\nimport org.apache.commons.io.FilenameUtils;\n\n"
                + "import java.io.File;\nimport java.io.FileOutputStream;\nimport java.io.IOException;\n\n"
                + "/* GENERATED by scripts/task-harness/tools/Prepare.java: the methods below are\n"
                + " * spliced verbatim from app/src/main/java/com/solarized/firedown/utils/Utils.java. */\n"
                + "public class Utils {\n\n    private static final String TAG = \"Utils\";\n\n"
                + extractMethod(src, "public static boolean isFileWriteable\\(File \\w+\\)") + "\n\n"
                + extractMethod(src, "public static String changeExtension\\(String \\w+, String \\w+\\)") + "\n}\n";
        write("utils/Utils.java", text);
    }

    static void spliceUrlParser() throws IOException {
        String src = read("manager/UrlParser.java");
        String text = "package com.solarized.firedown.manager;\n\n"
                + "import java.util.Random;\nimport java.util.regex.Matcher;\nimport java.util.regex.Pattern;\n\n"
                + "/* GENERATED by scripts/task-harness/tools/Prepare.java: spliced verbatim from\n"
                + " * app/src/main/java/com/solarized/firedown/manager/UrlParser.java. */\n"
                + "public class UrlParser {\n\n"
                + extractField(src, "private static final Pattern NAME_COUNTER") + "\n\n"
                + extractMethod(src, "public static String parseFilePath\\(String \\w+\\)") + "\n}\n";
        write("manager/UrlParser.java", text);
    }

    /* ── mutations ─────────────────────────────────────────────────────── */

    record Edit(String file, String regex, String replacement, int expected) {}

    static final Map<String, List<Edit>> MUTANTS = Map.of(
            // The running task back in an INSTANCE field: a second TaskManager
            // instance cannot reach the first one's task.
            "instance-runnable", List.of(
                    new Edit("manager/tasks/TaskManager.java",
                            Pattern.quote("private static volatile TaskRunnable sCurrentRunnable;"),
                            "private volatile TaskRunnable sCurrentRunnable; /* MUTANT: instance field */", 1)),
            // The old shared inputs: the running task reads whatever the
            // LATEST start carried.
            "shared-inputs", List.of(
                    new Edit("manager/tasks/TaskManager.java",
                            Pattern.quote("private static volatile Job sCurrentJob;"),
                            "private static volatile Job sCurrentJob;\n    private static volatile Job sLatestJob; /* MUTANT */", 1),
                    new Edit("manager/tasks/TaskManager.java",
                            Pattern.quote("mTaskRepository.onTaskQueued();"),
                            "mTaskRepository.onTaskQueued();\n            sLatestJob = job; /* MUTANT */", 1),
                    new Edit("manager/tasks/TaskManager.java",
                            "(public (?:ArrayList<DownloadEntity> getQueueList|GifMakerArgs getGifMakerArgs|long getFramePosMs)\\(\\) \\{\\s*Job job = )sCurrentJob;",
                            "$1sLatestJob; /* MUTANT */", 3)),
            // The original AudioEncodeTask.stop(): no null check on the
            // encoder, so a Cancel before it exists (or run()'s own stop()
            // after an early return) throws.
            "audio-stop-npe", List.of(
                    new Edit("manager/tasks/AudioEncodeTask.java",
                            "FFmpegEncoder encoder = mFFmpegEncoder;\\s*if \\(encoder != null\\) \\{\\s*encoder\\.interrupt\\(\\);\\s*\\}",
                            "mFFmpegEncoder.interrupt(); /* MUTANT: no null check */", 1)),
            // run() resets the stop flag: a Cancel that landed before the
            // task started is thrown away.
            "reset-stop", List.of(
                    new Edit("manager/tasks/TaskRunnable.java",
                            "(public void run\\(\\) \\{)",
                            "$1\n        mIsStopped = false; /* MUTANT */", 1)));

    static void mutate(String name) throws IOException {
        List<Edit> edits = MUTANTS.get(name);
        if (edits == null) throw new IllegalArgumentException("unknown mutation " + name + " (known: " + MUTANTS.keySet() + ")");
        for (Edit e : edits) {
            Path t = target(e.file());
            String src = Files.readString(t, StandardCharsets.UTF_8);
            Matcher m = Pattern.compile(e.regex()).matcher(src);
            int n = 0;
            while (m.find()) n++;
            if (n != e.expected()) {
                throw new IllegalStateException("mutation " + name + ": anchor /" + e.regex() + "/ matched " + n
                        + " time(s) in " + e.file() + ", expected " + e.expected() + " (the code moved; update the mutant)");
            }
            Files.writeString(t, src.replaceAll(e.regex(), e.replacement()), StandardCharsets.UTF_8);
        }
        System.err.println("mutation applied: " + name);
    }

    /* ── R ─────────────────────────────────────────────────────────────── */

    static void generateR() throws IOException {
        Pattern ref = Pattern.compile("(?<![\\w.])R\\.(\\w+)\\.(\\w+)");
        Map<String, Set<String>> ids = new TreeMap<>();
        try (Stream<Path> s = Files.walk(out)) {
            for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".java"))::iterator) {
                Matcher m = ref.matcher(Files.readString(p, StandardCharsets.UTF_8));
                while (m.find()) ids.computeIfAbsent(m.group(1), k -> new TreeSet<>()).add(m.group(2));
            }
        }
        StringBuilder sb = new StringBuilder("package com.solarized.firedown;\n\n/* GENERATED by Prepare.java: every R.type.name the copied sources use. */\npublic final class R {\n");
        int type = 1;
        for (Map.Entry<String, Set<String>> e : ids.entrySet()) {
            sb.append("    public static final class ").append(e.getKey()).append(" {\n");
            int n = 1;
            for (String name : e.getValue()) {
                sb.append("        public static final int ").append(name).append(" = 0x7f")
                        .append(String.format("%02x%04x", type, n++)).append(";\n");
            }
            sb.append("    }\n");
            type++;
        }
        sb.append("}\n");
        write("R.java", sb.toString());
    }
}
