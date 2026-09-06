package com.xerox3025.printplugin;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class JobHistoryActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private TextView emptyText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_job_history);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.job_history_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        recyclerView = findViewById(R.id.recycler_view);
        emptyText = findViewById(R.id.empty_text);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        loadHistory();
    }

    private void loadHistory() {
        List<PrintJobHistory.JobRecord> jobs = PrintJobHistory.getHistory(this);
        if (jobs.isEmpty()) {
            recyclerView.setVisibility(View.GONE);
            emptyText.setVisibility(View.VISIBLE);
        } else {
            recyclerView.setVisibility(View.VISIBLE);
            emptyText.setVisibility(View.GONE);
            recyclerView.setAdapter(new JobAdapter(jobs));
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, R.string.clear_history);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == 1) {
            PrintJobHistory.clearHistory(this);
            loadHistory();
            return true;
        }
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    static class JobAdapter extends RecyclerView.Adapter<JobAdapter.ViewHolder> {
        private final List<PrintJobHistory.JobRecord> jobs;

        JobAdapter(List<PrintJobHistory.JobRecord> jobs) {
            this.jobs = jobs;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_job_history, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            PrintJobHistory.JobRecord job = jobs.get(position);
            holder.name.setText(job.jobName);
            String pageStr = job.pageCount > 0
                    ? " — " + holder.itemView.getContext().getString(R.string.pages_count, job.pageCount)
                    : "";
            holder.time.setText(job.getFormattedTime() + pageStr);

            String displayStatus = job.status;
            if ("COMPLETED".equals(job.status)) {
                displayStatus = holder.itemView.getContext().getString(R.string.job_status_completed);
                holder.status.setTextColor(Color.parseColor("#2E7D32"));
            } else if ("FAILED".equals(job.status)) {
                displayStatus = holder.itemView.getContext().getString(R.string.job_status_failed);
                holder.status.setTextColor(Color.parseColor("#C62828"));
            } else if ("CANCELLED".equals(job.status)) {
                displayStatus = holder.itemView.getContext().getString(R.string.job_status_cancelled);
                holder.status.setTextColor(Color.parseColor("#F57F17"));
            } else {
                holder.status.setTextColor(Color.parseColor("#F57F17"));
            }
            holder.status.setText(displayStatus);

            if (job.detail != null && !job.detail.isEmpty()) {
                holder.detail.setText(job.detail);
                holder.detail.setVisibility(View.VISIBLE);
            } else {
                holder.detail.setVisibility(View.GONE);
            }
        }

        @Override
        public int getItemCount() {
            return jobs.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView name, time, status, detail;

            ViewHolder(View v) {
                super(v);
                name = v.findViewById(R.id.job_name);
                time = v.findViewById(R.id.job_time);
                status = v.findViewById(R.id.job_status);
                detail = v.findViewById(R.id.job_detail);
            }
        }
    }
}
