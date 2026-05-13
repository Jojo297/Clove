package com.jiangdg.utils;

import android.app.Application;
import android.util.Log;

/**
 * Simple XLog Wrapper - Fallback ke android.util.Log
 */
public class XLogWrapper {

    public static void init(Application application, String folderPath) {
        // Tidak perlu inisialisasi khusus
        Log.i("XLogWrapper", "Initialized (using android.util.Log)");
    }

    public static void v(String tag, String msg) {
        Log.v(tag, msg);
    }

    public static void i(String tag, String msg) {
        Log.i(tag, msg);
    }

    public static void d(String tag, String msg) {
        Log.d(tag, msg);
    }

    public static void w(String tag, String msg) {
        Log.w(tag, msg);
    }

    public static void w(String tag, String msg, Throwable throwable) {
        Log.w(tag, msg, throwable);
    }

    public static void w(String tag, Throwable throwable) {
        Log.w(tag, throwable);
    }

    public static void e(String tag, String msg) {
        Log.e(tag, msg);
    }

    public static void e(String tag, String msg, Throwable throwable) {
        Log.e(tag, msg, throwable);
    }
}