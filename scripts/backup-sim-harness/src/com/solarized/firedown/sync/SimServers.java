package com.solarized.firedown.sync;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The driver's side of the simulation (loaded by the application class
 * loader, never by a phone's): the two fake servers behind a {@link Gate}
 * that logs which phone asked for what and can hold one request until the
 * driver lets it go, a source stream that stops at a chosen byte, and the
 * child-first class loader that gives every phone its own copy of the real
 * classes.
 */
final class SimServers {

    private SimServers() {
    }

    /** One request held at the gate: armed once, released by the driver. */
    static final class Hold {
        final String method;
        final String suffix;
        final String userAgent;
        final AtomicBoolean armed = new AtomicBoolean(true);
        final CountDownLatch arrived = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        Hold(String method, String suffix, String userAgent) {
            this.method = method;
            this.suffix = suffix;
            this.userAgent = userAgent;
        }

        boolean awaitArrived() throws InterruptedException {
            return arrived.await(30, TimeUnit.SECONDS);
        }

        void release() {
            armed.set(false);
            release.countDown();
        }
    }

    /**
     * In front of a fake server: logs {@code ua \t method \t path?query} for
     * every request, notes any request whose headers or URL carry
     * {@link #secret} in the clear, and parks a request matching an armed
     * {@link Hold} until it is released.
     */
    static final class Gate implements HttpHandler {
        private final HttpHandler inner;
        final List<String> log = Collections.synchronizedList(new ArrayList<>());
        final List<Hold> holds = new CopyOnWriteArrayList<>();
        final AtomicInteger leaks = new AtomicInteger();
        volatile String secret;

        Gate(HttpHandler inner) {
            this.inner = inner;
        }

        Hold hold(String method, String suffix, String userAgent) {
            Hold h = new Hold(method, suffix, userAgent);
            holds.add(h);
            return h;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            String ua = ex.getRequestHeaders().getFirst("User-Agent");
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getRawQuery();
            log.add(ua + "\t" + method + "\t" + path + (query == null ? "" : "?" + query));
            String s = secret;
            if (s != null) {
                boolean leaked = ex.getRequestURI().toString().contains(s);
                for (Map.Entry<String, List<String>> h : ex.getRequestHeaders().entrySet()) {
                    for (String v : h.getValue()) {
                        leaked |= v.contains(s);
                    }
                }
                if (leaked) {
                    leaks.incrementAndGet();
                }
            }
            for (Hold h : holds) {
                if (h.method.equals(method) && path.endsWith(h.suffix)
                        && (h.userAgent == null || h.userAgent.equals(ua))
                        && h.armed.compareAndSet(true, false)) {
                    h.arrived.countDown();
                    try {
                        h.release.await(50, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    break;
                }
            }
            inner.handle(ex);
        }

        int count(String ua, String method, String suffix) {
            int n = 0;
            synchronized (log) {
                for (String line : log) {
                    String[] p = line.split("\t", 3);
                    String pathOnly = p[2].contains("?") ? p[2].substring(0, p[2].indexOf('?')) : p[2];
                    if ((ua == null || ua.equals(p[0])) && p[1].equals(method)
                            && pathOnly.endsWith(suffix)) {
                        n++;
                    }
                }
            }
            return n;
        }

        int countQuery(String ua, String method, String queryPart) {
            int n = 0;
            synchronized (log) {
                for (String line : log) {
                    String[] p = line.split("\t", 3);
                    if ((ua == null || ua.equals(p[0])) && p[1].equals(method)
                            && p[2].contains(queryPart)) {
                        n++;
                    }
                }
            }
            return n;
        }
    }

    private static HttpServer serve(HttpHandler handler) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", handler);
        http.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "sim-server");
            t.setDaemon(true);
            return t;
        }));
        http.start();
        return http;
    }

    /** FakeS3 (one bucket, signature-checking) behind a gate, path-style. */
    static final class S3 implements AutoCloseable {
        final FakeS3 s3 = new FakeS3("s3.fake");
        final Gate gate = new Gate(s3);
        final HttpServer http;

        S3() throws IOException {
            http = serve(gate);
            gate.secret = s3.secret;
        }

        String endpoint() {
            return "http://127.0.0.1:" + http.getAddress().getPort();
        }

        @Override
        public void close() {
            http.stop(0);
        }
    }

    /** FakeDav (alex / secret, a collection at /dav/) behind a gate. */
    static final class Dav implements AutoCloseable {
        final FakeDav dav = new FakeDav();
        final Gate gate = new Gate(dav);
        final HttpServer http;

        Dav() throws IOException {
            dav.mkdirs("/dav");
            http = serve(gate);
            dav.port = http.getAddress().getPort();
        }

        String url() {
            return "http://127.0.0.1:" + dav.port + "/dav/";
        }

        @Override
        public void close() {
            http.stop(0);
        }
    }

    /** A source that delivers {@code data} but stops at {@code pauseAt}
     *  until released (-1: never stops). */
    static final class Paused extends InputStream {
        private final byte[] data;
        private final long pauseAt;
        private int pos;
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch go = new CountDownLatch(1);

        Paused(byte[] data, long pauseAt) {
            this.data = data;
            this.pauseAt = pauseAt;
        }

        boolean awaitReached() throws InterruptedException {
            return reached.await(30, TimeUnit.SECONDS);
        }

        void release() {
            go.countDown();
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (pos >= data.length) {
                return -1;
            }
            if (pauseAt >= 0 && pos >= pauseAt && go.getCount() > 0) {
                reached.countDown();
                try {
                    if (!go.await(50, TimeUnit.SECONDS)) {
                        throw new IOException("harness: never released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("harness: interrupted");
                }
            }
            int n = Math.min(len, data.length - pos);
            if (pauseAt >= 0 && pos < pauseAt && go.getCount() > 0) {
                n = (int) Math.min(n, pauseAt - pos);
            }
            System.arraycopy(data, pos, b, off, n);
            pos += n;
            return n;
        }
    }

    /**
     * Child-first: every class a phone uses (the real backup classes, the
     * stubs, OkHttp, Okio, Kotlin, org.json) is the phone's own copy; only
     * the JDK and the {@code simapi} contract come from the parent.
     */
    static final class PhoneLoader extends URLClassLoader {
        PhoneLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        private static boolean shared(String name) {
            return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.")
                    || name.startsWith("sun.") || name.startsWith("com.sun.")
                    || name.startsWith("org.xml.") || name.startsWith("org.w3c.")
                    || name.startsWith("simapi.");
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (shared(name)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    try {
                        c = findClass(name);
                    } catch (ClassNotFoundException e) {
                        return super.loadClass(name, resolve);
                    }
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
        }
    }
}
