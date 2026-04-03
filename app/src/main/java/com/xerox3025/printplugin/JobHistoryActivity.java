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
            getSupportActionBar().setTitle("Print Job History");
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
        menu.add(0, 1, 0, "Clear History");
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
            holder.time.setText(job.getFormattedTime()
                    + (job.pageCount > 0 ? " — " + job.pageCount + " page(s)" : ""));
            holder.status.setText(job.status);

            switch (job.status) {
                case "COMPLETED":
                    holder.status.setTextColor(Color.parseColor("#2E7D32"));
                    break;
                case "FAILED":
                    holder.status.setTextColor(Color.parseColor("#C62828"));
                    break;
                default:
                    holder.status.setTextColor(Color.parseColor("#F57F17"));
                    break;
            }

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
