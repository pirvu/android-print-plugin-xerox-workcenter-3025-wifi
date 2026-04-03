package com.xerox3025.printplugin;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

public class PrintLog {

    private static final int MAX_ENTRIES = 500;
    private static final LinkedList<String> entries = new LinkedList<>();
    private static final SimpleDateFormat sdf =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    public static synchronized void i(String tag, String msg) {
        Log.i(tag, msg);
        add("I", tag, msg);
    }

    public static synchronized void w(String tag, String msg) {
        Log.w(tag, msg);
        add("W", tag, msg);
    }

    public static synchronized void e(String tag, String msg) {
        Log.e(tag, msg);
        add("E", tag, msg);
    }

    public static synchronized void e(String tag, String msg, Throwable t) {
        Log.e(tag, msg, t);
        add("E", tag, msg + " — " + t.getMessage());
    }

    private static void add(String level, String tag, String msg) {
        String ts = sdf.format(new Date());
        entries.addFirst("[" + ts + "] " + level + "/" + tag + ": " + msg);
        while (entries.size() > MAX_ENTRIES) {
            entries.removeLast();
        }
    }

    public static synchronized List<String> getEntries() {
        return new ArrayList<>(entries);
    }

    public static synchronized String exportAsText() {
        StringBuilder sb = new StringBuilder();
        for (String entry : entries) {
            sb.append(entry).append("\n");
        }
        return sb.toString();
    }

    public static synchronized void clear() {
        entries.clear();
    }
}
