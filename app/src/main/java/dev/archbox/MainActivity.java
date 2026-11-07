package dev.archbox;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;

/** Deliberately tiny and free of Termux classes: shows the last crash, or starts the terminal. */
public class MainActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        File crash = new File(getFilesDir(), "crash.txt");
        if (!crash.exists()) { go(); return; }

        String text;
        try (FileInputStream in = new FileInputStream(crash)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            text = o.toString("UTF-8");
        } catch (Exception e) { text = String.valueOf(e); }

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Color.BLACK);
        col.setPadding(24, 48, 24, 24);

        TextView tv = new TextView(this);
        tv.setText("Archbox crashed last time:\n\n" + text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(11);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        Button retry = new Button(this);
        retry.setText("Clear and retry");
        retry.setOnClickListener(v -> { crash.delete(); go(); });
        col.addView(retry);
        setContentView(col);
    }

    private void go() {
        startActivity(new Intent(this, TerminalActivity.class));
        finish();
    }
}
