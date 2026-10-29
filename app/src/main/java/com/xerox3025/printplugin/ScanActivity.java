package com.xerox3025.printplugin;

import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
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

    private final JobControl control = new JobControl();
    private Bitmap previewBitmap;
    private byte[] pendingSave;
    private final androidx.activity.result.ActivityResultLauncher<String> saveDocument =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.CreateDocument("image/jpeg"), uri -> {
                byte[] data = pendingSave;
                pendingSave = null;
                if (uri == null || data == null) return;
                new Thread(() -> {
                    String message;
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        if (out == null) throw new IOException("Cannot open destination");
                        out.write(data);
                        message = "Scan saved";
                    } catch (IOException | SecurityException e) { message = "Save failed: " + e.getMessage(); }
                    String result = message;
                    updateUi(() -> Toast.makeText(this, result, Toast.LENGTH_LONG).show());
                }, "scan-save").start();
            });

    @Override protected void onDestroy() {
        control.cancel();
        imagePreview.setImageDrawable(null);
        if (previewBitmap != null) previewBitmap.recycle();
        lastScanData = null;
        pendingSave = null;
        super.onDestroy();
    }

    private void updateUi(Runnable action) {
        runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) action.run(); });
    }

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
            WsdScanClient.ScannerStatus status = WsdScanClient.getScannerStatus(getPrinterIp(), control);
            updateUi(() -> {
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
        if (previewBitmap != null) { previewBitmap.recycle(); previewBitmap = null; }
        lastScanData = null;
        findViewById(R.id.btn_scan).setEnabled(false);

        new Thread(() -> {
            // Step 1: Create scan job
            WsdScanClient.ScanJobResult job = WsdScanClient.createScanJob(ip, settings, control);
            if (!job.success) {
                updateUi(() -> {
                    textStatus.setText(getString(R.string.scan_status_error, job.error));
                    findViewById(R.id.btn_scan).setEnabled(true);
                });
                return;
            }

            updateUi(() -> textStatus.setText(R.string.scan_status_retrieving));

            // Step 2: Retrieve image — this call blocks until the scanner finishes
            byte[] imageData = WsdScanClient.retrieveImage(ip, job.jobId, job.imageUri, control);

            byte[] finalImageData = imageData;
            updateUi(() -> {
                findViewById(R.id.btn_scan).setEnabled(true);
                if (finalImageData != null && finalImageData.length > 0) {
                    lastScanData = finalImageData;
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(finalImageData, 0, finalImageData.length, options);
                    if (options.outWidth <= 0 || options.outHeight <= 0
                            || (long)options.outWidth * options.outHeight > 25000000L) {
                        textStatus.setText(R.string.scan_status_decode_error);
                        lastScanData = null;
                        return;
                    }
                    options.inSampleSize = 1;
                    while (options.outWidth / options.inSampleSize > 1600
                            || options.outHeight / options.inSampleSize > 1600) options.inSampleSize *= 2;
                    options.inJustDecodeBounds = false;
                    Bitmap bmp = BitmapFactory.decodeByteArray(finalImageData, 0, finalImageData.length, options);
                    if (bmp != null) {
                        previewBitmap = bmp;
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

        pendingSave = lastScanData;
        saveDocument.launch(filename);
    }

    private void shareImage() {
        if (lastScanData == null) return;

        try {
            // Write to cache for sharing
            String filename = "scan." + lastScanFormat;
            File scans = new File(getCacheDir(), "scans");
            if (!scans.isDirectory() && !scans.mkdirs()) throw new IOException("Cannot create scan directory");
            File[] old = scans.listFiles();
            if (old != null) for (File f : old) {
                if (System.currentTimeMillis() - f.lastModified() > 86400000L) f.delete();
            }
            File cacheFile = File.createTempFile("scan_", "." + lastScanFormat, scans);
            try (FileOutputStream fos = new FileOutputStream(cacheFile)) { fos.write(lastScanData); }

            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", cacheFile);

            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("jpg".equals(lastScanFormat) ? "image/jpeg" : "image/tiff");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.setClipData(ClipData.newRawUri("scan", uri));
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, getString(R.string.scan_share_title)));
        } catch (Exception e) {
            Toast.makeText(this, "Share failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
