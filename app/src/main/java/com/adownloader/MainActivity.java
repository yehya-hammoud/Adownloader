package com.adownloader;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private EditText urlInput;
    private LinearLayout optionsLayout;
    private EditText threadInput;
    private Button goButton;

    private LinearLayout infoLayout;
    private TextView nameText;
    private TextView sizeText;

    private ProgressBar progressBar;
    private TextView statusText;

    private Button pauseButton;
    private Button cancelButton;

    private String url = "";
    private int threads = 1;
    private long totalFileBytes = 0;   
    private String fileName = "";

    private boolean isSizeKnown = false;
    private boolean isPaused = false;

    private int currentProgress = 0;

    public static final int STATUS_FILE_INFO = 100;
    public static final int STATUS_PROGRESS  = 101;
    public static final int STATUS_PAUSED    = 102;
    public static final int STATUS_RESUME    = 103;
    public static final int STATUS_CANCEL    = 104;
    public static final int STATUS_FINISHED  = 105;
    public static final int STATUS_ERROR     = 106;


    private ResultReceiver resultReceiver;

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Notifications disabled. Download will run silently in background.", Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        urlInput = findViewById(R.id.urlInput);
        optionsLayout = findViewById(R.id.optionsLayout);
        infoLayout = findViewById(R.id.infoLayout);
        threadInput = findViewById(R.id.threadInput); 
        goButton = findViewById(R.id.goButton);
        progressBar = findViewById(R.id.progressBar);
        statusText = findViewById(R.id.statusText);
        nameText = findViewById(R.id.nameText);
        sizeText = findViewById(R.id.sizeText);
        pauseButton = findViewById(R.id.pauseButton);
        cancelButton = findViewById(R.id.cancelButton);

        setupResultReceiver();
        checkNotificationPermission();
        setupInputWatchers();
        setupClickListeners();
    }
    
    private void setupResultReceiver(){
            resultReceiver = new ResultReceiver(new Handler(Looper.getMainLooper())) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                super.onReceiveResult(resultCode, resultData);
                if (resultData == null) return;

                switch (resultCode) {
                    case STATUS_FILE_INFO:
                        Log.d("Adownloader_UI","got info");
                        fileName = resultData.getString("file_name", "File");
                        totalFileBytes = resultData.getLong("file_size", 0);
                        int threadCount =  resultData.getInt("thread_count", 0);
                        
                        nameText.setText(fileName +" "+ threadCount + " ");
                        isSizeKnown = (totalFileBytes > 0);

                        if (isSizeKnown) {
                            long sizeInMb = totalFileBytes / (1024 * 1024);
                            sizeText.setText(sizeInMb + " MB");
                            progressBar.setIndeterminate(false);
                        } else {
                            sizeText.setText("Unknown size");
                            progressBar.setIndeterminate(true);
                        }
                        progressBar.setVisibility(View.VISIBLE);
                        statusText.setVisibility(View.VISIBLE);
                        pauseButton.setVisibility(View.VISIBLE);
                        cancelButton.setVisibility(View.VISIBLE);
                        infoLayout.setVisibility(View.VISIBLE);
                        break;

                    case STATUS_PROGRESS:
                        Log.d("Adownloader_UI","progressed");
                        currentProgress = resultData.getInt("progress");
                        long downloadedBytes = resultData.getLong("bytes_downloaded");
                        long downloadedMb = downloadedBytes / (1024 * 1024);

                        if (isSizeKnown) {
                            progressBar.setProgress(currentProgress);
                            long totalMb = totalFileBytes / (1024 * 1024);
                            Log.d("Adownloader_UI", "Progress: " + currentProgress );
                            statusText.setText(currentProgress + "% • " + downloadedMb + " / " + totalMb + " MB");
                        } else {
                            statusText.setText("Downloaded " + downloadedMb + " MB");
                        }
                        break;

                    case STATUS_PAUSED:
                        Log.d("Adownloader_UI","paused");
                        isPaused = true;
                        pauseButton.setText("Resume");
                        statusText.setText("Paused (" + currentProgress + "%)");
                        break;

                    case STATUS_CANCEL:
                        Log.d("Adownloader_UI","canceled");
                        Toast.makeText(MainActivity.this, "Download Complete!", Toast.LENGTH_LONG).show();
                        resetUiState();
                        break;
                    case STATUS_FINISHED:
                        Toast.makeText(MainActivity.this, "Download Complete!", Toast.LENGTH_LONG).show();
                        resetUiState();
                        break;

                    case STATUS_ERROR:
                        Log.d("Adownloader_UI","error");
                        String errorMsg = resultData.getString("error", "Unknown error");
                        Toast.makeText(MainActivity.this, "Error: " + errorMsg, Toast.LENGTH_LONG).show();
                        resetUiState();
                        break;
                }
            }
        };
    }

    private void checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                    != PackageManager.PERMISSION_GRANTED) {
                
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    private void setupInputWatchers() {
        urlInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                String input = s.toString().trim();
                if (android.util.Patterns.WEB_URL.matcher(input).matches()) {
                    urlInput.setError(null); 
                    url = input;
                    optionsLayout.setVisibility(View.VISIBLE);
                } else if (!input.isEmpty()) {
                    urlInput.setError("Please enter a valid URL");
                    optionsLayout.setVisibility(View.GONE);
                } else {
                    optionsLayout.setVisibility(View.GONE);
                }
            }
        });

        threadInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                String input = s.toString().trim();
                if (!input.isEmpty()) {
                    try {
                        int num = Integer.parseInt(input);
                        threads = Math.max(num, 1);
                        threadInput.setError(null);
                    } catch (NumberFormatException e) {
                        threadInput.setError("Enter a valid number");
                    }
                } else {
                    threads = 1;
                }
            }
        });
    }

    private void setupClickListeners() {
        goButton.setOnClickListener(v -> {
            if (url.isEmpty()) {
                Toast.makeText(this, "Please enter a valid URL", Toast.LENGTH_SHORT).show();
                return;
            }

            Intent intent = new Intent(this, DataSyncService.class);
            intent.putExtra("DOWNLOAD_URL", url);
            intent.putExtra("NUM_CHUNKS", threads); 
            intent.putExtra(DataSyncService.EXTRA_RECEIVER, resultReceiver);
            Log.d("Adownloader_UI", "Sending Intent with Receiver: " + (resultReceiver != null));
            ContextCompat.startForegroundService(this, intent);

            urlInput.setText("");
            optionsLayout.setVisibility(View.GONE);
            goButton.setEnabled(false);
            pauseButton.setText("Pause");
            isPaused = false;
        });

        pauseButton.setOnClickListener(v -> {
            Intent prIntent = new Intent(this, DataSyncService.class);
            if (!isPaused) {
                isPaused = true;
                prIntent.setAction(DataSyncService.ACTION_PAUSE);
            } else {
                isPaused = false;
                prIntent.setAction(DataSyncService.ACTION_RESUME);
                statusText.setText("Resuming...");
                pauseButton.setText("Pause");
            }
            ContextCompat.startForegroundService(this, prIntent);
        });

        cancelButton.setOnClickListener(v -> {
            Intent cancelIntent = new Intent(this, DataSyncService.class);
            cancelIntent.setAction(DataSyncService.ACTION_CANCEL);
            startService(cancelIntent);
            resetUiState();
        });
    }

    private void resetUiState() {
        infoLayout.setVisibility(View.GONE);
        optionsLayout.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);
        statusText.setVisibility(View.GONE);
        pauseButton.setVisibility(View.GONE);
        cancelButton.setVisibility(View.GONE);
        goButton.setEnabled(true);
        isPaused = false;
        currentProgress = 0;
    }
}