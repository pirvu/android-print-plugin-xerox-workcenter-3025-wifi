package com.xerox3025.printplugin;

import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ScanActivity extends AppCompatActivity {

    private static final String TAG = "ScanActivity";

    private Spinner spinnerResolution;
    private Spinner spinnerColor;
    private TextView textStatus;
    private ImageView imagePreview;
    private View buttonsRow;

    private byte[] lastScanData;
    private String lastScanFormat;

    private final int[] resolutions = {75, 100, 150, 200, 300};
    private final String[] resolutionLabels = {"75 DPI", "100 DPI", "150 DPI", "200 DPI", "300 DPI"};
    private final String[] colorModes = {"Grayscale8", "RGB24", "BlackAndWhite1"};
    private final String[] colorLabels = {"Grayscale", "Color", "Black & White"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.scan_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        spinnerResolution = findViewById(R.id.spinner_resolution);
        spinnerColor = findViewById(R.id.spinner_color);
        textStatus = findViewById(R.id.text_status);
        imagePreview = findViewById(R.id.image_preview);
        buttonsRow = findViewById(R.id.buttons_row);

        spinnerResolution.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, resolutionLabels));
        spinnerResolution.setSelection(4); // 300 DPI default

        spinnerColor.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, colorLabels));
        spinnerColor.setSelection(0); // Grayscale default

        findViewById(R.id.btn_scan).setOnClickListener(v -> startScan());
        findViewById(R.id.btn_save).setOnClickListener(v -> saveImage());
        findViewById(R.id.btn_share).setOnClickListener(v -> shareImage());

        // Check scanner status on open
        checkScannerStatus();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private String getPrinterIp() {
        return PreferenceManager.getDefaultSharedPreferences(this)
                .getString("printer_ip", "192.168.0.109");
    }

    private void checkScannerStatus() {
        textStatus.setText(R.string.scan_status_checking);
        new Thread(() -> {
            WsdScanClient.ScannerStatus status = WsdScanClient.getScannerStatus(getPrinterIp());
            runOnUiThread(() -> {
                if (status.isIdle()) {
                    textStatus.setText(R.string.scan_status_ready);
                } else if ("Error".equals(status.state)) {
                    textStatus.setText(getString(R.string.scan_status_error, status.error));
                } else {
                    textStatus.setText(getString(R.string.scan_status_busy, status.state));
                }
            });
        }).start();
    }

    private void startScan() {
        String ip = getPrinterIp();
        int resIndex = spinnerResolution.getSelectedItemPosition();
        int colorIndex = spinnerColor.getSelectedItemPosition();

        WsdScanClient.ScanSettings settings = new WsdScanClient.ScanSettings();
        settings.resolution = resolutions[resIndex];
        settings.colorMode = colorModes[colorIndex];
        settings.format = "jfif"; // JPEG for smaller file size
        lastScanFormat = "jpg";

        textStatus.setText(R.string.scan_status_scanning);
        buttonsRow.setVisibility(View.GONE);
        imagePreview.setImageBitmap(null);
        findViewById(R.id.btn_scan).setEnabled(false);

        new Thread(() -> {
            // Step 1: Create scan job
            WsdScanClient.ScanJobResult job = WsdScanClient.createScanJob(ip, settings);
            if (!job.success) {
                runOnUiThread(() -> {
                    textStatus.setText(getString(R.string.scan_status_error, job.error));
                    findViewById(R.id.btn_scan).setEnabled(true);
                });
                return;
            }

            runOnUiThread(() -> textStatus.setText(R.string.scan_status_retrieving));

            // Step 2: Retrieve image — this call blocks until the scanner finishes
            byte[] imageData = WsdScanClient.retrieveImage(ip, job.jobId, job.imageUri);

            byte[] finalImageData = imageData;
            runOnUiThread(() -> {
                findViewById(R.id.btn_scan).setEnabled(true);
                if (finalImageData != null && finalImageData.length > 0) {
                    lastScanData = finalImageData;
                    Bitmap bmp = BitmapFactory.decodeByteArray(finalImageData, 0, finalImageData.length);
                    if (bmp != null) {
                        imagePreview.setImageBitmap(bmp);
                        buttonsRow.setVisibility(View.VISIBLE);
                        textStatus.setText(getString(R.string.scan_status_done,
                                finalImageData.length / 1024));
                    } else {
                        textStatus.setText(R.string.scan_status_decode_error);
                    }
                } else {
                    textStatus.setText(R.string.scan_status_no_data);
                }
            });
        }).start();
    }

    private void saveImage() {
        if (lastScanData == null) return;

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String filename = "Scan_" + timestamp + "." + lastScanFormat;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ use MediaStore
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
                values.put(MediaStore.MediaColumns.MIME_TYPE,
                        "jpg".equals(lastScanFormat) ? "image/jpeg" : "image/tiff");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS);

                Uri uri = getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    OutputStream out = getContentResolver().openOutputStream(uri);
                    if (out != null) {
                        out.write(lastScanData);
                        out.close();
                    }
                    Toast.makeText(this,
                            getString(R.string.scan_saved, filename), Toast.LENGTH_LONG).show();
                }
            } else {
                // Older Android — write to Downloads directly
                File dir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                File file = new File(dir, filename);
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(lastScanData);
                fos.close();
                Toast.makeText(this,
                        getString(R.string.scan_saved, file.getAbsolutePath()),
                        Toast.LENGTH_LONG).show();
            }
        } catch (IOException e) {
            Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void shareImage() {
        if (lastScanData == null) return;

        try {
            // Write to cache for sharing
            String filename = "scan." + lastScanFormat;
            File cacheFile = new File(getCacheDir(), filename);
            FileOutputStream fos = new FileOutputStream(cacheFile);
            fos.write(lastScanData);
            fos.close();

            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", cacheFile);

            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("jpg".equals(lastScanFormat) ? "image/jpeg" : "image/tiff");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, getString(R.string.scan_share_title)));
        } catch (Exception e) {
            Toast.makeText(this, "Share failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
