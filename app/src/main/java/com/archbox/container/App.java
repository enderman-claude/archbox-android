package com.archbox.container;

import android.app.Application;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

/** Writes any uncaught crash to files/crash.txt so MainActivity can show it on next launch. */
public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try (FileOutputStream o = new FileOutputStream(new File(getFilesDir(), "crash.txt"))) {
                String s = "thread: " + t.getName() + "\n" + Log.getStackTraceString(e);
                o.write(s.getBytes("UTF-8"));
            } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }
}
