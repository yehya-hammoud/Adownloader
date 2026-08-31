package com.adownloader;

import android.content.ContentValues;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
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

    private final OkHttpClient client = new OkHttpClient();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        urlInput = findViewById(R.id.urlInput);
        goButton = findViewById(R.id.goButton);
        progressBar = findViewById(R.id.progressBar);
        statusText = findViewById(R.id.statusText);

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
                    statusText.setText("0%");
                }
            }
        });
    }

    private void startDownload(String url) {
        
        String fileName = url.substring(url.lastIndexOf('/') + 1);
        if (fileName.isEmpty() || !fileName.contains(".")) {
            fileName = "downloaded_file_" + System.currentTimeMillis();
        }

        final String finalFileName = fileName;

        Request request = new Request.Builder().url(url).build();  
        client.newCall(request).enqueue(new Callback() {
            @Override  
            public void onFailure(Call call, IOException e) {
                call.cancel();
            }  

            @Override  
            public void onResponse(Call call, Response response) throws IOException {    
                
                final File tempFile = new File(getFilesDir() , "temp_" + System.currentTimeMillis() + ".tmp");  
                
                try (BufferedSource source = response.body().source();
                    BufferedSink sink = Okio.buffer(Okio.sink(tempFile))) {

                    long totalFileSize = response.body().contentLength();
                    long totalBytesRead = 0;
                    long readBytes;
                    int lastProgress = 0;

                    
                    okio.Buffer buffer = sink.buffer(); 
                    long segmentSize = 8192; 

                    while ((readBytes = source.read(buffer, segmentSize)) != -1) {
                        totalBytesRead += readBytes;
                        sink.emitCompleteSegments(); 

                        if (totalFileSize > 0) {
                            int progress = (int) ((totalBytesRead * 100) / totalFileSize);
                            if (progress > lastProgress) {
                                lastProgress = progress;

                                final int finalProgress = progress;
                                
                                runOnUiThread(() -> {
                                    progressBar.setProgress(finalProgress);
                                    statusText.setText(finalProgress + "%");
                                });
                            }
                        } else {
                                // When size is unknown, convert bytes to Megabytes smoothly (Bytes / 1024 / 1024)
                                double megabytesRead = totalBytesRead / (1024.0 * 1024.0);
    
                                // Format to 1 decimal place 
                                final String progressText = String.format(java.util.Locale.US,"%.1f MB", megabytesRead);
                                runOnUiThread(() -> {
                                    progressBar.setIndeterminate(true);
                                    statusText.setText(progressText);
                                });
                        }
                    }
                    sink.flush();
                    
                    publishToPublicDownloads(tempFile, finalFileName);

                    runOnUiThread(() -> {
                        progressBar.setIndeterminate(false);
                        progressBar.setVisibility(View.GONE);
                        statusText.setVisibility(View.GONE);
                        Toast.makeText(MainActivity.this, "Download finished successfully!", Toast.LENGTH_SHORT).show();
                    });
                }
                catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }); 
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