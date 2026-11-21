package com.scandesk.app;

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
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Optional local-only acceptance inputs. Neither private images nor transcriptions belong in the app. */
@RunWith(AndroidJUnit4.class)
public final class HandwrittenTableFieldTest {
    @Test public void exportsReviewableHandwrittenTables() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File directory = context.getExternalFilesDir("handtable");
        File manifest = new File(directory, "inputs.json");
        assumeTrue("Install local checksum manifest to enable field probe", manifest.isFile());
        JSONObject inputs = new JSONObject(new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8));
        ScanStore store = new ScanStore(new ContextWrapper(context) {
            @Override public File getNoBackupFilesDir() { return new File(directory, "table-scratch"); }
        });
        for (Iterator<String> keys = inputs.keys(); keys.hasNext();) {
            String name = keys.next();
            assertTrue(name.matches("[a-z0-9_-]{1,64}"));
            JSONObject specification = inputs.getJSONObject(name);
            String filename = specification.getString("file");
            assertTrue(filename.matches("[a-z0-9_-]+\\.(jpg|png)"));
            File input = new File(directory, filename);
            StringBuilder digest = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input.toPath()))) {
                digest.append(String.format(Locale.ROOT, "%02x", b & 255));
            }
            assertEquals(specification.getString("sha256"), digest.toString());
            ScanDocument document = store.newDocument();
            ScanDocument.Page page = new ScanDocument.Page(ScanStore.newId(), 0, CropQuad.FULL, ScanDocument.Filter.AUTO);
            try {
                store.importImage(context.getContentResolver(), Uri.fromFile(input), document, page);
                Bitmap thumbnail = ScanImages.source(store.image(document, page), 0, 1000);
                CropQuad crop;
                ScanDocument.Filter filter;
                try {
                    crop = ScanImages.detect(thumbnail);
                    if (crop == null) crop = CropQuad.FULL;
                    filter = ScanImages.initialHandwritingFilter(thumbnail, crop);
                } finally { thumbnail.recycle(); }
                document = document.withPages(List.of(page.crop(crop).filter(filter)));
                store.save(document);
                long start = SystemClock.elapsedRealtime();
                ScanRecognition.TableDraft draft = ScanRecognition.handwrittenTables(
                        context.getAssets(), store, document, (a, b) -> {});
                long elapsed = SystemClock.elapsedRealtime() - start;
                assertFalse(draft.tables.isEmpty());
                try (FileOutputStream out = new FileOutputStream(new File(directory, name + "-table.xlsx"))) {
                    OfficeExport.excel(draft.tables, out);
                }
                JSONObject result = new JSONObject().put("sha256", digest.toString()).put("elapsed_ms", elapsed)
                        .put("filter", filter.name()).put("crop", new org.json.JSONArray(crop.values()))
                        .put("draft", draft.json());
                Files.write(new File(directory, name + "-table.json").toPath(),
                        result.toString(2).getBytes(StandardCharsets.UTF_8));
            } finally { store.delete(document); }
        }
    }
}
