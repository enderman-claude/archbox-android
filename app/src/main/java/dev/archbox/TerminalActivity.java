package dev.archbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
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

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * The terminal. Installing the rootfs is OnboardingActivity/InstallService's job; this runs
 * libarchbox.so (the container launcher, three backends) on a pty that becomes the container's
 * /dev/console and /dev/ttyS0.
 */
public class TerminalActivity extends Activity implements TerminalSessionClient, TerminalViewClient {
    private static final int MIN_SP = 8, MAX_SP = 40, DEF_SP = 14;
    private TerminalView view;
    private TerminalSession session;
    private Bootstrap bootstrap;
    private boolean ctrl, alt, volDown;
    private Button ctrlButton, altButton;
    private SharedPreferences prefs;
    private int textSp;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Crumbs.add(this, "TerminalActivity.onCreate");
        bootstrap = new Bootstrap(getFilesDir());
        if (!bootstrap.installed()) {                       // nothing to boot yet: onboarding does the install
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }
        prefs = getSharedPreferences("archbox", MODE_PRIVATE);
        textSp = prefs.getInt("textSp", DEF_SP);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Color.BLACK);

        Crumbs.add(this, "creating TerminalView");
        view = new TerminalView(this, null);
        view.setTerminalViewClient(this);
        applyTextSize();
        view.setKeepScreenOn(true);
        col.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        col.addView(extraKeys());
        setContentView(col);

        Crumbs.add(this, "views ready");
        startContainer();
    }

    static final String VERSION = BuildConfig.GIT_SHA + " sdk" + BuildConfig.TARGET_SDK;

    private File tierFile() { return new File(getFilesDir(), "archbox-tier"); }

    /** Manual backend override written by the menu: unshare | proot | fakechroot (absent = automatic). */
    private String tierOverride() {
        try {
            String t = new String(java.nio.file.Files.readAllBytes(tierFile().toPath()), StandardCharsets.UTF_8).trim();
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
            "ARCHBOX_TMP=" + new File(getFilesDir(), "proot-tmp").getPath()));
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

    private void restartContainer() {
        TerminalSession old = session;
        startContainer();                                   // `session` now points at the new one
        if (old != null) old.finishIfRunning();
    }

    // ---- text size (pinch to zoom, menu) -------------------------------------------------

    private void applyTextSize() {
        view.setTextSize((int) (textSp * getResources().getDisplayMetrics().scaledDensity));
    }

    private void changeTextSize(int delta) {
        textSp = Math.max(MIN_SP, Math.min(MAX_SP, textSp + delta));
        prefs.edit().putInt("textSp", textSp).apply();
        applyTextSize();
    }

    // ---- menu ----------------------------------------------------------------------------

    private void showMenu() {
        String[] items = {"Restart session", "Backend: " + (tierOverride() == null ? "auto" : tierOverride()) + "...",
                "Text larger", "Text smaller", "Reinstall rootfs..."};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Archbox " + VERSION)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: restartContainer(); break;
                        case 1: chooseBackend(); break;
                        case 2: changeTextSize(+2); break;
                        case 3: changeTextSize(-2); break;
                        case 4: confirmReinstall(); break;
                    }
                }).show();
    }

    private void chooseBackend() {
        final String[] values = {null, "unshare", "proot", "fakechroot"};
        String[] labels = {"Automatic (unshare, then proot, then fakechroot)", "unshare (namespaces)",
                "proot", "fakechroot"};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Container backend")
                .setItems(labels, (d, which) -> {
                    if (values[which] == null) tierFile().delete();
                    else try {
                        java.nio.file.Files.write(tierFile().toPath(), values[which].getBytes(StandardCharsets.UTF_8));
                    } catch (Exception e) { android.widget.Toast.makeText(this, "" + e, android.widget.Toast.LENGTH_LONG).show(); }
                    restartContainer();
                }).show();
    }

    private void confirmReinstall() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Reinstall rootfs?")
                .setMessage("This deletes everything inside the Arch system (packages, files in /root). "
                        + "A cached tarball is kept, so reinstalling does not need to download again.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete and reinstall", (d, w) -> {
                    if (session != null) session.finishIfRunning();
                    android.widget.Toast.makeText(this, "Deleting...", android.widget.Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        bootstrap.uninstall();
                        runOnUiThread(() -> {
                            startActivity(new Intent(this, OnboardingActivity.class));
                            finish();
                        });
                    }, "uninstall").start();
                }).show();
    }

    private void sendKey(int code) {
        long t = SystemClock.uptimeMillis();
        view.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        view.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
        clearSticky();
    }

    private void clearSticky() {
        ctrl = false; alt = false;
        paintModifier(ctrlButton, false);
        paintModifier(altButton, false);
    }

    /** Armed modifier = solid green with black text; idle = the normal button look. */
    private void paintModifier(Button b, boolean armed) {
        if (b == null) return;
        if (armed) {
            if (b.getTag() == null) b.setTag(b.getBackground());   // remember the idle look once
            b.setBackgroundColor(0xFF4CAF50);
            b.setTextColor(Color.BLACK);
        } else if (b.getTag() instanceof android.graphics.drawable.Drawable) {
            b.setBackground((android.graphics.drawable.Drawable) b.getTag());
            b.setTextColor(Color.WHITE);
        }
    }

    private Button key(String label, Runnable action) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextSize(12);
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);
        btn.setPadding(0, 0, 0, 0);
        btn.setOnClickListener(v -> action.run());
        return btn;
    }

    private LinearLayout keyRow(Button... buttons) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (Button btn : buttons) row.addView(btn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private View extraKeys() {
        ctrlButton = key("CTRL", () -> { ctrl = !ctrl; paintModifier(ctrlButton, ctrl); view.requestFocus(); });
        altButton = key("ALT", () -> { alt = !alt; paintModifier(altButton, alt); view.requestFocus(); });
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(keyRow(
                key("ESC", () -> sendKey(KeyEvent.KEYCODE_ESCAPE)),
                key("TAB", () -> sendKey(KeyEvent.KEYCODE_TAB)),
                ctrlButton, altButton,
                key("\u2190", () -> sendKey(KeyEvent.KEYCODE_DPAD_LEFT)),
                key("\u2193", () -> sendKey(KeyEvent.KEYCODE_DPAD_DOWN)),
                key("\u2191", () -> sendKey(KeyEvent.KEYCODE_DPAD_UP)),
                key("\u2192", () -> sendKey(KeyEvent.KEYCODE_DPAD_RIGHT)),
                key("\u2328", this::toggleKeyboard)));
        col.addView(keyRow(
                key("-", () -> typeChar("-")),
                key("/", () -> typeChar("/")),
                key("|", () -> typeChar("|")),
                key("~", () -> typeChar("~")),
                key("HOME", () -> sendKey(KeyEvent.KEYCODE_MOVE_HOME)),
                key("END", () -> sendKey(KeyEvent.KEYCODE_MOVE_END)),
                key("PGUP", () -> sendKey(KeyEvent.KEYCODE_PAGE_UP)),
                key("PGDN", () -> sendKey(KeyEvent.KEYCODE_PAGE_DOWN)),
                key("\u22ee", this::showMenu)));
        return col;
    }

    private void typeChar(String c) {
        if (session != null && session.isRunning()) session.write(c);
        clearSticky();
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
        if (session != null && isFinishing()) session.finishIfRunning();
    }

    // ---- TerminalSessionClient --------------------------------------------
    @Override public void onTextChanged(@NonNull TerminalSession s) { view.onScreenUpdated(); }
    @Override public void onTitleChanged(@NonNull TerminalSession s) { }
    @Override public void onSessionFinished(@NonNull TerminalSession s) {
        if (s != session) return;                           // an old session replaced by a restart
        if (s.getExitStatus() == 0 && bootstrap.installed()) { finish(); return; }
        if (s.getEmulator() != null) {                      // keep the output readable, offer a restart
            byte[] msg = "\r\n[archbox: press Enter to restart the container]\r\n".getBytes(StandardCharsets.UTF_8);
            s.getEmulator().append(msg, msg.length);
            view.onScreenUpdated();
        }
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
    @Override public float onScale(float scale) {
        if (scale < 0.9f || scale > 1.1f) { changeTextSize(scale > 1f ? +1 : -1); return 1.0f; }
        return scale;
    }
    @Override public void onSingleTapUp(MotionEvent e) {
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(view, 0);
    }
    @Override public boolean shouldBackButtonBeMappedToEscape() { return false; }
    @Override public boolean shouldEnforceCharBasedInput() { return true; }
    @Override public boolean shouldUseCtrlSpaceWorkaround() { return false; }
    @Override public boolean isTerminalViewSelected() { return true; }
    @Override public void copyModeChanged(boolean copyMode) { }
    @Override public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession s) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { volDown = true; return true; }   // Vol-down = Ctrl (like Termux)
        if (keyCode == KeyEvent.KEYCODE_ENTER && s != null && !s.isRunning()) { restartContainer(); return true; }
        return false;
    }
    @Override public boolean onKeyUp(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { volDown = false; return true; }
        return false;
    }
    @Override public boolean onLongPress(MotionEvent event) { return false; }
    @Override public boolean readControlKey() { return ctrl || volDown; }
    @Override public boolean readAltKey() { return alt; }
    @Override public boolean readShiftKey() { return false; }
    @Override public boolean readFnKey() { return false; }
    @Override public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession s) {
        // TerminalView reads readControlKey()/readAltKey() right before and after this callback, so the
        // one-shot modifiers are released after the current input has been fully processed.
        if (ctrl || alt) view.post(this::clearSticky);
        return false;
    }
    @Override public void onEmulatorSet() { }
}
