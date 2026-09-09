package com.adownloader;

import com.adownloader.R;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ResultReceiver;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;

public class DataSyncService extends Service {

    public static final String EXTRA_RECEIVER = "extra_receiver";

    private static final String TAG = "DataSyncService";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "data_sync_channel";

    public static final String ACTION_PAUSE = "com.adownloader.action.PAUSE";
    public static final String ACTION_RESUME = "com.adownloader.action.RESUME";
    public static final String ACTION_CANCEL = "com.adownloader.action.CANCEL";

    public static final int STATUS_FILE_INFO = 100;
    public static final int STATUS_PROGRESS  = 101;
    public static final int STATUS_PAUSED    = 102;
    public static final int STATUS_RESUME    = 103;
    public static final int STATUS_CANCEL    = 104;
    public static final int STATUS_FINISHED  = 105;
    public static final int STATUS_ERROR     = 106;

    private ResultReceiver receiver;
    private final Bundle bundle = new Bundle();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService executorService;
    private final OkHttpClient client = new OkHttpClient();
    
    private final AtomicInteger completedChunksCount = new AtomicInteger(0);
    private final AtomicLong totalBytesDownloaded = new AtomicLong(0);
    private final List<ChunkDownloader> activeChunkDownloaders = new CopyOnWriteArrayList<>();
    private final List<File> partFiles = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<Integer, Long> chunkProgressMap = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicInteger completedChunks = new java.util.concurrent.atomic.AtomicInteger(0);
    private int totalChunksCount = 0;

    private long lastNotificationUpdateTime = 0;
    
    private Call activeCall;
    private File tempFile;
    private volatile boolean isPaused = false;
    private volatile boolean isRunning = false;
    private String savedUrl = "";
    private int savedRequestedChunks = 4;
    private String savedFileName = "";
    private int currentProgress = 0;
    private long startTimeMs = 0L;

    private static final long MAX_RUNTIME_MS = TimeUnit.HOURS.toMillis(5) + TimeUnit.MINUTES.toMillis(55);
    private final Runnable shutdownRunnable = new Runnable() {
        @Override
        public void run() {
            if (System.currentTimeMillis() - startTimeMs >= MAX_RUNTIME_MS) {
                stopService();
            } else {
                mainHandler.postDelayed(this, TimeUnit.MINUTES.toMillis(5));
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        executorService = Executors.newSingleThreadExecutor();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            
            if (ACTION_PAUSE.equals(action)) {
                pauseDownload();
                updateNotification(savedFileName + ". Paused", currentProgress, true, false);
            } else if (ACTION_RESUME.equals(action)) {
                resumeDownload();
            } else if (ACTION_CANCEL.equals(action)) {
                cancelDownload(); 
            } else if (intent.hasExtra("DOWNLOAD_URL")) {
                String downloadUrl = intent.getStringExtra("DOWNLOAD_URL");
                int numChunks = intent.getIntExtra("NUM_CHUNKS", 4);
                receiver = intent.getParcelableExtra(EXTRA_RECEIVER);
                
                startTimeMs = System.currentTimeMillis();
                isRunning = true;
                mainHandler.postDelayed(shutdownRunnable, TimeUnit.MINUTES.toMillis(5));

                if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE){
                    startForeground( 
                        NOTIFICATION_ID, 
                        buildDownloadNotification("Preparing download...", 0, false, false), 
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    );
                }else{
                    startForeground( 
                        NOTIFICATION_ID, 
                        buildDownloadNotification("Preparing download...", 0, false, false)
                    );
                }

                savedUrl = downloadUrl;
                savedRequestedChunks = numChunks;
                startDownload(downloadUrl, numChunks);
            }
        }
        return START_NOT_STICKY;
    }

    private void startDownload(String url, int requestedChunks) {
        executorService.execute(() -> {
            try {
                String fileName = url.substring(url.lastIndexOf('/') + 1);
                if (fileName.isEmpty() || !fileName.contains(".")) {
                    fileName = "downloaded_file_" + System.currentTimeMillis();
                }

                final String finalFileName = fileName;
                savedFileName = finalFileName;
                tempFile = new File(getFilesDir(), "temp_" + finalFileName + ".tmp");

                boolean supportsRanges = false;
                long totalFileSize = 0;

                Request headRequest = new Request.Builder().url(url).head().build();
                activeCall = client.newCall(headRequest);

                try (Response response = activeCall.execute()) {
                    if (response.isSuccessful()) {
                        String acceptRanges = response.header("Accept-Ranges");
                        supportsRanges = "bytes".equalsIgnoreCase(acceptRanges);
                        String contentLengthHeader = response.header("Content-Length");
                        if (contentLengthHeader != null) {
                            try {
                                totalFileSize = Long.parseLong(contentLengthHeader);
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }

                boolean isSizeKnown = totalFileSize > 0;
                boolean canThread = supportsRanges && isSizeKnown;
                int actualChunks = canThread ? requestedChunks : 1;
                long chunkSize = isSizeKnown ? (totalFileSize / actualChunks) : 0;
                chunkProgressMap.clear();
                completedChunks.set(0);
                totalChunksCount = actualChunks;

                bundle.clear();
                bundle.putString("file_name", finalFileName);
                bundle.putLong("file_size", totalFileSize);
                bundle.putInt("thread_count" , actualChunks);
                if (receiver != null) receiver.send(STATUS_FILE_INFO, bundle);

                ExecutorService chunkExecutor = Executors.newFixedThreadPool(actualChunks);
                long finalTotalFileSize = totalFileSize;

                for (int i = 0; i < actualChunks; i++) {
                    long startByte = i * chunkSize;
                    long endByte = (i == actualChunks - 1) ? (totalFileSize - 1) : (startByte + chunkSize - 1);

                    File partFile = new File(getFilesDir(), "temp_" + finalFileName + ".part" + i);
                    partFiles.add(partFile);

                    if (partFile.exists()) {
                        totalBytesDownloaded.addAndGet(partFile.length());
                    }

                    ChunkDownloader downloader = new ChunkDownloader(
                        i, url, partFile, startByte, endByte, client,
                        new ChunkDownloader.ChunkProgressListener() {
                            @Override
                                public void onProgressUpdate(int chunkIndex, long currentChunkBytes, long totalChunkBytes) {
                                    chunkProgressMap.put(chunkIndex, currentChunkBytes);

                                    long downloaded = 0;
                                    for (long bytes : chunkProgressMap.values()) {
                                        downloaded += bytes;
                                    }

                                    long finalDownloaded = downloaded;
                                    int calcProgress = finalTotalFileSize > 0 ? (int) (finalDownloaded * 100 / finalTotalFileSize) : 0;
                                    final int progress = Math.min(calcProgress, 100);
                                    currentProgress = progress;
                                    mainHandler.post(() -> {
                                        updateNotification(finalFileName, progress, false, false);

                                        Bundle progressBundle = new Bundle();
                                        progressBundle.putInt("progress", progress);
                                        progressBundle.putLong("bytes_downloaded", finalDownloaded);
                                        
                                        if (receiver != null) {
                                            receiver.send(STATUS_PROGRESS, progressBundle);
                                        }
                                    });
                                }

                                @Override
                                public void onError(int chunkIndex, Exception e) {
                                    mainHandler.post(() -> {
                                        stopForeground(STOP_FOREGROUND_REMOVE);
                                        Bundle errorBundle = new Bundle();
                                        errorBundle.putString("error", e.getMessage());
                                        if (receiver != null) receiver.send(STATUS_ERROR, errorBundle);
                                        stopSelf();
                                    });
                                }

                                @Override
                                public void onComplete(int chunkIndex) {
                                    if (completedChunks.incrementAndGet() == totalChunksCount) {
                                        mainHandler.post(() -> {
                                            mergeChunkFiles(finalFileName , actualChunks);
                                            
                                            Bundle finishBundle = new Bundle();
                                            if (receiver != null) receiver.send(STATUS_FINISHED, finishBundle);
                                            stopForeground(STOP_FOREGROUND_REMOVE);
                                            stopSelf();
                                        });
                                    }
                                }
                        }
                    );

                    activeChunkDownloaders.add(downloader);
                    chunkExecutor.execute(downloader);
                }

                chunkExecutor.shutdown();

            } catch (Exception e) {
                Log.e(TAG, "Download failed", e);
                mainHandler.post(() -> {
                    updateNotification("Download failed", 0, false, true);
                    bundle.clear();
                    bundle.putString("error", e.toString());
                    if (receiver != null) receiver.send(STATUS_ERROR, bundle);
                });
                stopSelf();
            }
        });
    }

    private void mergeChunkFiles(String fileName, int numChunks) {
        File finalTempFile = new File(getFilesDir(), "temp_" + fileName + ".tmp");

        try (BufferedSink sink = Okio.buffer(Okio.sink(finalTempFile))) {
            for (int i = 0; i < numChunks; i++) {
                File partFile = new File(getFilesDir(), "temp_" + fileName + ".part" + i);
                if (partFile.exists()) {
                    try (BufferedSource source = Okio.buffer(Okio.source(partFile))) {
                        sink.writeAll(source);
                    }
                    partFile.delete();
                }
            }
            sink.flush();
            publishToPublicDownloads(finalTempFile, fileName);
        } catch (IOException e) {
            mainHandler.post(() -> {
                updateNotification(fileName, 0, false, true);
                bundle.clear();
                bundle.putString("error", e.toString());
                if (receiver != null) receiver.send(STATUS_ERROR, bundle);
            });
        }
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
            mainHandler.post(() -> {
                updateNotification(fileName, 100, false, true);
                bundle.clear();
                if (receiver != null) receiver.send(STATUS_FINISHED, bundle);
            });
        }
    }

    private void pauseDownload() {
        isPaused = true;
        for (ChunkDownloader downloader : activeChunkDownloaders) {
            downloader.cancel();
        }
        activeChunkDownloaders.clear();
        bundle.putBoolean("is_paused", true);
        if (receiver != null) receiver.send(STATUS_PAUSED, bundle);
    }

    private void resumeDownload() {
        isPaused = false;
        startDownload(savedUrl, savedRequestedChunks);
        if (receiver != null) receiver.send(STATUS_RESUME, bundle);
    }

    private void cancelDownload() {
        isPaused = false;
        if (activeCall != null && !activeCall.isCanceled()) {
            activeCall.cancel();
        }

        pauseDownload();

        if (tempFile != null && tempFile.exists()) {
            tempFile.delete();
        }

        for (File file : partFiles) {
            if (file != null && file.exists()) {
                file.delete();
            }
        }
        partFiles.clear();
        if (receiver != null) receiver.send(STATUS_CANCEL, bundle);
        stopService();
    }

    private void stopService() {
        isRunning = false;
        mainHandler.removeCallbacks(shutdownRunnable);
        if (executorService != null) {
            executorService.shutdownNow();
        }
        stopSelf();
    }

    public void onTimeout(int startId, int fgsType) {
        if (fgsType == ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) {
            Log.e(TAG, "Hard system 6-hour timeout hit!");
            stopService();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Data Sync Channel",
                NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildDownloadNotification(String fileName, int progress, boolean isPaused, boolean isComplete) {
        int notificationIcon;
        // these icons need to be changed they are inconsistant between devices 
        if (isComplete) {
            notificationIcon = R.drawable.ic_stat_file_download_done; 
        } else if (isPaused) {
            notificationIcon = R.drawable.ic_stat_pause; 
        } else {
            notificationIcon = R.drawable.ic_stat_file_download;
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(fileName != null && !fileName.isEmpty() ? fileName : "Downloading File")
                .setSmallIcon(notificationIcon)
                .setOnlyAlertOnce(true)
                .setOngoing(!isComplete);

        builder.clearActions();

        if (isComplete) {
            builder.setContentText("Download Complete")
                    .setProgress(0, 0, false);
        } else {
            builder.setContentText(isPaused ? "Paused • " + progress + "%" : "Downloading • " + progress + "%")
                    .setProgress(100, progress, false);

            if (isPaused) {
                Intent resumeIntent = new Intent(this, DataSyncService.class).setAction(ACTION_RESUME);

                PendingIntent resumePending = PendingIntent.getService(
                        this, 10, resumeIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                );
                builder.addAction(notificationIcon, "Resume", resumePending);
            } else {
                Intent pauseIntent = new Intent(this, DataSyncService.class).setAction(ACTION_PAUSE);

                PendingIntent pausePending = PendingIntent.getService(
                        this, 11, pauseIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                );
                builder.addAction(notificationIcon, "Pause", pausePending);
            }

            Intent cancelIntent = new Intent(this, DataSyncService.class).setAction(ACTION_CANCEL);
            PendingIntent cancelPending = PendingIntent.getService(
                    this, 12, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            builder.addAction(notificationIcon, "Cancel", cancelPending);
        }

        return builder.build();
    }


    private void updateNotification(String fileName, int progress, boolean isPaused, boolean isComplete) {
        long currentTime = System.currentTimeMillis();

        if (isComplete || isPaused || progress == 100 || (currentTime - lastNotificationUpdateTime) >= 400) {
            
            Notification notification = buildDownloadNotification(fileName, progress, isPaused, isComplete);
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, notification);
                lastNotificationUpdateTime = currentTime; 
            }
        }
    }



    @Override
    public void onDestroy() {
        stopService();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { 
        return null; 
    }
}