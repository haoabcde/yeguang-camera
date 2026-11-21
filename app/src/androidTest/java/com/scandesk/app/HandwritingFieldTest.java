package com.scandesk.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.SystemClock;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** Optional private-store probe. The image manifest contains checksums only; scoring reads the resulting DOCX. */
@RunWith(AndroidJUnit4.class)
public final class HandwritingFieldTest {
    @Test public void exportsBothOcrModesForExternalHandwriting() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File input = context.getExternalFilesDir("handwriting");
        assertNotNull(input);
        File manifest = new File(input, "inputs.json");
        assumeTrue("Run tools/qa/handwriting/prepare.py and install its samples to enable this probe",
                manifest.isFile());
        JSONObject sources = new JSONObject(new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8));
        assertTrue(sources.length() > 0);
        ScanStore store = new ScanStore(new ContextWrapper(context) {
            @Override public File getNoBackupFilesDir() {
                return new File(input, "scratch");
            }
        });
        JSONObject run = new JSONObject();
        for (Iterator<String> names = sources.keys(); names.hasNext();) {
            String name = names.next();
            assertTrue(name.matches("[a-z0-9_-]{1,64}"));
            File png = new File(input, name + ".png");
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(png.toPath()));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
            assertEquals(name, sources.getString(name), hex.toString());
            ScanDocument.Page page = new ScanDocument.Page(ScanStore.newId(), 0, CropQuad.FULL, ScanDocument.Filter.AUTO);
            ScanDocument doc = store.newDocument().withPages(List.of(page));
            store.save(doc);
            try {
                store.importImage(context.getContentResolver(), Uri.fromFile(png), doc, page);
                JSONObject metrics = new JSONObject().put("input_sha256", hex.toString());
                Bitmap thumbnail = ScanImages.source(store.image(doc, page), 0, 1000);
                ScanDocument.Filter selected;
                try {
                    selected = ScanImages.initialHandwritingFilter(thumbnail, page.crop);
                } finally {
                    thumbnail.recycle();
                }
                for (ScanDocument.Filter filter : List.of(ScanDocument.Filter.AUTO, ScanDocument.Filter.ORIGINAL)) {
                    ScanDocument sameInput = doc.withPages(List.of(page.filter(filter)));
                    for (boolean handwriting : new boolean[] {false, true}) {
                        String mode = handwriting ? "hand-auto" : "print";
                        if (filter == ScanDocument.Filter.ORIGINAL) mode = handwriting ? "hand-original" : "print-original";
                        long start = SystemClock.elapsedRealtime();
                        ScanRecognition.WordDraft text = handwriting
                                ? ScanRecognition.handwriting(context.getAssets(), store, sameInput, (a, b) -> {})
                                : ScanRecognition.word(context.getAssets(), store, sameInput, (a, b) -> {});
                        assertEquals(1, text.pages.size());
                        try (FileOutputStream out = new FileOutputStream(new File(input, name + "-" + mode + ".docx"))) {
                            OfficeExport.word(text.pages, out);
                        }
                        metrics.put(mode + "_elapsed_ms", SystemClock.elapsedRealtime() - start);
                        metrics.put(mode + "_filter", filter.name());
                    }
                }
                String selectedMode = selected == ScanDocument.Filter.AUTO ? "hand-auto" : "hand-original";
                assertEquals(metrics.getString(selectedMode + "_filter"), selected.name());
                Files.copy(new File(input, name + "-" + selectedMode + ".docx").toPath(),
                        new File(input, name + "-hand.docx").toPath(), StandardCopyOption.REPLACE_EXISTING);
                metrics.put("hand_filter", selected.name());
                metrics.put("hand_result_from", selectedMode);
                metrics.put("hand_elapsed_ms", metrics.getLong(selectedMode + "_elapsed_ms"));
                run.put(name, metrics);
            } finally {
                store.delete(doc);
            }
        }
        Files.write(new File(input, "run.json").toPath(), run.toString(2).getBytes(StandardCharsets.UTF_8));
    }
}
