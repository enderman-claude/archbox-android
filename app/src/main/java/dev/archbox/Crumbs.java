package dev.archbox;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Breadcrumbs + "why did the last process die" (API 30+), readable on the next launch without adb. */
final class Crumbs {
    private Crumbs() { }

    static void add(Context c, String m) {
        try (FileOutputStream o = new FileOutputStream(new File(c.getFilesDir(), "crumbs.log"), true)) {
            o.write((System.currentTimeMillis() % 100000 + " " + m + "\n").getBytes(StandardCharsets.UTF_8));
            o.getFD().sync();
        } catch (Throwable ignored) { }
    }

    static String read(Context c) {
        File f = new File(c.getFilesDir(), "crumbs.log");
        if (!f.exists()) return "(none)";
        try (FileInputStream i = new FileInputStream(f)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = i.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } catch (Exception e) { return "unreadable: " + e; }
    }

    static void clear(Context c) { new File(c.getFilesDir(), "crumbs.log").delete(); }

    static void ack(Context c) {
        try (FileOutputStream o = new FileOutputStream(new File(c.getFilesDir(), "ack"))) {
            o.write(Long.toString(System.currentTimeMillis()).getBytes());
        } catch (Throwable ignored) { }
    }

    static String device() {
        StringBuilder sb = new StringBuilder();
        sb.append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT)
          .append(") ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("ABIs: ").append(java.util.Arrays.toString(Build.SUPPORTED_ABIS)).append('\n');
        return sb.toString();
    }

    /** Null when the previous process exited normally or was already acknowledged. */
    static String lastExit(Context c) {
        if (Build.VERSION.SDK_INT < 30) return null;
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            List<ApplicationExitInfo> l = am.getHistoricalProcessExitReasons(c.getPackageName(), 0, 5);
            long ack = 0;
            File af = new File(c.getFilesDir(), "ack");
            if (af.exists()) try (BufferedReader r = new BufferedReader(new FileReader(af))) { ack = Long.parseLong(r.readLine().trim()); } catch (Exception ignored) { }
            StringBuilder sb = new StringBuilder();
            for (ApplicationExitInfo i : l) {
                int r = i.getReason();
                if (i.getTimestamp() <= ack) continue;
                if (r == ApplicationExitInfo.REASON_USER_REQUESTED || r == ApplicationExitInfo.REASON_USER_STOPPED
                        || (r == ApplicationExitInfo.REASON_EXIT_SELF && i.getStatus() == 0)) continue;
                sb.append("Previous exit: reason=").append(r).append(" status=").append(i.getStatus())
                  .append(" desc=").append(i.getDescription()).append('\n');
                if (r == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    try (InputStream in = i.getTraceInputStream()) {
                        if (in != null) {
                            byte[] buf = new byte[262144]; int n = in.read(buf);
                            StringBuilder run = new StringBuilder(); int shown = 0;
                            for (int k = 0; k < n && shown < 3000; k++) {
                                char ch = (char) (buf[k] & 0xff);
                                if (ch >= 32 && ch < 127) run.append(ch);
                                else { if (run.length() >= 5) { sb.append("  ").append(run).append('\n'); shown += run.length(); } run.setLength(0); }
                            }
                        }
                    } catch (Exception e) { sb.append("  (no tombstone: ").append(e).append(")\n"); }
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Throwable t) { return "exit-info error: " + t + "\n"; }
    }
}
