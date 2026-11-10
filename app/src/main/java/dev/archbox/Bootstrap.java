package dev.archbox;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.system.Os;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Gets an Arch Linux ARM (aarch64) rootfs onto disk and configures it for the launcher.
 * Runs on a worker thread inside InstallService, never on the UI thread. The tarball can come
 * from a (resumable) download, from the cached copy of an earlier download, or from a file the
 * user picked with the system file picker.
 */
final class Bootstrap {
    /** Receives progress. Called from the worker thread. */
    interface Listener {
        void log(String s);
        /** total <= 0 means unknown */
        void progress(String phase, long done, long total);
    }

    static final class Cancelled extends IOException { Cancelled() { super("cancelled"); } }

    static final class Request {
        enum Kind { DOWNLOAD, CACHED, FILE }
        final Kind kind; final Uri file, export; final boolean keep;
        Request(Kind kind, Uri file, Uri export, boolean keep) {
            this.kind = kind; this.file = file; this.export = export; this.keep = keep;
        }
    }

    static final String VERSION = BuildConfig.GIT_SHA + " sdk" + BuildConfig.TARGET_SDK;

    // os.archlinuxarm.org has no valid TLS certificate for its own hostname, so use HTTPS on the
    // official mirrors (list from archlinuxarm/PKGBUILDs core/pacman-mirrorlist, 2025-10-26;
    // jp.mirror and il.us.mirror were dropped from it). All are probed in parallel and the
    // fastest working one wins. Never falls back to plain HTTP.
    private static final String PATH = "/os/ArchLinuxARM-aarch64-latest.tar.gz";
    static final String[] HOSTS = {
        "mirror.archlinuxarm.org",        // GeoIP redirect to the closest mirror
        "dk.mirror.archlinuxarm.org",
        "de.mirror.archlinuxarm.org",
        "de3.mirror.archlinuxarm.org",
        "de4.mirror.archlinuxarm.org",
        "eu.mirror.archlinuxarm.org",
        "gr.mirror.archlinuxarm.org",
        "hu.mirror.archlinuxarm.org",
        "tw.mirror.archlinuxarm.org",
        "tw2.mirror.archlinuxarm.org",
        "ca.us.mirror.archlinuxarm.org",
        "fl.us.mirror.archlinuxarm.org",
        "nj.us.mirror.archlinuxarm.org",
        "mirrors.dotsrc.org/archlinuxarm", // Denmark, same host as dk.mirror
    };
    static final String[] URLS = new String[HOSTS.length];
    static {
        for (int i = 0; i < HOSTS.length; i++) URLS[i] = "https://" + HOSTS[i] + PATH;
    }
    private String chosen = URLS[0];

    private final File root, cache, part, marker, procTmp;
    private long lastTick;
    private volatile boolean cancelled;

    Bootstrap(File filesDir) {
        root = new File(filesDir, "arch");
        cache = new File(filesDir, "arch.tar.gz");          // finished download, kept for reinstalls
        part = new File(filesDir, "arch.tar.gz.part");      // unfinished download, resumed with Range
        procTmp = new File(filesDir, "proot-tmp");
        marker = new File(root, ".archbox-installed");
    }

    File root() { return root; }
    boolean installed() { return marker.exists(); }
    void cancel() { cancelled = true; }

    /** The cached tarball from an earlier download, or null. */
    File cached() { return cache.isFile() && cache.length() > 0 ? cache : null; }
    void clearCache() { cache.delete(); part.delete(); }
    /** Bytes of an unfinished download on disk (0 if none). */
    long partial() { return part.isFile() ? part.length() : 0; }

    /** Removes the installed rootfs but keeps the cached tarball. */
    void uninstall() {
        deleteRecursive(root);
        deleteRecursive(procTmp);
    }

    static String nameOf(ContentResolver cr, Uri u) {
        try (Cursor c = cr.query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Exception ignored) { }
        String p = u.getLastPathSegment();
        return p == null ? u.toString() : p;
    }

    static long sizeOf(ContentResolver cr, Uri u) {
        try (Cursor c = cr.query(u, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) { }
        return -1;
    }

    void run(Request req, ContentResolver cr, Listener l) throws Exception {
        cancelled = false;
        l.log("== Archbox install (" + VERSION + ") ==\n");
        File tar = null;
        if (req.kind == Request.Kind.DOWNLOAD) {
            tar = download(l);
            if (req.export != null) exportCopy(tar, req.export, cr, l);
        } else if (req.kind == Request.Kind.CACHED) {
            tar = cached();
            if (tar == null) throw new IOException("no cached download found");
            l.log("Using the cached download (no network needed).\n");
        }

        l.log("Extracting rootfs...\n");
        deleteRecursive(root);
        root.mkdirs();
        if (req.kind == Request.Kind.FILE) {
            long total = sizeOf(cr, req.file);
            l.log("From: " + nameOf(cr, req.file) + "\n");
            try (InputStream in = cr.openInputStream(req.file)) {
                if (in == null) throw new IOException("cannot open " + req.file);
                extract(in, total, l);
            }
        } else {
            try (InputStream in = new FileInputStream(tar)) { extract(in, tar.length(), l); }
        }
        check();
        configure();
        if (req.kind == Request.Kind.DOWNLOAD && !req.keep) tar.delete();
        if (!marker.createNewFile()) throw new IOException("cannot write marker");
        l.log("\nDone.\n");
    }

    /** A wrong archive (x86 rootfs, a data tarball...) should fail here, not as a broken container. */
    private void check() throws IOException {
        if (!new File(root, "usr/lib/ld-linux-aarch64.so.1").exists())
            throw new IOException("this does not look like an Arch Linux ARM aarch64 rootfs "
                    + "(/usr/lib/ld-linux-aarch64.so.1 is missing)");
    }

    // ---- download -------------------------------------------------------

    private static final class Probe {
        final String url; long ms = Long.MAX_VALUE; String err;
        Probe(String url) { this.url = url; }
        String host() { return url.split("/")[2]; }
    }

    /** Probe every mirror at once (HEAD of the tarball, includes TLS handshake); fastest working first. */
    private List<String> rank(Listener out) throws Exception {
        out.log("Testing mirrors...\n");
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(URLS.length);
        List<Probe> probes = new ArrayList<>();
        List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
        for (String u : URLS) {
            final Probe pr = new Probe(u);
            probes.add(pr);
            fs.add(ex.submit(() -> {
                long t0 = System.nanoTime();
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(pr.url).openConnection();
                    c.setRequestMethod("HEAD");
                    c.setConnectTimeout(6000);
                    c.setReadTimeout(6000);
                    int code = c.getResponseCode();
                    c.disconnect();
                    if (code != 200) pr.err = "HTTP " + code;
                    else pr.ms = (System.nanoTime() - t0) / 1000000;
                } catch (Exception e) { pr.err = e.toString(); }
            }));
        }
        for (java.util.concurrent.Future<?> f : fs) {
            try { f.get(10, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception e) { f.cancel(true); }
        }
        ex.shutdownNow();
        List<Probe> ok = new ArrayList<>();
        for (Probe pr : probes) {
            if (pr.ms != Long.MAX_VALUE) { ok.add(pr); out.log("  " + pr.host() + "  " + pr.ms + " ms\n"); }
            else out.log("  " + pr.host() + "  failed: " + (pr.err == null ? "timeout" : pr.err) + "\n");
        }
        java.util.Collections.sort(ok, (x, y) -> Long.compare(x.ms, y.ms));
        List<String> ranked = new ArrayList<>();
        for (Probe pr : ok) ranked.add(pr.url);
        return ranked;
    }

    /** Downloads (or resumes) the tarball into the cache and returns it. */
    private File download(Listener l) throws Exception {
        List<String> ranked = rank(l);
        if (cancelled) throw new Cancelled();
        if (ranked.isEmpty()) throw new IOException("no mirror reachable (see list above)");
        Exception last = null;
        for (String u : ranked) {
            try {
                l.log("Downloading from " + u.split("/")[2] + "\n");
                String md5 = downloadFrom(u, l);
                chosen = u;
                String expected = fetchMd5();
                if (expected != null && !expected.equalsIgnoreCase(md5)) {
                    part.delete();
                    throw new IOException("md5 mismatch: got " + md5 + ", expected " + expected);
                }
                l.log(expected != null ? "md5 ok: " + md5 + "\n" : "(no .md5 published, skipping check)\n");
                cache.delete();
                if (!part.renameTo(cache)) throw new IOException("cannot move the download into place");
                return cache;
            } catch (Cancelled c) {
                throw c;
            } catch (Exception e) {
                last = e;
                l.log("download failed: " + e + (part.length() > 0 ? " (progress is kept, it will resume)" : "") + "\n");
            }
        }
        throw new IOException("all mirrors failed, last error: " + last);
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** Appends to arch.tar.gz.part (resuming with a Range request); returns the md5 of the whole file. */
    private String downloadFrom(String u, Listener l) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            long have = part.exists() ? part.length() : 0;
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
            int code = c.getResponseCode();
            if (code == 416) { c.disconnect(); part.delete(); continue; }   // stale partial: start over
            if (code != 200 && code != 206) throw new IOException("HTTP " + code);
            boolean resume = code == 206 && have > 0;
            if (!resume) have = 0;
            long len = c.getContentLengthLong();
            long total = len > 0 ? have + len : -1;
            MessageDigest md = MessageDigest.getInstance("MD5");
            if (resume) {
                l.log("Resuming at " + (have >> 20) + " MiB\n");
                try (InputStream old = new FileInputStream(part)) {
                    byte[] buf = new byte[1 << 16]; int n;
                    while ((n = old.read(buf)) > 0) { md.update(buf, 0, n); if (cancelled) throw new Cancelled(); }
                }
            }
            long done = have;
            try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(part, resume)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled) throw new Cancelled();
                    o.write(buf, 0, n);
                    md.update(buf, 0, n);
                    done += n;
                    progress(l, "Downloading", done, total);
                }
            }
            if (total > 0 && done < total) throw new IOException("connection closed early at " + (done >> 20) + " MiB");
            progress(l, "Downloading", done, done);
            return hex(md.digest());
        }
        throw new IOException("server rejected the range request");
    }

    private String fetchMd5() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(chosen + ".md5").openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                String line = r.readLine();
                return line == null ? null : line.trim().split("\\s+")[0];
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Copies the downloaded tarball to a location the user picked (survives uninstalling the app). */
    private void exportCopy(File src, Uri dst, ContentResolver cr, Listener l) throws Exception {
        l.log("Saving a copy of the tarball...\n");
        try (InputStream in = new FileInputStream(src); OutputStream o = cr.openOutputStream(dst, "w")) {
            if (o == null) throw new IOException("cannot open the destination");
            byte[] buf = new byte[1 << 16]; int n; long done = 0, total = src.length();
            while ((n = in.read(buf)) > 0) {
                if (cancelled) throw new Cancelled();
                o.write(buf, 0, n);
                done += n;
                progress(l, "Saving copy", done, total);
            }
            progress(l, "Saving copy", total, total);
            l.log("Copy saved.\n");
        } catch (Cancelled c) {
            throw c;
        } catch (Exception e) {
            l.log("Could not save the copy (continuing): " + e + "\n");   // not fatal
        }
    }

    private void progress(Listener l, String what, long done, long total) {
        long now = System.currentTimeMillis();
        if (now - lastTick < 250 && done != total) return;
        lastTick = now;
        l.progress(what, done, total);
    }

    // ---- extraction -----------------------------------------------------

    private static final class Counting extends FilterInputStream {
        long count;
        Counting(InputStream in) { super(in); }
        @Override public int read() throws IOException { int b = super.read(); if (b >= 0) count++; return b; }
        @Override public int read(byte[] b, int o, int l) throws IOException { int n = super.read(b, o, l); if (n > 0) count += n; return n; }
    }

    private static String clean(String name) {
        while (name.startsWith("./") || name.startsWith("/")) name = name.startsWith("./") ? name.substring(2) : name.substring(1);
        return name;
    }

    /** True if any existing parent of f is a symlink or resolves outside root (would write onto the host). */
    private boolean escapesRoot(File f) {
        try {
            String rootCanon = root.getCanonicalPath();
            String parentCanon = f.getParentFile().getCanonicalPath();
            return !(parentCanon.equals(rootCanon) || parentCanon.startsWith(rootCanon + "/"));
        } catch (IOException e) { return true; }
    }

    private static void copyFile(File from, File to) throws IOException {
        try (InputStream i = new FileInputStream(from); OutputStream o = new FileOutputStream(to)) {
            byte[] b = new byte[1 << 16]; int n;
            while ((n = i.read(b)) > 0) o.write(b, 0, n);
        }
    }

    /** Picks the decompressor from the magic bytes: .tar, .tar.gz and .tar.xz are accepted. */
    private static InputStream decompress(BufferedInputStream bin) throws IOException {
        bin.mark(8);
        byte[] m = new byte[6];
        int n = 0;
        while (n < m.length) { int r = bin.read(m, n, m.length - n); if (r < 0) break; n += r; }
        bin.reset();
        if (n >= 2 && (m[0] & 0xff) == 0x1f && (m[1] & 0xff) == 0x8b) return new GZIPInputStream(bin, 1 << 16);
        if (n >= 6 && (m[0] & 0xff) == 0xfd && m[1] == '7' && m[2] == 'z' && m[3] == 'X' && m[4] == 'Z' && m[5] == 0)
            return new XZCompressorInputStream(bin);
        if (n >= 4 && (m[0] & 0xff) == 0x28 && (m[1] & 0xff) == 0xb5 && (m[2] & 0xff) == 0x2f && (m[3] & 0xff) == 0xfd)
            throw new IOException(".zst archives are not supported: use .tar.gz, .tar.xz or .tar");
        return bin;                                         // plain tar
    }

    private void extract(InputStream raw, long total, Listener out) throws Exception {
        List<String[]> dirModes = new ArrayList<>();
        Counting cnt = new Counting(raw);
        String current = "?";
        try (TarArchiveInputStream tin = new TarArchiveInputStream(decompress(new BufferedInputStream(cnt, 1 << 16)))) {
            TarArchiveEntry e;
            byte[] buf = new byte[1 << 16];
            while ((e = tin.getNextTarEntry()) != null) {
                if (cancelled) throw new Cancelled();
                String name = clean(e.getName());
                current = name;
                if (name.isEmpty() || name.equals(".") || ("../" + name + "/").contains("/../")) continue;
                File f = new File(root, name);
                String path = f.getPath();
                int mode = e.getMode() & 07777;
                File parent = f.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                // An absolute symlink earlier in the archive must never redirect writes onto the host fs.
                if (parent != null && escapesRoot(f)) {
                    out.log("skipping (path escapes rootfs): " + name + "\n");
                    continue;
                }

                try {
                    if (e.isDirectory()) {
                        f.mkdirs();
                        try { Os.chmod(path, mode | 0700); } catch (Exception ignored) { }
                        dirModes.add(new String[]{path, Integer.toString(mode)});
                    } else if (e.isSymbolicLink()) {
                        deleteRecursive(f);
                        Os.symlink(e.getLinkName(), path);
                    } else if (e.isLink()) {
                        deleteRecursive(f);
                        File target = new File(root, clean(e.getLinkName()));
                        try {
                            Os.link(target.getPath(), path);
                        } catch (Exception linkFail) {
                            // Some Android/SELinux setups refuse link(2) in app data: copy instead.
                            copyFile(target, f);
                            try { Os.chmod(path, mode); } catch (Exception ignored) { }
                        }
                    } else if (e.isFile()) {
                        deleteRecursive(f);
                        try (OutputStream o = new FileOutputStream(f)) {
                            int n;
                            while ((n = tin.read(buf)) > 0) o.write(buf, 0, n);
                        }
                        // setuid/setgid bits are meaningless with a single mapped uid; keep rwx only if refused.
                        try { Os.chmod(path, mode); } catch (Exception ex) { Os.chmod(path, mode & 0777); }
                    } else if (e.isFIFO()) {
                        deleteRecursive(f);
                        try { Os.mkfifo(path, mode); } catch (Exception ignored) { }
                    } // device nodes: skipped, the launcher mounts /dev itself
                } catch (Exception ex) {
                    throw new IOException("extracting '" + name + "' (mode " + Integer.toOctalString(mode)
                            + ", type " + (char) e.getLinkFlag() + "): " + ex, ex);
                }
                progress(out, "Extracting", cnt.count, total);
            }
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("extracting '" + current + "': " + ex, ex);
        }
        // Restore real directory modes, deepest first.
        for (int i = dirModes.size() - 1; i >= 0; i--) {
            try { Os.chmod(dirModes.get(i)[0], Integer.parseInt(dirModes.get(i)[1])); } catch (Exception ignored) { }
        }
        progress(out, "Extracting", total, total);
    }

    // ---- post-install configuration ---------------------------------------

    private void configure() throws Exception {
        // DNS: the host's resolver config is not visible inside the container
        File resolv = new File(root, "etc/resolv.conf");
        resolv.delete();
        write(resolv, "nameserver 1.1.1.1\nnameserver 8.8.8.8\n");

        // Only one uid/gid is mapped into the user namespace, so pacman's download
        // sandbox (which switches to another user) cannot work; also no mountinfo.
        File pc = new File(root, "etc/pacman.conf");
        if (pc.exists()) {
            String s = read(pc);
            s = s.replaceAll("(?m)^CheckSpace", "#CheckSpace");
            if (!s.contains("DisableSandbox")) s = s.replaceFirst("(?m)^\\[options\\]", "[options]\nDisableSandbox");
            write(pc, s);
        }

        refreshProfile();
    }

    /** (Re)writes /etc/profile.d/archbox.sh from the bundled resource; called on every launch so
     *  fixes to the first-login logic reach rootfs installs that already exist. */
    void refreshProfile() {
        try {
            File pd = new File(root, "etc/profile.d");
            if (!new File(root, "etc").isDirectory()) return;
            pd.mkdirs();
            File sh = new File(pd, "archbox.sh");
            try (InputStream in = Bootstrap.class.getResourceAsStream("/archbox-profile.sh")) {
                if (in == null) return;
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[4096]; int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                write(sh, b.toString("UTF-8"));
            }
            Os.chmod(sh.getPath(), 0644);
        } catch (Exception ignored) { }
    }

    private static void write(File f, String s) throws IOException {
        try (OutputStream o = new FileOutputStream(f)) { o.write(s.getBytes(StandardCharsets.UTF_8)); }
    }

    private static String read(File f) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (InputStream i = new FileInputStream(f)) {
            byte[] buf = new byte[8192]; int n;
            while ((n = i.read(buf)) > 0) b.write(buf, 0, n);
        }
        return b.toString("UTF-8");
    }

    private static void deleteRecursive(File f) {
        if (!f.exists() && !isLink(f)) return;
        if (f.isDirectory() && !isLink(f)) {
            try { Os.chmod(f.getPath(), 0700); } catch (Exception ignored) { }
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }

    private static boolean isLink(File f) {
        try { return android.system.Os.lstat(f.getPath()).st_mode >> 12 == 012; } catch (Exception e) { return false; }
    }
}
