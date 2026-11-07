package dev.archbox;

import android.system.Os;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/** Downloads Arch Linux ARM (aarch64) and unpacks + configures it for the namespace launcher. */
final class Bootstrap {
    interface Out { void print(String s); }

    static final String URL = "https://os.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz";

    private final File root, tarball, marker;
    private long lastTick;

    Bootstrap(File filesDir) {
        root = new File(filesDir, "arch");
        tarball = new File(filesDir, "arch.tar.gz");
        marker = new File(root, ".archbox-installed");
    }

    File root() { return root; }
    boolean installed() { return marker.exists(); }

    void run(Out out) throws Exception {
        out.print("\u001b[1;36m== Archbox first boot ==\u001b[0m\n");
        out.print("Downloading Arch Linux ARM (aarch64)...\n");
        String md5 = download(out);
        String expected = fetchMd5();
        if (expected != null && !expected.equalsIgnoreCase(md5))
            throw new IOException("md5 mismatch: got " + md5 + ", expected " + expected);
        out.print(expected != null ? "\nmd5 ok: " + md5 + "\n" : "\n(no .md5 published, skipping check)\n");

        out.print("Extracting rootfs (this takes a few minutes)...\n");
        deleteRecursive(root);
        root.mkdirs();
        extract(out);
        configure();
        tarball.delete();
        if (!marker.createNewFile()) throw new IOException("cannot write marker");
        out.print("\nDone. Starting container...\n");
    }

    // ---- download -------------------------------------------------------

    private String download(Out out) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(URL).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        long total = c.getContentLengthLong();
        MessageDigest md = MessageDigest.getInstance("MD5");
        long done = 0;
        try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(tarball)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
                md.update(buf, 0, n);
                done += n;
                progress(out, "download", done, total);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private String fetchMd5() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(URL + ".md5").openConnection();
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

    private void progress(Out out, String what, long done, long total) {
        long now = System.currentTimeMillis();
        if (now - lastTick < 250 && done != total) return;
        lastTick = now;
        String pct = total > 0 ? String.format(" %3d%%", done * 100 / total) : "";
        out.print(String.format("\r\u001b[K%s: %d MiB%s", what, done >> 20,
                total > 0 ? " / " + (total >> 20) + " MiB" + pct : ""));
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

    private void extract(Out out) throws Exception {
        long total = tarball.length();
        List<String[]> dirModes = new ArrayList<>();
        Counting cnt = new Counting(new BufferedInputStream(new FileInputStream(tarball), 1 << 16));
        try (TarArchiveInputStream tin = new TarArchiveInputStream(new GZIPInputStream(cnt, 1 << 16))) {
            TarArchiveEntry e;
            byte[] buf = new byte[1 << 16];
            while ((e = tin.getNextTarEntry()) != null) {
                String name = clean(e.getName());
                if (name.isEmpty() || name.equals(".") || (".." + "/" + name + "/").contains("/../")) continue;
                File f = new File(root, name);
                String path = f.getPath();
                int mode = e.getMode() & 07777;
                File parent = f.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();

                if (e.isDirectory()) {
                    f.mkdirs();
                    Os.chmod(path, mode | 0700);
                    dirModes.add(new String[]{path, Integer.toString(mode)});
                } else if (e.isSymbolicLink()) {
                    f.delete();
                    Os.symlink(e.getLinkName(), path);
                } else if (e.isLink()) {
                    f.delete();
                    Os.link(new File(root, clean(e.getLinkName())).getPath(), path);
                } else if (e.isFile()) {
                    f.delete();
                    try (OutputStream o = new FileOutputStream(f)) {
                        int n;
                        while ((n = tin.read(buf)) > 0) o.write(buf, 0, n);
                    }
                    Os.chmod(path, mode);
                } else if (e.isFIFO()) {
                    f.delete();
                    try { Os.mkfifo(path, mode); } catch (Exception ignored) { }
                } // device nodes: skipped, the launcher mounts /dev itself
                progress(out, "extract", cnt.count, total);
            }
        }
        for (int i = dirModes.size() - 1; i >= 0; i--)
            Os.chmod(dirModes.get(i)[0], Integer.parseInt(dirModes.get(i)[1]));
        progress(out, "extract", total, total);
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

        File pd = new File(root, "etc/profile.d");
        pd.mkdirs();
        File sh = new File(pd, "archbox.sh");
        write(sh, "#!/bin/sh\n"
                + "echo \"libc: $(ldd --version 2>&1 | head -1)\"\n"
                + "if [ ! -e /var/lib/pacman/.archbox-keyring ]; then\n"
                + "  echo 'First login: initialising the pacman keyring...'\n"
                + "  pacman-key --init && pacman-key --populate archlinuxarm && touch /var/lib/pacman/.archbox-keyring\n"
                + "fi\n");
        Os.chmod(sh.getPath(), 0644);
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
