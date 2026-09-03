package com.adownloader;

import android.content.ContentValues;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;


import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;

public class MainActivity extends AppCompatActivity {

    private EditText urlInput;
    private Button goButton;
    private ProgressBar progressBar;
    private TextView statusText;
    private Button pauseButton;
    private Button cancelButton;
    
    private Call activeCall;

    private boolean isSizeKnown = false;
    private boolean isPaused = false;
    private String lastProgressText = "";
    private File tempFile;

    private final OkHttpClient client = new OkHttpClient();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        urlInput = findViewById(R.id.urlInput);
        goButton = findViewById(R.id.goButton);
        progressBar = findViewById(R.id.progressBar);
        statusText = findViewById(R.id.statusText);
        pauseButton = findViewById(R.id.pauseButton);
        cancelButton = findViewById(R.id.cancelButton);


        cancelButton.setOnClickListener(v -> {
            if(isPaused){                    
                if (tempFile != null && tempFile.exists()){
                        tempFile.delete();
                    }}
            else{                
                if (activeCall != null && !activeCall.isCanceled()) {
                    activeCall.cancel(); 
                    if (tempFile != null && tempFile.exists()){
                        tempFile.delete();
                    }
                }}
                isPaused = false;
                progressBar.setVisibility(View.GONE);
                statusText.setVisibility(View.GONE);
                pauseButton.setVisibility(View.GONE);
                cancelButton.setVisibility(View.GONE);
            }
        );


        pauseButton.setOnClickListener(v -> {

            LinearLayout.LayoutParams barParams = (LinearLayout.LayoutParams) progressBar.getLayoutParams();
            LinearLayout.LayoutParams textParams = (LinearLayout.LayoutParams) statusText.getLayoutParams();

            if (!isPaused) {
                isPaused = true;
                if (activeCall != null && !activeCall.isCanceled()) {
                    // Pausing is just canceling without deleting the temp file 
                    activeCall.cancel(); 
                }
                if (!isSizeKnown){
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(0);
                }
                barParams.weight = 1.5f;
                textParams.weight = 1.5f;
                
                pauseButton.setText("Resume");
                statusText.setText("Paused " + lastProgressText );
            } else {
                String downloadUrl = urlInput.getText().toString().trim();
                startDownload(downloadUrl);
                statusText.setText("Resuming");
                barParams.weight = 2.0f;
                textParams.weight = 1.0f;
            }
            progressBar.setLayoutParams(barParams);
            statusText.setLayoutParams(textParams);
        });

        goButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String downloadUrl = urlInput.getText().toString().trim();

                if (downloadUrl.isEmpty()) {
                    urlInput.setError("Please enter a link!");
                } else {
                    Toast.makeText(MainActivity.this, "Starting download...", Toast.LENGTH_SHORT).show();
                    startDownload(downloadUrl);
                    progressBar.setVisibility(View.VISIBLE);
                    statusText.setVisibility(View.VISIBLE);
                    progressBar.setProgress(0);
                    statusText.setText("Starting");
                }
            }
        });
    }

    private void startDownload(String url) {
        isPaused = false;
        pauseButton.setText("Pause");
        pauseButton.setVisibility(View.VISIBLE);
        cancelButton.setVisibility(View.VISIBLE);

        String fileName = url.substring(url.lastIndexOf('/') + 1);
        if (fileName.isEmpty() || !fileName.contains(".")) {
            fileName = "downloaded_file_" + System.currentTimeMillis();
        }

        final String finalFileName = fileName;
        tempFile = new File(getFilesDir(), "temp_" + finalFileName + ".tmp");

        long existingBytes = tempFile.exists() ? tempFile.length() : 0;

        Request headRequest = new Request.Builder()
                .url(url)
                .head()
                .build();

        activeCall = client.newCall(headRequest);
        activeCall.enqueue(new Callback() { 
            @Override
            public void onFailure(Call call, IOException e) {
                if (!isPaused) {
                    showToast("Failed to connect to server");
                    runOnUiThread(() -> {
                        progressBar.setVisibility(View.GONE);
                        statusText.setVisibility(View.GONE);
                        pauseButton.setVisibility(View.GONE);
                        cancelButton.setVisibility(View.GONE);
                    });
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    showToast("Server returned error: " + response.code());
                    response.close(); 
                    return;
                }

                String acceptRanges = response.header("Accept-Ranges"); 
                // Can choose which byte the download starts from so can resume from pause or network fail
                boolean supportsRanges = "bytes".equalsIgnoreCase(acceptRanges);

                String contentLengthHeader = response.header("Content-Length");
                long totalFileSize = 0;

                if (contentLengthHeader != null) {
                    try {
                        totalFileSize = Long.parseLong(contentLengthHeader);
                    } catch (NumberFormatException e) {
                        totalFileSize = 0; 
                    }
                }
                isSizeKnown = (totalFileSize>0);
                
                response.close();

                downloadFileStream(url, tempFile, finalFileName, totalFileSize, existingBytes, supportsRanges);
            }
        });
    }


    private void downloadFileStream(String url, File tempFile, String fileName, 
        long totalFileSize, long existingBytes, boolean supportsRanges) {

        Request.Builder requestBuilder = new Request.Builder().url(url);

        boolean isResuming = supportsRanges && existingBytes > 0;
        if (isResuming) {
            requestBuilder.addHeader("Range", "bytes=" + existingBytes + "-");
        }

        activeCall = client.newCall(requestBuilder.build());
        activeCall.enqueue(new Callback() {
        @Override
        public void onFailure(Call call, IOException e) {
            if (!isPaused) {
                showToast("Download failed or interrupted");
            }
        }

        @Override
        public void onResponse(Call call, Response response) throws IOException {
            int statusCode = response.code();

            if (statusCode != 200 && statusCode != 206) {
                showToast("Download server error HTTP " + statusCode);
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    statusText.setVisibility(View.GONE);
                    pauseButton.setVisibility(View.GONE);
                    cancelButton.setVisibility(View.GONE);
                });
                return;
            }

            try (BufferedSource source = response.body().source();
                BufferedSink sink = Okio.buffer(isResuming ? Okio.appendingSink(tempFile) : Okio.sink(tempFile))) {

                long totalBytesRead = isResuming ? existingBytes : 0;                
                long readBytes;
                
                okio.Buffer buffer = sink.buffer();
                long segmentSize = 8192;

                while ((readBytes = source.read(buffer, segmentSize)) != -1) {
                    totalBytesRead += readBytes;
                    sink.emitCompleteSegments();

                    double downloadedMB = totalBytesRead / (1024.0 * 1024.0);
                    final String progressText;

                    if (totalFileSize > 0) {
                        double totalMB = totalFileSize / (1024.0 * 1024.0);
                        progressText = String.format(java.util.Locale.US, "%.1f MB/%.1f MB", downloadedMB, totalMB);
                    } else {
                        progressText = String.format(java.util.Locale.US, "%.1f MB/??", downloadedMB);
                    }

                    if (!progressText.equals(lastProgressText)) {
                        lastProgressText = progressText;
                        final String textToDisplay = progressText;
                        final long totalBytes = totalBytesRead;
                        runOnUiThread(() -> {
                            if (totalFileSize > 0) {
                                int progress = (int) ((totalBytes * 100) / totalFileSize);
                                progressBar.setIndeterminate(false);
                                progressBar.setProgress(progress);
                            } else {
                                progressBar.setIndeterminate(true);
                            }
                            statusText.setText(textToDisplay);
                        });
                    }
                }

                sink.flush();

                publishToPublicDownloads(tempFile, fileName);

                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    statusText.setVisibility(View.GONE);
                    pauseButton.setVisibility(View.GONE);
                    cancelButton.setVisibility(View.GONE);
                    Toast.makeText(MainActivity.this, "Download finished successfully!", Toast.LENGTH_SHORT).show();
                });

            } catch (IOException e) {
                if (!isPaused) {
                    showToast("Stream interrupted!");
                }
            }
        }
    });
}


    private void showToast(String message) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
    }


    private void publishToPublicDownloads(File tempFile, String fileName) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

        ContentResolver resolver = getContentResolver();
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);

        if (uri != null) {
            try (InputStream inputStream = new FileInputStream(tempFile);
                OutputStream outputStream = resolver.openOutputStream(uri)) {
        
                byte[] buffer = new byte[8192];
                int length;
                while ((length = inputStream.read(buffer)) > 0) {
                    outputStream.write(buffer, 0, length);
                }
            }
            
            tempFile.delete();
        }
    }
}