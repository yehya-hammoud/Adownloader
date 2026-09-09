package com.adownloader;

import java.io.File;
import java.io.IOException;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;

public class ChunkDownloader implements Runnable {

    public interface ChunkProgressListener {
        void onProgressUpdate(int chunkIndex, long bytesRead, long totalChunkBytes);
        void onError(int chunkIndex, Exception e);
        void onComplete(int chunkIndex);
    }

    private final int chunkIndex;
    private final String url;
    private final File partFile;
    private final long startByte;
    private final long endByte;
    private final OkHttpClient client;
    private final ChunkProgressListener listener;
    
    private Call activeCall;
    private volatile boolean isStopped = false;

    public ChunkDownloader(int chunkIndex, String url, File partFile, 
                            long startByte, long endByte, 
                            OkHttpClient client, ChunkProgressListener listener) {
        this.chunkIndex = chunkIndex;
        this.url = url;
        this.partFile = partFile;
        this.startByte = startByte;
        this.endByte = endByte;
        this.client = client;
        this.listener = listener;
    }

    public File getFile() {
        return partFile;
    }

    public void cancel() {
        isStopped = true;
        if (activeCall != null) {
            activeCall.cancel(); 
        }
    }

    public void stop() {
        cancel();
    }

    @Override
    public void run() {
        long existingBytes = partFile.exists() ? partFile.length() : 0;
        long currentStartByte = startByte + existingBytes;
        long totalChunkSize = (endByte - startByte) + 1;

        if (existingBytes >= totalChunkSize) {
            if (listener != null) {
                listener.onProgressUpdate(chunkIndex, totalChunkSize, totalChunkSize);
                listener.onComplete(chunkIndex);
            }
            return;
        }

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Range", "bytes=" + currentStartByte + "-" + endByte)
                .build();

        activeCall = client.newCall(request);

        try (Response response = activeCall.execute()) {
            if (!response.isSuccessful() && response.code() != 206) {
                if (listener != null) {
                    listener.onError(chunkIndex, new IOException("HTTP Error " + response.code()));
                }
                return;
            }

            try (BufferedSource source = response.body().source();
                BufferedSink sink = Okio.buffer(existingBytes > 0 ? Okio.appendingSink(partFile) : Okio.sink(partFile))) {

                long totalBytesRead = existingBytes;
                long readBytes;
                okio.Buffer buffer = sink.buffer();
                long segmentSize = 8192;

                while (!isStopped && (readBytes = source.read(buffer, segmentSize)) != -1) {
                    totalBytesRead += readBytes;
                    sink.emitCompleteSegments();

                    if (listener != null) {
                        listener.onProgressUpdate(chunkIndex, totalBytesRead, totalChunkSize);
                    }
                }

                sink.flush();

                if (!isStopped && listener != null) {
                    listener.onComplete(chunkIndex);
                }
            }
        } catch (IOException e) {
            if (!isStopped && listener != null) {
                listener.onError(chunkIndex, e);
            }
        }
    }
}