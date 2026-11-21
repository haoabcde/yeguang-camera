package com.scandesk.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Color;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class BookPageStoreTest {
    @Test public void appliesAndRestoresWithoutReplacingTheOriginalPhotoOrItsSettings() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File root = new File(context.getCacheDir(), ScanStore.newId());
        ScanStore store = new ScanStore(new ContextWrapper(context) {
            @Override public File getNoBackupFilesDir() { return root; }
        });
        CropQuad crop = CropQuad.from(new float[]{.1f,.08f,.91f,.1f,.89f,.95f,.08f,.92f});
        ScanDocument.Page original = new ScanDocument.Page(ScanStore.newId(), 1, crop, ScanDocument.Filter.GRAY);
        ScanDocument doc = store.newDocument().withPages(List.of(original));
        store.save(doc);
        File prepared = new File(root, "prepared.png");
        try {
            write(store.image(doc, original), Color.RED);
            byte[] source = Files.readAllBytes(store.image(doc, original).toPath());
            store.saveReview(doc, ScanRecognition.WORD, new ScanRecognition.WordDraft(List.of("原稿校对记录")).json());
            write(prepared, Color.BLUE);
            store.writeFlattened(doc, original, prepared, () -> {});
            ScanDocument flattened = doc.withPages(List.of(original.flattened()));
            store.save(flattened);
            ScanDocument loaded = store.load(doc.id);
            ScanDocument.Page page = loaded.pages.get(0);
            assertTrue(page.isFlattened());
            assertEquals(0, page.rotation);
            assertEquals(CropQuad.FULL, page.crop);
            assertEquals(original.filter, page.filter);
            assertArrayEquals(source, Files.readAllBytes(store.image(loaded, page.restoreFlattening()).toPath()));
            assertArrayEquals(Files.readAllBytes(prepared.toPath()), Files.readAllBytes(store.image(loaded, page).toPath()));
            assertTrue(store.reviewIsOutdated(loaded, ScanRecognition.WORD));
            assertNotNull(store.loadPreviousReview(loaded, ScanRecognition.WORD));

            ScanDocument.Page edited = page.rotate().crop(CropQuad.FULL).filter(ScanDocument.Filter.MONO);
            store.save(loaded.withPages(List.of(edited)));
            ScanDocument.Page reopened = store.load(doc.id).pages.get(0);
            assertTrue(reopened.isFlattened());
            ScanDocument.Page restored = reopened.restoreFlattening();
            assertEquals(original.rotation, restored.rotation);
            assertEquals(original.crop, restored.crop);
            assertEquals(original.filter, restored.filter);
            assertFalse(restored.isFlattened());
            ScanDocument finalDoc = loaded.withPages(List.of(restored));
            store.save(finalDoc);
            store.discardFlattened(finalDoc, reopened);
            assertFalse(store.image(finalDoc, reopened).exists());
            assertArrayEquals(source, Files.readAllBytes(store.image(finalDoc, restored).toPath()));
        } finally {
            store.delete(doc);
            prepared.delete();
        }
    }

    @Test public void cancelledDerivedWriteRestoresItsPreviousFileAndLeavesMetadataAlone() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File root = new File(context.getCacheDir(), ScanStore.newId());
        ScanStore store = new ScanStore(new ContextWrapper(context) {
            @Override public File getNoBackupFilesDir() { return root; }
        });
        ScanDocument.Page page = new ScanDocument.Page(ScanStore.newId(), 0, CropQuad.FULL, ScanDocument.Filter.AUTO);
        ScanDocument doc = store.newDocument().withPages(List.of(page));
        store.save(doc);
        File prepared = new File(root, "prepared.png");
        try {
            write(store.image(doc, page), Color.WHITE);
            byte[] original = Files.readAllBytes(store.image(doc, page).toPath());
            write(prepared, Color.BLUE);
            store.writeFlattened(doc, page, prepared, () -> {});
            byte[] accepted = Files.readAllBytes(store.image(doc, page.flattened()).toPath());
            write(prepared, Color.GREEN);
            AtomicInteger checks = new AtomicInteger();
            try {
                store.writeFlattened(doc, page, prepared, () -> {
                    if (checks.incrementAndGet() == 2) throw new CancellationException();
                });
                fail("Cancelled copy must abort");
            } catch (CancellationException expected) { }
            assertArrayEquals(accepted, Files.readAllBytes(store.image(doc, page.flattened()).toPath()));
            assertArrayEquals(original, Files.readAllBytes(store.image(doc, page).toPath()));
            assertFalse(store.load(doc.id).pages.get(0).isFlattened());
            store.removeSource(doc, page.flattened());
            assertFalse(store.image(doc, page).exists());
            assertFalse(store.image(doc, page.flattened()).exists());
        } finally {
            store.delete(doc);
            prepared.delete();
        }
    }

    private static void write(File target, int color) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        try (FileOutputStream stream = new FileOutputStream(target)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } finally { bitmap.recycle(); }
    }
}
