package dev.archbox;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.nio.charset.StandardCharsets;

/**
 * The whole app is one terminal. First launch: a throwaway session shows download/extract
 * progress. Then a session running libarchbox.so (the namespace launcher) takes over; its
 * pty slave is the container's /dev/console and /dev/ttyS0.
 */
public class TerminalActivity extends Activity implements TerminalSessionClient, TerminalViewClient {
    private TerminalView view;
    private TerminalSession session;
    private Bootstrap bootstrap;
    private boolean ctrl, bootstrapStarted;
    private Button ctrlButton;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Crumbs.add(this, "TerminalActivity.onCreate");
        bootstrap = new Bootstrap(getFilesDir());

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Color.BLACK);

        Crumbs.add(this, "creating TerminalView");
        view = new TerminalView(this, null);
        view.setTerminalViewClient(this);
        view.setTextSize((int) (14 * getResources().getDisplayMetrics().scaledDensity));
        view.setKeepScreenOn(true);
        col.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        col.addView(extraKeys());
        setContentView(col);

        Crumbs.add(this, "views ready, installed=" + bootstrap.installed());
        if (bootstrap.installed()) startContainer();
        else attach(new TerminalSession("/system/bin/sleep", getFilesDir().getPath(),
                new String[]{"sleep", "2147483647"}, env(), 2000, this));
    }

    static final String VERSION = BuildConfig.GIT_SHA + " sdk" + BuildConfig.TARGET_SDK;

    /**
     * Optional manual override of the launcher's tier selection (unshare -> proot -> fakechroot):
     * put one of those words in files/archbox-tier. Absent/empty/unknown = automatic.
     */
    private String tierOverride() {
        try {
            String t = new String(java.nio.file.Files.readAllBytes(
                    new java.io.File(getFilesDir(), "archbox-tier").toPath()),
                    StandardCharsets.UTF_8).trim();
            return (t.equals("unshare") || t.equals("proot") || t.equals("fakechroot")) ? t : null;
        } catch (Exception e) { return null; }
    }

    private String[] env() {
        java.util.List<String> e = new java.util.ArrayList<>(java.util.Arrays.asList(
            "TERM=xterm-256color", "COLORTERM=truecolor",
            "ARCHBOX_VERSION=" + VERSION,
            // targetSdk >= 29 cannot execve() from app data: use the "userland" PRoot there
            "ARCHBOX_USERLAND=" + (BuildConfig.TARGET_SDK >= 29 ? "1" : "0"),
            // scratch dir for proot (tier 2) and for fakechroot's un-translated /tmp-style paths (tier 3)
            "ARCHBOX_TMP=" + new java.io.File(getFilesDir(), "proot-tmp").getPath()));
        String tier = tierOverride();
        if (tier != null) e.add("ARCHBOX_TIER=" + tier);
        return e.toArray(new String[0]);
    }

    private void attach(TerminalSession s) {
        Crumbs.add(this, "attachSession");
        session = s;
        view.attachSession(s);
        Crumbs.add(this, "attached");
        view.requestFocus();
    }

    private void startContainer() {
        Crumbs.add(this, "startContainer");
        String launcher = getApplicationInfo().nativeLibraryDir + "/libarchbox.so";
        attach(new TerminalSession(launcher, getFilesDir().getPath(),
                new String[]{"archbox", bootstrap.root().getPath()}, env(), 4000, this));
    }

    private void runBootstrap(final TerminalSession progressSession) {
        new Thread(() -> {
            try {
                bootstrap.run(s -> runOnUiThread(() -> {
                    if (progressSession.getEmulator() == null) return;
                    byte[] bytes = s.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
                    progressSession.getEmulator().append(bytes, bytes.length);
                    view.onScreenUpdated();
                }));
                runOnUiThread(() -> { progressSession.finishIfRunning(); startContainer(); });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    String msg = "\r\n\u001b[1;31mBootstrap failed: " + e + "\u001b[0m\r\nRelaunch the app to retry.\r\n";
                    byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
                    if (progressSession.getEmulator() != null) {
                        progressSession.getEmulator().append(bytes, bytes.length);
                        view.onScreenUpdated();
                    }
                });
            }
        }, "bootstrap").start();
    }

    private View extraKeys() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        String[] labels = {"ESC", "CTRL", "TAB", "\u2190", "\u2193", "\u2191", "\u2192", "\u2328"};
        String[] seqs = {"\u001b", null, "\t", "\u001b[D", "\u001b[B", "\u001b[A", "\u001b[C", "KEYBOARD"};
        for (int i = 0; i < labels.length; i++) {
            Button btn = new Button(this);
            btn.setText(labels[i]);
            btn.setAllCaps(false);
            btn.setTextSize(12);
            btn.setMinWidth(0);
            btn.setMinimumWidth(0);
            final String seq = seqs[i];
            if ("KEYBOARD".equals(seq)) {
                btn.setTextSize(16);
                btn.setOnClickListener(v -> toggleKeyboard());
            } else if (seq == null) {
                ctrlButton = btn;
                btn.setOnClickListener(v -> { ctrl = !ctrl; btn.setAlpha(ctrl ? 1f : 0.6f); });
                btn.setAlpha(0.6f);
            } else {
                btn.setOnClickListener(v -> { if (session != null) session.write(seq); });
            }
            row.addView(btn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return row;
    }

    private boolean kbShown;

    /** Keyboard height heuristic that works on every API level (the window is adjustResize). */
    private boolean imeVisible() {
        android.graphics.Rect r = new android.graphics.Rect();
        View root = getWindow().getDecorView();
        root.getWindowVisibleDisplayFrame(r);
        int hidden = root.getRootView().getHeight() - r.height();
        return hidden > root.getRootView().getHeight() * 0.25f;
    }

    /** Show or hide the soft keyboard (the terminal view only opens it on tap). */
    private void toggleKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        boolean visible = imeVisible();
        Crumbs.add(this, "keyboard button: visible=" + visible);
        if (visible) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
            kbShown = false;
        } else {
            view.requestFocus();
            boolean ok = imm.showSoftInput(view, 0);
            if (!ok) imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
            kbShown = true;
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (session != null) session.finishIfRunning();
    }

    // ---- TerminalSessionClient --------------------------------------------
    @Override public void onTextChanged(@NonNull TerminalSession s) { view.onScreenUpdated(); }
    @Override public void onTitleChanged(@NonNull TerminalSession s) { }
    @Override public void onSessionFinished(@NonNull TerminalSession s) {
        if (s == session && bootstrap.installed() && s.getExitStatus() == 0) finish();
    }
    @Override public void onCopyTextToClipboard(@NonNull TerminalSession s, String text) {
        ((android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE))
                .setPrimaryClip(android.content.ClipData.newPlainText("archbox", text));
    }
    @Override public void onPasteTextFromClipboard(@Nullable TerminalSession s) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (s != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            if (t != null) s.getEmulator().paste(t.toString());
        }
    }
    @Override public void onBell(@NonNull TerminalSession s) { }
    @Override public void onColorsChanged(@NonNull TerminalSession s) { }
    @Override public void onTerminalCursorStateChange(boolean state) { }
    @Override public Integer getTerminalCursorStyle() { return null; }
    @Override public void logError(String tag, String message) { android.util.Log.e(tag, message); }
    @Override public void logWarn(String tag, String message) { android.util.Log.w(tag, message); }
    @Override public void logInfo(String tag, String message) { android.util.Log.i(tag, message); }
    @Override public void logDebug(String tag, String message) { android.util.Log.d(tag, message); }
    @Override public void logVerbose(String tag, String message) { android.util.Log.v(tag, message); }
    @Override public void logStackTraceWithMessage(String tag, String message, Exception e) { android.util.Log.e(tag, message, e); }
    @Override public void logStackTrace(String tag, Exception e) { android.util.Log.e(tag, "error", e); }

    // ---- TerminalViewClient -------------------------------------------------
    @Override public float onScale(float scale) { return scale; }
    @Override public void onSingleTapUp(MotionEvent e) {
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(view, 0);
    }
    @Override public boolean shouldBackButtonBeMappedToEscape() { return false; }
    @Override public boolean shouldEnforceCharBasedInput() { return true; }
    @Override public boolean shouldUseCtrlSpaceWorkaround() { return false; }
    @Override public boolean isTerminalViewSelected() { return true; }
    @Override public void copyModeChanged(boolean copyMode) { }
    @Override public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession s) { return false; }
    @Override public boolean onKeyUp(int keyCode, KeyEvent e) { return false; }
    @Override public boolean onLongPress(MotionEvent event) { return false; }
    @Override public boolean readControlKey() { return ctrl; }
    @Override public boolean readAltKey() { return false; }
    @Override public boolean readShiftKey() { return false; }
    @Override public boolean readFnKey() { return false; }
    @Override public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession s) {
        if (ctrl) { ctrl = false; if (ctrlButton != null) ctrlButton.setAlpha(0.6f); }
        return false;
    }
    @Override public void onEmulatorSet() {
        if (!bootstrap.installed() && !bootstrapStarted && session != null) {
            Crumbs.add(this, "emulator ready, starting bootstrap");
            bootstrapStarted = true;
            runBootstrap(session);
        }
    }
}
