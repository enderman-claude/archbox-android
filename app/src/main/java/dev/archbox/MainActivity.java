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
public class MainActivity extends Activity implements TerminalSessionClient, TerminalViewClient {
    private TerminalView view;
    private TerminalSession session;
    private Bootstrap bootstrap;
    private boolean ctrl, bootstrapStarted;
    private Button ctrlButton;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        bootstrap = new Bootstrap(getFilesDir());

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Color.BLACK);

        view = new TerminalView(this, null);
        view.setTerminalViewClient(this);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextSize((int) (14 * getResources().getDisplayMetrics().scaledDensity));
        view.setKeepScreenOn(true);
        col.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        col.addView(extraKeys());
        setContentView(col);

        if (bootstrap.installed()) startContainer();
        else attach(new TerminalSession("/system/bin/sleep", getFilesDir().getPath(),
                new String[]{"sleep", "2147483647"}, env(), 2000, this));
    }

    private String[] env() { return new String[]{"TERM=xterm-256color", "COLORTERM=truecolor"}; }

    private void attach(TerminalSession s) {
        session = s;
        view.attachSession(s);
        view.requestFocus();
    }

    private void startContainer() {
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
        String[] labels = {"ESC", "CTRL", "TAB", "\u2190", "\u2193", "\u2191", "\u2192"};
        String[] seqs = {"\u001b", null, "\t", "\u001b[D", "\u001b[B", "\u001b[A", "\u001b[C"};
        for (int i = 0; i < labels.length; i++) {
            Button btn = new Button(this);
            btn.setText(labels[i]);
            btn.setAllCaps(false);
            btn.setTextSize(12);
            btn.setMinWidth(0);
            btn.setMinimumWidth(0);
            final String seq = seqs[i];
            if (seq == null) {
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
            bootstrapStarted = true;
            runBootstrap(session);
        }
    }
}
