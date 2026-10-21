package com.solarized.firedown.p2pshare;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Drives the REAL {@link P2pHttp}, {@link P2pLanServer}, {@link RelayCredsPolicy},
 * {@link P2pFileNames} and {@link P2pSessionPolicy} (copied from app/src by
 * run.sh) on a plain JVM.
 * Lives in the production package because the request-head parser and the
 * server's loopback door are package-private on purpose.
 */
public final class P2pHarness {

    private static int failures;
    private static int passes;

    private P2pHarness() {
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            passes++;
            System.out.println("PASS  " + name);
        } else {
            failures++;
            System.out.println("FAIL  " + name + (detail == null ? "" : " — " + detail));
        }
    }

    static void check(String name, boolean ok) {
        check(name, ok, null);
    }

    public static void main(String[] args) throws Exception {
        try {
            httpHead();
            httpBody();
            relayPolicy();
            networkGate();
            fileNames();
            sessionPolicy();
            reachableHosts();
            lanServer();
            lanDeadlineAndPool();
        } catch (Throwable t) {
            failures++;
            System.out.println("FAIL  harness threw " + t);
            t.printStackTrace(System.out);
        }
        System.out.println(passes + " passed, " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    /* ── P2pHttp.readHead ───────────────────────────────────────────────── */

    private static void httpHead() throws IOException {
        String raw = "POST /answer?t=abc&x=1&bad HTTP/1.1\r\n"
                + "Host: 192.168.1.2:40000\r\n"
                + "content-LENGTH: 5\r\n"
                + "\r\n"
                + "hello";
        InputStream in = new ByteArrayInputStream(raw.getBytes(StandardCharsets.US_ASCII));
        P2pHttp.RequestHead head = P2pHttp.readHead(in);
        check("head: parsed", head != null);
        check("head: method", "POST".equals(head.method));
        check("head: path split from query", "/answer".equals(head.path));
        check("head: query pairs", "abc".equals(head.query.get("t")) && "1".equals(head.query.get("x"))
                && !head.query.containsKey("bad"));
        check("head: header names lower-cased, values trimmed",
                "192.168.1.2:40000".equals(head.headers.get("host"))
                        && "5".equals(head.headers.get("content-length")));
        check("head: content-length read", P2pHttp.contentLength(head) == 5);
        // THE load-bearing property: the stream sits EXACTLY at the first body
        // byte. A readLine-style parser leaves the blank line's '\n' behind,
        // which then lands as body[0] and drops the last real byte — the
        // deflate "operation aborted" that corrupted LAN answers once.
        byte[] body = P2pHttp.readBody(in, 5, 1024);
        check("head: body starts right after CRLFCRLF",
                body != null && "hello".equals(new String(body, StandardCharsets.US_ASCII)),
                body == null ? "null" : new String(body, StandardCharsets.US_ASCII));
        check("head: nothing left behind", in.read() == -1);

        // A stray CR inside a header can begin a fresh terminator match and
        // must not swallow the real one or shift the body.
        String stray = "POST /offer?t=1 HTTP/1.1\r\nX: a\r\rb\r\nContent-Length: 2\r\n\r\nOK";
        in = new ByteArrayInputStream(stray.getBytes(StandardCharsets.US_ASCII));
        head = P2pHttp.readHead(in);
        body = head == null ? null : P2pHttp.readBody(in, P2pHttp.contentLength(head), 1024);
        check("head: stray CR in a header does not shift the body",
                head != null && body != null && "OK".equals(new String(body, StandardCharsets.US_ASCII)));

        check("head: empty stream is null",
                P2pHttp.readHead(new ByteArrayInputStream(new byte[0])) == null);
        check("head: request line without a target is null",
                P2pHttp.readHead(new ByteArrayInputStream("GET\r\n\r\n".getBytes(StandardCharsets.US_ASCII))) == null);
        check("head: no query → no pairs, path intact",
                P2pHttp.readHead(new ByteArrayInputStream("GET /engine HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII))).query.isEmpty());

        StringBuilder huge = new StringBuilder("POST /answer HTTP/1.1\r\n");
        while (huge.length() < P2pHttp.MAX_HEAD_BYTES + 64) {
            huge.append("X-Pad: ").append("y".repeat(200)).append("\r\n");
        }
        huge.append("\r\n");
        check("head: a head over the cap is refused (null)",
                P2pHttp.readHead(new ByteArrayInputStream(huge.toString().getBytes(StandardCharsets.US_ASCII))) == null);
        check("head: a head without a terminator (EOF) still parses the request line",
                P2pHttp.readHead(new ByteArrayInputStream("POST /x HTTP/1.1\r\nA: b".getBytes(StandardCharsets.US_ASCII))) != null);

        P2pHttp.RequestHead noLen = P2pHttp.readHead(new ByteArrayInputStream(
                "POST /answer HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII)));
        check("head: absent content-length is -1", P2pHttp.contentLength(noLen) == -1);
        P2pHttp.RequestHead badLen = P2pHttp.readHead(new ByteArrayInputStream(
                "POST /answer HTTP/1.1\r\nContent-Length: abc\r\n\r\n".getBytes(StandardCharsets.US_ASCII)));
        check("head: malformed content-length is -1", P2pHttp.contentLength(badLen) == -1);
    }

    /* ── P2pHttp.readBody ───────────────────────────────────────────────── */

    private static void httpBody() throws IOException {
        byte[] data = new byte[300];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        byte[] exact = P2pHttp.readBody(new ByteArrayInputStream(data), 300, 1024);
        boolean same = exact != null && exact.length == 300;
        for (int i = 0; same && i < 300; i++) {
            same = exact[i] == data[i];
        }
        check("body: exact bytes, binary-safe", same);
        check("body: declared over the cap is null",
                P2pHttp.readBody(new ByteArrayInputStream(data), 2048, 1024) == null);
        check("body: declared zero is null",
                P2pHttp.readBody(new ByteArrayInputStream(data), 0, 1024) == null);
        check("body: declared -1 (absent) is null",
                P2pHttp.readBody(new ByteArrayInputStream(data), -1, 1024) == null);
        boolean threw = false;
        try {
            P2pHttp.readBody(new ByteArrayInputStream(data), 400, 1024);
        } catch (IOException e) {
            threw = true;
        }
        check("body: a connection that ends short throws, never a padded body", threw);
        // The reader must cope with a stream that hands bytes out one at a time.
        InputStream dribble = new InputStream() {
            int i = 0;

            @Override
            public int read() {
                return i < 300 ? (data[i++] & 0xff) : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (i >= 300) {
                    return -1;
                }
                b[off] = data[i++];
                return 1;
            }
        };
        byte[] dribbled = P2pHttp.readBody(dribble, 300, 1024);
        check("body: assembled across short reads", dribbled != null && dribbled.length == 300
                && dribbled[299] == data[299]);
    }

    /* ── RelayCredsPolicy ───────────────────────────────────────────────── */

    private static void relayPolicy() {
        long now = 1_700_000_000_000L;
        long hour = RelayCredsPolicy.expiryFor(now, 3600);
        check("policy: expiry keeps the safety margin",
                hour == now + 3600_000L - RelayCredsPolicy.EXPIRY_MARGIN_MS);
        check("policy: a TTL under the margin expires at once",
                RelayCredsPolicy.expiryFor(now, 30) == now);
        check("policy: a negative TTL expires at once",
                RelayCredsPolicy.expiryFor(now, -5) == now);
        check("policy: fresh hour-long credential is reusable",
                RelayCredsPolicy.reusable(now, hour));
        // The shipped bug: a credential with minutes left was handed to a new
        // share whose offer window alone is 15 min, so the relay allocation
        // (or its refresh) expired under the transfer.
        check("policy: 29 minutes left is NOT reusable",
                !RelayCredsPolicy.reusable(now, now + 29L * 60 * 1000));
        check("policy: exactly MIN_REMAINING left is reusable",
                RelayCredsPolicy.reusable(now, now + RelayCredsPolicy.MIN_REMAINING_MS));
        check("policy: one ms under MIN_REMAINING is not",
                !RelayCredsPolicy.reusable(now, now + RelayCredsPolicy.MIN_REMAINING_MS - 1));
        check("policy: a short-lived credential is still valid as a fallback",
                RelayCredsPolicy.stillValid(now, now + 1));
        check("policy: an expired credential is never valid",
                !RelayCredsPolicy.stillValid(now, now) && !RelayCredsPolicy.stillValid(now, now - 1));
        // Over expiries on BOTH sides of every boundary — the old single
        // case (1 s left) was never reusable, so it held vacuously.
        long[] expiries = {now - 1, now, now + 1, now + 1000,
                now + RelayCredsPolicy.MIN_REMAINING_MS - 1, now + RelayCredsPolicy.MIN_REMAINING_MS,
                now + RelayCredsPolicy.MIN_REMAINING_MS + 1, hour};
        boolean implies = true;
        int reusableSeen = 0;
        for (long expiry : expiries) {
            if (RelayCredsPolicy.reusable(now, expiry)) {
                reusableSeen++;
                implies &= RelayCredsPolicy.stillValid(now, expiry);
            }
        }
        check("policy: reusable implies stillValid (non-vacuous: " + reusableSeen + " reusable cases)",
                implies && reusableSeen >= 3);
        check("policy: MIN_REMAINING covers the 15-minute offer window with room for a transfer",
                RelayCredsPolicy.MIN_REMAINING_MS >= 2L * 15 * 60 * 1000);
    }

    /* ── RelayCredsPolicy.networkAllowsFetch ────────────────────────────── */

    private static void networkGate() {
        check("gate: no active network skips the relay fetch",
                !RelayCredsPolicy.networkAllowsFetch(false, false, false, false));
        check("gate: unknown capabilities still try",
                RelayCredsPolicy.networkAllowsFetch(true, false, false, false));
        // The shipped bug: the gate also wanted VALIDATED, so a network shown
        // as "connected, no internet" (probe servers blocked, some VPNs)
        // never fetched relay credentials even when it reached the internet.
        check("gate: internet without validation is tried",
                RelayCredsPolicy.networkAllowsFetch(true, true, true, false));
        check("gate: a captive portal skips",
                !RelayCredsPolicy.networkAllowsFetch(true, true, true, true));
        check("gate: a network without the internet capability skips",
                !RelayCredsPolicy.networkAllowsFetch(true, true, false, false));
    }

    /* ── P2pFileNames ───────────────────────────────────────────────────── */

    /** A Taken over a fixed set of names, counting how often it is asked. */
    private static final class Names implements P2pFileNames.Taken {
        final Set<String> taken = new HashSet<>();
        int asked;

        Names(String... names) {
            taken.addAll(Arrays.asList(names));
        }

        @Override
        public boolean test(File candidate) {
            asked++;
            return taken.contains(candidate.getName());
        }
    }

    private static String pick(String name, String... taken) {
        return P2pFileNames.uniquify(new File("/dl"), name, new Names(taken)).getName();
    }

    private static void fileNames() {
        check("names: a free name is kept", "clip.mp4".equals(pick("clip.mp4")));
        check("names: taken → number before the extension",
                "clip(1).mp4".equals(pick("clip.mp4", "clip.mp4")));
        check("names: counts past every taken number",
                "clip(3).mp4".equals(pick("clip.mp4", "clip.mp4", "clip(1).mp4", "clip(2).mp4")));
        check("names: a numbered name continues its own count",
                "clip(4).mp4".equals(pick("clip(3).mp4", "clip(3).mp4")));
        // THE shipped bug: no extension → the old helper returned the same
        // path forever, spinning the shared disk executor.
        check("names: no extension → number at the end",
                "notes(1)".equals(pick("notes", "notes")));
        check("names: a dot-file has no extension",
                ".bashrc(1)".equals(pick(".bashrc", ".bashrc")));
        check("names: only the last extension is kept apart",
                "archive.tar(1).gz".equals(pick("archive.tar.gz", "archive.tar.gz")));
        check("names: a 'dot' followed by words is not an extension",
                "Mr. Smith goes(1)".equals(pick("Mr. Smith goes", "Mr. Smith goes")));
        check("names: an over-long tail is not an extension",
                "data.verylongtail(1)".equals(pick("data.verylongtail", "data.verylongtail")));
        check("names: a trailing dot is not an extension",
                "end.(1)".equals(pick("end.", "end.")));
        // The other half of the bug: an EMPTY name resolved to the download
        // directory itself, which always "exists".
        check("names: null falls back to a real name",
                P2pFileNames.FALLBACK_NAME.equals(pick(null)));
        check("names: blank falls back to a real name",
                P2pFileNames.FALLBACK_NAME.equals(pick("   ")));
        check("names: the fallback is uniquified too",
                (P2pFileNames.FALLBACK_NAME + "(1)").equals(pick("", P2pFileNames.FALLBACK_NAME)));

        // Everything taken: it must still return, after a bounded number of
        // questions, never loop.
        P2pFileNames.Taken all = new P2pFileNames.Taken() {
            int n;

            @Override
            public boolean test(File candidate) {
                n++;
                if (n > 1 + P2pFileNames.MAX_NUMBERED + 16) {
                    throw new IllegalStateException("unbounded");
                }
                return true;
            }
        };
        boolean returned;
        try {
            returned = P2pFileNames.uniquify(new File("/dl"), "x.bin", all) != null;
        } catch (IllegalStateException e) {
            returned = false;
        }
        check("names: a directory where everything is taken still returns (bounded)", returned);

        // Numbered names exhausted, a random suffix is free.
        // Bounded like `all` above, so a regression to an unbounded count
        // FAILS here instead of hanging the harness.
        P2pFileNames.Taken numberedTaken = new P2pFileNames.Taken() {
            int n;

            @Override
            public boolean test(File candidate) {
                n++;
                if (n > 1 + P2pFileNames.MAX_NUMBERED + 16) {
                    throw new IllegalStateException("unbounded");
                }
                return !candidate.getName().matches("x-[0-9a-f]{8}\\.bin");
            }
        };
        String random;
        try {
            random = P2pFileNames.uniquify(new File("/dl"), "x.bin", numberedTaken).getName();
        } catch (IllegalStateException e) {
            random = "unbounded";
        }
        check("names: past the numbered range a random suffix is used",
                random.matches("x-[0-9a-f]{8}\\.bin"), random);
        Names counted = new Names("x.bin");
        P2pFileNames.uniquify(new File("/dl"), "x.bin", counted);
        check("names: a free numbered name is found on the first try", counted.asked == 2);
        check("names: the file stays in the directory it was asked for",
                "/dl".equals(P2pFileNames.uniquify(new File("/dl"), "a.txt", new Names()).getParent()));
    }

    /* ── P2pSessionPolicy ───────────────────────────────────────────────── */

    private static void sessionPolicy() {
        P2pSessionPolicy.SendEntry e;
        // (hasState, isSend, live, inTransfer, statePath, argPath, restored)
        e = P2pSessionPolicy.sendEntry(false, false, false, false, null, "/a", false);
        check("policy: no session + a file → START", e == P2pSessionPolicy.SendEntry.START, e.name());
        e = P2pSessionPolicy.sendEntry(false, false, false, false, null, null, false);
        check("policy: no session, no file → NOTHING", e == P2pSessionPolicy.SendEntry.NOTHING, e.name());
        e = P2pSessionPolicy.sendEntry(true, true, true, false, "/a", "/a", false);
        check("policy: same file, waiting for an accept → ATTACH", e == P2pSessionPolicy.SendEntry.ATTACH, e.name());
        e = P2pSessionPolicy.sendEntry(true, true, true, true, "/a", "/a", false);
        check("policy: same file, transferring → ATTACH", e == P2pSessionPolicy.SendEntry.ATTACH, e.name());
        // Shipped bug 1: the finished-share notification opens the screen
        // with no file, and a rotation restores it; both STARTED a brand-new
        // share of the same file instead of showing the outcome.
        e = P2pSessionPolicy.sendEntry(true, true, false, false, "/a", null, false);
        check("policy: outcome + notification tap (no file) → ATTACH", e == P2pSessionPolicy.SendEntry.ATTACH, e.name());
        e = P2pSessionPolicy.sendEntry(true, true, false, false, "/a", "/a", true);
        check("policy: outcome + rotation → ATTACH, never a new share", e == P2pSessionPolicy.SendEntry.ATTACH, e.name());
        e = P2pSessionPolicy.sendEntry(false, false, false, false, null, "/a", true);
        check("policy: restored with no session (process death) → NOTHING, never a new share",
                e == P2pSessionPolicy.SendEntry.NOTHING, e.name());
        // Sharing the same file again from Downloads after it finished: new.
        e = P2pSessionPolicy.sendEntry(true, true, false, false, "/a", "/a", false);
        check("policy: outcome shown, same file picked again → START", e == P2pSessionPolicy.SendEntry.START, e.name());
        // Shipped bug 2: a new share silently killed a running transfer.
        e = P2pSessionPolicy.sendEntry(true, true, true, true, "/a", "/b", false);
        check("policy: another file while one transfers → BUSY", e == P2pSessionPolicy.SendEntry.BUSY, e.name());
        e = P2pSessionPolicy.sendEntry(true, false, true, true, null, "/b", false);
        check("policy: a file while a RECEIVE transfers → BUSY", e == P2pSessionPolicy.SendEntry.BUSY, e.name());
        e = P2pSessionPolicy.sendEntry(true, true, true, false, "/a", "/b", false);
        check("policy: another file while one only waits → START (newest wins)",
                e == P2pSessionPolicy.SendEntry.START, e.name());
        e = P2pSessionPolicy.sendEntry(true, false, true, false, null, "/b", false);
        check("policy: a file while a receive previews → START", e == P2pSessionPolicy.SendEntry.START, e.name());
        e = P2pSessionPolicy.sendEntry(true, false, true, true, null, null, false);
        check("policy: no file + a receive session → NOTHING (the send screen has nothing to show)",
                e == P2pSessionPolicy.SendEntry.NOTHING, e.name());

        check("busy: only a live session in transfer blocks",
                P2pSessionPolicy.blocksNewSession(true, true, true)
                        && !P2pSessionPolicy.blocksNewSession(true, true, false)
                        && !P2pSessionPolicy.blocksNewSession(true, false, true)
                        && !P2pSessionPolicy.blocksNewSession(false, false, false));
    }

    /* ── P2pLanServer.reachableHost (what NSD resolved → a URL host) ────── */

    private static void reachableHosts() throws IOException {
        InetAddress v4 = InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 1, 23});
        byte[] ll = new byte[16];
        ll[0] = (byte) 0xfe;
        ll[1] = (byte) 0x80;
        ll[15] = 1;
        InetAddress v6 = Inet6Address.getByAddress(null, ll, 3); // fe80::1%3
        check("hosts: the IPv4 wins over a link-local IPv6 listed first",
                "192.168.1.23".equals(P2pLanServer.reachableHost(Arrays.asList(v6, v4))));
        // THE shipped bug: a pre-34 device reports a single host; a scoped
        // IPv6 made the request builder throw on the main thread.
        check("hosts: IPv6 only → null (the device is not listed)",
                P2pLanServer.reachableHost(Collections.singletonList(v6)) == null);
        check("hosts: nothing resolved → null",
                P2pLanServer.reachableHost(null) == null
                        && P2pLanServer.reachableHost(Collections.emptyList()) == null);
    }

    /* ── P2pLanServer ───────────────────────────────────────────────────── */

    /** Records what the server handed over, first delivery wins. */
    private static final class RecordingHandler implements P2pLanServer.Handler {
        final List<String> answers = new ArrayList<>();
        final List<String> offers = new ArrayList<>();
        final AtomicBoolean answerTaken = new AtomicBoolean(false);
        volatile boolean offerBusy;

        @Override
        public boolean onAnswer(String body) {
            answers.add(body);
            if (P2pLanServer.DECLINED_BODY.equals(body)) {
                return true;
            }
            return answerTaken.compareAndSet(false, true);
        }

        @Override
        public boolean onOffer(String body) {
            offers.add(body);
            return !offerBusy;
        }
    }

    private static final class Reply {
        int status;
        String body;
    }

    private static Reply send(int port, String raw) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(raw.getBytes(StandardCharsets.UTF_8));
            out.flush();
            return readReply(socket.getInputStream());
        }
    }

    private static Reply readReply(InputStream in) throws IOException {
        byte[] buf = new byte[4096];
        int total = 0;
        int read;
        while (total < buf.length && (read = in.read(buf, total, buf.length - total)) > 0) {
            total += read;
        }
        String text = new String(buf, 0, total, StandardCharsets.US_ASCII);
        Reply reply = new Reply();
        reply.body = text;
        String[] parts = text.split(" ", 3);
        reply.status = parts.length > 1 ? Integer.parseInt(parts[1]) : -1;
        return reply;
    }

    private static String post(String target, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return "POST " + target + " HTTP/1.1\r\nHost: x\r\nContent-Length: " + bytes.length
                + "\r\n\r\n" + body;
    }

    private static void lanServer() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        P2pLanServer sender = new P2pLanServer(handler, true, false);
        check("lan: a null address stays inert", !sender.start((InetAddress) null)
                && sender.getAnswerUrl() == null);
        check("lan: binds the loopback", sender.start(InetAddress.getLoopbackAddress()));
        int port = sender.getPort();
        String token = sender.getToken();
        check("lan: token is 32 hex chars", token.matches("[0-9a-f]{32}"));
        check("lan: answer url carries host, port and token",
                ("http://127.0.0.1:" + port + "/answer?t=" + token).equals(sender.getAnswerUrl()),
                sender.getAnswerUrl());

        check("lan: wrong token → 403",
                send(port, post("/answer?t=deadbeef", "FDR1.x")).status == 403);
        check("lan: no token → 403",
                send(port, post("/answer", "FDR1.x")).status == 403);
        check("lan: GET on a served route → 404",
                send(port, "GET /answer?t=" + token + " HTTP/1.1\r\nHost: x\r\n\r\n").status == 404);
        check("lan: the route this owner does not serve → 404",
                send(port, post("/offer?t=" + token, "FDS1.x")).status == 404);
        check("lan: an unknown path → 404",
                send(port, post("/engine?t=" + token, "x")).status == 404);
        check("lan: a gated request never reaches the handler",
                handler.answers.isEmpty() && handler.offers.isEmpty());

        check("lan: missing Content-Length → 400",
                send(port, "POST /answer?t=" + token + " HTTP/1.1\r\nHost: x\r\n\r\nFDR1.x").status == 400);
        StringBuilder big = new StringBuilder();
        while (big.length() < 17 * 1024) {
            big.append('z');
        }
        check("lan: a body over 16 KB → 400 (not read)",
                send(port, post("/answer?t=" + token, big.toString())).status == 400);
        check("lan: a malformed Content-Length → 400",
                send(port, "POST /answer?t=" + token + " HTTP/1.1\r\nContent-Length: abc\r\n\r\n").status == 400);
        check("lan: a bad head → 400",
                send(port, "\r\n\r\n").status == 400);
        check("lan: still nothing reached the handler",
                handler.answers.isEmpty() && handler.offers.isEmpty());

        String answer = "FDR1." + "Q".repeat(1500);
        check("lan: first answer → 204",
                send(port, post("/answer?t=" + token, answer)).status == 204);
        check("lan: the handler got the exact body",
                handler.answers.size() == 1 && answer.equals(handler.answers.get(0)));
        check("lan: a second answer → 409 and is still handed over for the decision",
                send(port, post("/answer?t=" + token, "FDR1.second")).status == 409
                        && handler.answers.size() == 2);
        check("lan: declined → 204 even after an answer was taken",
                send(port, post("/answer?t=" + token, P2pLanServer.DECLINED_BODY)).status == 204
                        && P2pLanServer.DECLINED_BODY.equals(handler.answers.get(2)));
        check("lan: body whitespace is trimmed, content kept",
                send(port, post("/answer?t=" + token, "  declined \r\n")).status == 204
                        && P2pLanServer.DECLINED_BODY.equals(handler.answers.get(3)));
        // The body starts with 'F' — a parser that leaves the blank line's
        // '\n' behind would hand over "\nFDR1…" minus its last byte.
        Reply exact = send(port, post("/answer?t=" + token, "FDR1.abc"));
        check("lan: exact body bytes (no stray leading byte, last byte present)",
                exact.status == 409 && "FDR1.abc".equals(handler.answers.get(4)));
        check("lan: the status line closes the connection with no body",
                exact.body.contains("Content-Length: 0") && exact.body.endsWith("\r\n\r\n"));

        sender.stop();
        // stop() closes the ServerSocket on the caller's thread, but with the
        // accept thread parked in accept() the JDK's NioSocketImpl DEFERS the
        // real close until that thread unparks (the in-flight accept syscall
        // pins the kernel socket) — for a few ms a connect can still land in
        // the backlog and then be reset or see EOF with no reply. Harmless in
        // production (a LAN POST in that window is simply unanswered), so the
        // property pinned here is "nothing ANSWERS after stop()", waited for
        // the accept thread to exit first so the check is deterministic.
        awaitAcceptThreadGone();
        boolean unanswered = false;
        try {
            unanswered = send(port, post("/answer?t=" + token, "FDR1.late")).status == -1;
        } catch (IOException e) {
            unanswered = true;
        }
        check("lan: nothing answers after stop()", unanswered);
        check("lan: stop() keeps the answer url (the offer that embeds it is still being shown)",
                sender.getAnswerUrl() != null);

        RecordingHandler recv = new RecordingHandler();
        P2pLanServer receiver = new P2pLanServer(recv, false, true);
        check("lan: receiver binds", receiver.start(InetAddress.getLoopbackAddress()));
        int rport = receiver.getPort();
        String rtoken = receiver.getToken();
        check("lan: tokens are per server", !rtoken.equals(token));
        check("lan: receiver does not serve /answer",
                send(rport, post("/answer?t=" + rtoken, "FDR1.x")).status == 404);
        String offer = "FDS1." + "W".repeat(1200);
        check("lan: an offer → 204 and reaches the handler",
                send(rport, post("/offer?t=" + rtoken, offer)).status == 204
                        && recv.offers.size() == 1 && offer.equals(recv.offers.get(0)));
        recv.offerBusy = true;
        check("lan: a busy receiver answers 409",
                send(rport, post("/offer?t=" + rtoken, "FDS1.other")).status == 409);
        recv.offerBusy = false;
        check("lan: and takes the next offer once free",
                send(rport, post("/offer?t=" + rtoken, "FDS1.third")).status == 204);
        check("lan: a slow client that sends the head first still gets served", slowClient(rport, rtoken));
        receiver.stop();

        check("lan: offerUrl brackets an IPv6 host",
                "http://[fe80::1]:5/offer?t=t".equals(P2pLanServer.offerUrl("fe80::1", 5, "t")));
        check("lan: offerUrl leaves IPv4 and bracketed hosts alone",
                "http://192.168.0.9:5/offer?t=t".equals(P2pLanServer.offerUrl("192.168.0.9", 5, "t"))
                        && "http://[::1]:5/offer?t=t".equals(P2pLanServer.offerUrl("[::1]", 5, "t")));
    }

    /* ── P2pLanServer: one deadline per connection, a bounded worker pool ── */

    /** Opens a connection and trickles a request head that never ends. */
    private static Socket trickler(int port) throws IOException {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
        socket.setSoTimeout(5000);
        Thread feeder = new Thread(() -> {
            try {
                OutputStream out = socket.getOutputStream();
                byte[] head = "POST /offer?t=".getBytes(StandardCharsets.US_ASCII);
                for (int i = 0; ; i++) {
                    out.write(head[i % head.length]);
                    out.flush();
                    Thread.sleep(40);
                }
            } catch (Exception e) {
                // The server hung up — expected.
            }
        }, "trickler");
        feeder.setDaemon(true);
        feeder.start();
        return socket;
    }

    /** Milliseconds until the server ends the connection (read sees EOF or a reset). */
    private static long msUntilClosed(Socket socket) {
        long start = System.currentTimeMillis();
        try {
            InputStream in = socket.getInputStream();
            while (in.read() != -1) {
                // drain whatever status line came back
            }
        } catch (IOException e) {
            // reset or our own timeout — either way, measured below
        }
        return System.currentTimeMillis() - start;
    }

    private static void lanDeadlineAndPool() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        P2pLanServer server = new P2pLanServer(handler, false, true);
        server.mDeadlineMs = 600;
        check("deadline: binds", server.start(InetAddress.getLoopbackAddress()));
        int port = server.getPort();
        String token = server.getToken();

        // A byte every 40 ms resets any per-read timeout forever; the shipped
        // server held its ONE thread for as long as the client liked.
        Socket slow = trickler(port);
        long held = msUntilClosed(slow);
        slow.close();
        check("deadline: a trickling client is cut at the connection deadline",
                held >= 400 && held < 2500, held + " ms");

        // While one client trickles, another is served at once.
        Socket hog = trickler(port);
        Thread.sleep(50);
        long t0 = System.currentTimeMillis();
        Reply served = send(port, post("/offer?t=" + token, "FDS1.while-busy"));
        long took = System.currentTimeMillis() - t0;
        check("pool: a second client is served while the first trickles",
                served.status == 204 && took < 400, served.status + " in " + took + " ms");
        hog.close();
        Thread.sleep(700); // let the hog's worker hit its deadline

        // Fill every worker, then one more: dropped at once, never queued
        // behind the hogs.
        server.stop();
        awaitAcceptThreadGone();
        P2pLanServer pooled = new P2pLanServer(handler, false, true);
        pooled.mDeadlineMs = 3000;
        check("pool: binds", pooled.start(InetAddress.getLoopbackAddress()));
        int pport = pooled.getPort();
        List<Socket> hogs = new ArrayList<>();
        for (int i = 0; i < P2pLanServer.MAX_CONCURRENT; i++) {
            hogs.add(trickler(pport));
        }
        Thread.sleep(200); // every hog accepted and handed to a worker
        Socket extra = new Socket(InetAddress.getLoopbackAddress(), pport);
        extra.setSoTimeout(5000);
        long dropped = msUntilClosed(extra);
        extra.close();
        check("pool: a connection beyond MAX_CONCURRENT is closed at once",
                dropped < 1000, dropped + " ms");
        for (Socket h : hogs) {
            h.close();
        }
        Thread.sleep(3300); // the hogs' workers time out and free up
        Reply after = send(pport, post("/offer?t=" + pooled.getToken(), "FDS1.after"));
        check("pool: the server serves again once the hogs are gone", after.status == 204);

        // The token compare: exact bytes only (constant time, length-checked).
        String t = pooled.getToken();
        check("token: a prefix of the token → 403",
                send(pport, post("/offer?t=" + t.substring(0, t.length() - 1), "FDS1.x")).status == 403);
        check("token: the token plus a byte → 403",
                send(pport, post("/offer?t=" + t + "0", "FDS1.x")).status == 403);
        check("token: the token in upper case → 403",
                send(pport, post("/offer?t=" + t.toUpperCase(), "FDS1.x")).status == 403
                        || t.equals(t.toUpperCase()));
        pooled.stop();

        // Many offers at once on a fresh server: the handler is called from
        // several workers, and the one that wins is the only 204.
        AtomicBoolean busy = new AtomicBoolean(false);
        AtomicInteger delivered = new AtomicInteger();
        P2pLanServer racing = new P2pLanServer(new P2pLanServer.Handler() {
            @Override
            public boolean onAnswer(String body) {
                return false;
            }

            @Override
            public boolean onOffer(String body) {
                if (!busy.compareAndSet(false, true)) {
                    return false;
                }
                delivered.incrementAndGet();
                return true;
            }
        }, false, true);
        check("race: binds", racing.start(InetAddress.getLoopbackAddress()));
        int rp = racing.getPort();
        String rt = racing.getToken();
        List<Thread> senders = new ArrayList<>();
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        for (int i = 0; i < 6; i++) {
            final int n = i;
            Thread th = new Thread(() -> {
                try {
                    int status = send(rp, post("/offer?t=" + rt, "FDS1.r" + n)).status;
                    if (status == 204) {
                        ok.incrementAndGet();
                    } else if (status == 409) {
                        conflict.incrementAndGet();
                    }
                } catch (IOException e) {
                    // a connection dropped by a full pool counts as neither
                }
            });
            senders.add(th);
            th.start();
        }
        for (Thread th : senders) {
            th.join(5000);
        }
        check("race: exactly one of several simultaneous offers is taken",
                ok.get() == 1 && delivered.get() == 1, ok.get() + " taken, " + conflict.get() + " refused");
        racing.stop();
    }

    private static void awaitAcceptThreadGone() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            boolean alive = false;
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if ("P2pLan".equals(t.getName()) && t.isAlive()) {
                    alive = true;
                }
            }
            if (!alive) {
                return;
            }
            Thread.sleep(10);
        }
    }

    /** The head and the body arrive in two writes with a pause between — the
     *  server must wait for the declared body rather than 400 on the gap. */
    private static boolean slowClient(int port, String token) throws Exception {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            String body = "FDS1.slow";
            out.write(("POST /offer?t=" + token + " HTTP/1.1\r\nContent-Length: " + body.length()
                    + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            Thread.sleep(150);
            out.write(body.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return readReply(socket.getInputStream()).status == 204;
        }
    }
}
