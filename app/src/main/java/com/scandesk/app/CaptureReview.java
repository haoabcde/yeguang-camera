package com.scandesk.app;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns the single undecided shot while the camera activity is recreated or in the background. */
public final class CaptureReview extends ViewModel {
    enum Stage { IDLE, WRITING, CHECKING, READY, FAILED }

    final MutableLiveData<Stage> stage = new MutableLiveData<>(Stage.IDLE);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    File file;
    Bitmap preview;
    CaptureQuality.Issue issue = CaptureQuality.Issue.NONE;

    void begin(File output) {
        file = output;
        stage.setValue(Stage.WRITING);
    }

    void inspect(File output) {
        if (!output.equals(file)) {
            output.delete();
            return;
        }
        stage.setValue(Stage.CHECKING);
        worker.execute(() -> {
            try {
                Bitmap sample = ScanImages.source(output, 0, 960);
                CaptureQuality.Issue result = CaptureQuality.inspect(sample);
                main.post(() -> {
                    if (!output.equals(file)) {
                        sample.recycle();
                        return;
                    }
                    issue = result;
                    if (result == CaptureQuality.Issue.NONE) sample.recycle();
                    else preview = sample;
                    stage.setValue(Stage.READY);
                });
            } catch (IOException | RuntimeException invalid) {
                main.post(() -> fail(output));
            }
        });
    }

    void fail(File output) {
        output.delete();
        if (!output.equals(file)) return;
        file = null;
        stage.setValue(Stage.FAILED);
    }

    void clear(boolean discard) {
        if (discard && file != null) file.delete();
        file = null;
        preview = null;
        issue = CaptureQuality.Issue.NONE;
        stage.setValue(Stage.IDLE);
    }

    @Override protected void onCleared() {
        clear(true);
        worker.shutdownNow();
    }
}
