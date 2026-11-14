package com.archbox.container;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * First-run screen: pick where the Arch Linux ARM tarball comes from (download it, reuse the
 * cached download, or choose a file), then watch InstallService do the work. Everything slow runs
 * in the service, so leaving the app does not interrupt it. No Termux classes here.
 */
public class OnboardingActivity extends Activity implements InstallService.Watcher {
    private static final int RC_PICK = 1, RC_EXPORT = 2, RC_NOTIF = 3;
    private static final int FG = 0xFFE6E6E6, DIM = 0xFF9AA0A6, ACCENT = 0xFF4FC3F7, ERR = 0xFFFF6B6B;

    private Bootstrap bootstrap;
    private Uri picked, export;
    private RadioButton rbDl, rbFile, rbCache;
    private CheckBox keep;
    private Button exportBtn;
    private TextView fileLabel, exportLabel, phase, bytes, logView, errView;
    private View chooser, progressBox;
    private ProgressBar bar;
    private ScrollView logScroll;
    private Button cancel, back;
    private boolean left;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        bootstrap = new Bootstrap(getFilesDir());
        if (bootstrap.installed() && !InstallService.S.running) { openTerminal(); return; }
        setContentView(buildUi());
    }

    @Override protected void onStart() {
        super.onStart();
        if (left) return;
        InstallService.watch(this);
        render();
    }

    @Override protected void onStop() {
        super.onStop();
        InstallService.unwatch(this);
    }

    private void openTerminal() {
        if (left) return;
        left = true;
        startActivity(new Intent(this, TerminalActivity.class));
        finish();
    }

    // ---- UI ----------------------------------------------------------------------------

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private RadioButton radio(String s) {
        RadioButton r = new RadioButton(this);
        r.setText(s); r.setTextColor(FG); r.setTextSize(16); r.setId(View.generateViewId());
        return r;
    }

    private LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(top);
        return p;
    }

    private View buildUi() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(40), dp(20), dp(20));
        col.setBackgroundColor(Color.BLACK);

        col.addView(text("Archbox", 28, FG, true));
        col.addView(text("A full Arch Linux ARM system in a terminal. Choose where the root filesystem "
                + "comes from. Installing takes a few minutes and keeps going if you leave the app.", 14, DIM, false), lp(8));

        // ---- source chooser ----
        LinearLayout ch = new LinearLayout(this);
        ch.setOrientation(LinearLayout.VERTICAL);
        chooser = ch;

        RadioGroup rg = new RadioGroup(this);
        rbCache = null;
        File cached = bootstrap.cached();
        if (cached != null) {
            rbCache = radio("Use the cached download (" + InstallService.mib(cached.length()) + ")");
            rg.addView(rbCache);
        }
        rbDl = radio("Download Arch Linux ARM (aarch64)");
        rbFile = radio("Choose a tarball from storage");
        rg.addView(rbDl);
        rg.addView(rbFile);
        (cached != null ? rbCache : rbDl).setChecked(true);
        ch.addView(rg, lp(24));

        fileLabel = text("Accepts .tar.gz, .tgz, .tar.xz and .tar", 12, DIM, false);
        ch.addView(fileLabel);
        Button pick = new Button(this);
        pick.setText("Browse...");
        pick.setAllCaps(false);
        pick.setOnClickListener(v -> pickFile());
        ch.addView(pick, lp(4));
        rbFile.setOnClickListener(v -> { if (picked == null) pickFile(); });

        keep = new CheckBox(this);
        keep.setText("Keep the downloaded tarball for reinstalls");
        keep.setTextColor(FG);
        keep.setChecked(true);
        ch.addView(keep, lp(20));

        exportBtn = new Button(this);
        exportBtn.setText("Also save a copy to...");
        exportBtn.setAllCaps(false);
        exportBtn.setOnClickListener(v -> pickExport());
        ch.addView(exportBtn, lp(0));
        exportLabel = text("A copy outside the app survives uninstalling it. Downloads only.", 12, DIM, false);
        ch.addView(exportLabel);

        if (cached != null) {
            Button del = new Button(this);
            del.setText("Delete the cached download");
            del.setAllCaps(false);
            del.setOnClickListener(v -> { bootstrap.clearCache(); recreate(); });
            ch.addView(del, lp(8));
        }

        Button install = new Button(this);
        install.setText("Install");
        install.setAllCaps(false);
        install.setTextSize(18);
        install.setOnClickListener(v -> onInstall());
        ch.addView(install, lp(24));
        col.addView(ch);

        // ---- progress ----
        LinearLayout pb = new LinearLayout(this);
        pb.setOrientation(LinearLayout.VERTICAL);
        pb.setVisibility(View.GONE);
        progressBox = pb;
        phase = text("", 18, FG, true);
        pb.addView(phase, lp(24));
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        pb.addView(bar, lp(8));
        bytes = text("", 13, DIM, false);
        pb.addView(bytes);
        errView = text("", 14, ERR, false);
        pb.addView(errView, lp(8));
        logView = text("", 11, DIM, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logScroll = new ScrollView(this);
        logScroll.addView(logView);
        pb.addView(logScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        pb.addView(text("You can minimise the app: the install continues in the background.", 12, DIM, false), lp(8));
        cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setAllCaps(false);
        cancel.setOnClickListener(v -> InstallService.cancel(this));
        pb.addView(cancel, lp(8));
        back = new Button(this);
        back.setText("Back");
        back.setAllCaps(false);
        back.setOnClickListener(v -> { InstallService.S.reset(); render(); });
        pb.addView(back, lp(8));
        col.addView(pb, new LinearLayout.LayoutParams(-1, 0, 1f));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(Color.BLACK);
        sv.addView(col);
        return sv;
    }

    private void render() {
        if (left || chooser == null) return;
        InstallService.State s = InstallService.S;
        if (bootstrap.installed() && !s.running) { openTerminal(); return; }
        boolean show = s.running || s.error != null;
        chooser.setVisibility(show ? View.GONE : View.VISIBLE);
        progressBox.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;

        phase.setText(s.error != null ? "Install failed" : s.phase);
        long total = s.total, cur = s.cur;
        if (total > 0 && s.running) {
            bar.setIndeterminate(false);
            bar.setProgress((int) (cur * 1000 / total));
            bytes.setText(InstallService.mib(cur) + " / " + InstallService.mib(total) + "  ("
                    + (cur * 100 / total) + "%)");
        } else if (s.running) {
            bar.setIndeterminate(true);
            bytes.setText(cur > 0 ? InstallService.mib(cur) : "");
        }
        bar.setVisibility(s.running ? View.VISIBLE : View.GONE);
        bytes.setVisibility(s.running ? View.VISIBLE : View.GONE);
        errView.setText(s.error == null ? "" : s.error);
        errView.setVisibility(s.error == null ? View.GONE : View.VISIBLE);
        cancel.setVisibility(s.running ? View.VISIBLE : View.GONE);
        back.setVisibility(s.running ? View.GONE : View.VISIBLE);
        logView.setText(s.log());
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    @Override public void onChange() { render(); }

    // ---- actions -----------------------------------------------------------------------

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, RC_PICK);
    }

    private void pickExport() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("application/gzip")
                .putExtra(Intent.EXTRA_TITLE, "ArchLinuxARM-aarch64-latest.tar.gz")
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, RC_EXPORT);
    }

    @Override protected void onActivityResult(int rc, int result, Intent data) {
        super.onActivityResult(rc, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        int flag = rc == RC_PICK ? Intent.FLAG_GRANT_READ_URI_PERMISSION : Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try { getContentResolver().takePersistableUriPermission(u, flag); } catch (Exception ignored) { }
        if (rc == RC_PICK) {
            picked = u;
            long size = Bootstrap.sizeOf(getContentResolver(), u);
            fileLabel.setText(Bootstrap.nameOf(getContentResolver(), u)
                    + (size > 0 ? "  (" + InstallService.mib(size) + ")" : ""));
            rbFile.setChecked(true);
        } else if (rc == RC_EXPORT) {
            export = u;
            exportLabel.setText("Copy will be saved as: " + Bootstrap.nameOf(getContentResolver(), u));
        }
    }

    private void onInstall() {
        if (rbFile.isChecked() && picked == null) {
            Toast.makeText(this, "Choose a file first", Toast.LENGTH_SHORT).show();
            return;
        }
        // Android 13+ apps targeting 33+ must ask before the progress notification can show.
        if (Build.VERSION.SDK_INT >= 33 && BuildConfig.TARGET_SDK >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, RC_NOTIF);
            return;
        }
        begin();
    }

    @Override public void onRequestPermissionsResult(int rc, String[] perms, int[] res) {
        super.onRequestPermissionsResult(rc, perms, res);
        if (rc == RC_NOTIF) begin();            // install either way; without it there is just no notification
    }

    private void begin() {
        Bootstrap.Request.Kind kind = rbFile.isChecked() ? Bootstrap.Request.Kind.FILE
                : (rbCache != null && rbCache.isChecked()) ? Bootstrap.Request.Kind.CACHED
                : Bootstrap.Request.Kind.DOWNLOAD;
        InstallService.start(this, new Bootstrap.Request(kind,
                kind == Bootstrap.Request.Kind.FILE ? picked : null,
                kind == Bootstrap.Request.Kind.DOWNLOAD ? export : null,
                keep.isChecked()));
        render();
    }
}
