package com.scandesk.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

/** Exercises the installed application's OCR and export code with pre-annotated public photos. */
public final class FieldRunner extends Instrumentation {
    private static final String[] NAMES = {"menu", "bus", "forms"};
    private static final float[][] MANUAL_CROPS = {
            {.041f, .469f, .209f, .466f, .203f, .797f, .042f, .798f},
            {.576f, .155f, .963f, .110f, .952f, .897f, .577f, .827f},
            {.556f, .198f, .831f, .202f, .857f, .835f, .504f, .790f}
    };

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            measure();
            result.putString("result", "done");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("error", Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void measure() throws Exception {
        Context app = getTargetContext();
        File output = app.getExternalFilesDir("fieldstudy");
        if (output == null) throw new IllegalStateException("External test directory unavailable");
        Context isolated = new ContextWrapper(app) {
            @Override public File getNoBackupFilesDir() {
                return new File(output, "store");
            }
        };
        ScanStore store = new ScanStore(isolated);
        JSONObject run = new JSONObject();
        for (int i = 0; i < NAMES.length; i++) {
            String name = NAMES[i];
            File input = new File(output, name + ".jpg");
            ScanDocument doc = store.newDocument();
            ScanDocument.Page page = new ScanDocument.Page(ScanStore.newId(), 0, CropQuad.FULL,
                    ScanDocument.Filter.AUTO);
            store.save(doc);
            try {
                Files.copy(input.toPath(), store.image(doc, page).toPath());
                Bitmap thumbnail = ScanImages.source(input, 0, 1000);
                CropQuad detected;
                try {
                    detected = ScanImages.detect(thumbnail);
                } finally {
                    thumbnail.recycle();
                }
                CropQuad auto = detected == null ? CropQuad.FULL : detected;
                CropQuad manual = CropQuad.from(MANUAL_CROPS[i]);
                JSONObject sample = new JSONObject();
                sample.put("detected", detected != null);
                sample.put("autoQuad", points(auto));
                sample.put("manualQuad", points(manual));
                for (String mode : new String[]{"auto", "manual"}) {
                    ScanDocument.Page selection = page.crop(mode.equals("auto") ? auto : manual);
                    ScanDocument chosen = doc.withPages(Collections.singletonList(selection));
                    long start = SystemClock.elapsedRealtime();
                    ScanRecognition.WordDraft words = ScanRecognition.word(app.getAssets(),
                            store, chosen, (done, total) -> {});
                    try (OutputStream out = new FileOutputStream(new File(output, name + "-" + mode + ".docx"))) {
                        OfficeExport.word(words.pages, out);
                    }
                    sample.put(mode + "_ms", SystemClock.elapsedRealtime() - start);
                    if (mode.equals("manual")) {
                        try (OutputStream out = new FileOutputStream(new File(output, name + "-manual.pdf"))) {
                            ScanExport.pdf(store, chosen, false, out, (done, total) -> {});
                        }
                    }
                }
                run.put(name, sample);
                Files.write(new File(output, "run.json").toPath(), run.toString(2).getBytes(StandardCharsets.UTF_8));
            } finally {
                store.delete(doc);
            }
        }
    }

    private static JSONArray points(CropQuad quad) throws JSONException {
        JSONArray result = new JSONArray();
        for (float value : quad.values()) result.put((double) value);
        return result;
    }
}
