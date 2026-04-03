package com.xerox3025.printplugin;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class PrintJobHistory {

    private static final String PREFS_NAME = "print_job_history";
    private static final String KEY_JOBS = "jobs";
    private static final int MAX_ENTRIES = 50;

    public static class JobRecord {
        public final String jobName;
        public final long timestamp;
        public final String status; // COMPLETED, FAILED, CANCELLED
        public final String detail;
        public final int pageCount;

        public JobRecord(String jobName, long timestamp, String status,
                         String detail, int pageCount) {
            this.jobName = jobName;
            this.timestamp = timestamp;
            this.status = status;
            this.detail = detail;
            this.pageCount = pageCount;
        }

        public String getFormattedTime() {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(new Date(timestamp));
        }

        JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("name", jobName);
            obj.put("ts", timestamp);
            obj.put("status", status);
            obj.put("detail", detail);
            obj.put("pages", pageCount);
            return obj;
        }

        static JobRecord fromJson(JSONObject obj) throws JSONException {
            return new JobRecord(
                    obj.getString("name"),
                    obj.getLong("ts"),
                    obj.getString("status"),
                    obj.optString("detail", ""),
                    obj.optInt("pages", 0)
            );
        }
    }

    public static void addJob(Context context, JobRecord record) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY_JOBS, "[]"));
            arr.put(record.toJson());

            // Trim to MAX_ENTRIES (keep newest)
            while (arr.length() > MAX_ENTRIES) {
                arr.remove(0);
            }

            prefs.edit().putString(KEY_JOBS, arr.toString()).apply();
        } catch (JSONException e) {
            PrintLog.e("PrintJobHistory", "Failed to save job record", e);
        }
    }

    public static List<JobRecord> getHistory(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        List<JobRecord> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY_JOBS, "[]"));
            for (int i = arr.length() - 1; i >= 0; i--) { // newest first
                list.add(JobRecord.fromJson(arr.getJSONObject(i)));
            }
        } catch (JSONException e) {
            PrintLog.e("PrintJobHistory", "Failed to load history", e);
        }
        return list;
    }

    public static void clearHistory(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_JOBS).apply();
    }
}
