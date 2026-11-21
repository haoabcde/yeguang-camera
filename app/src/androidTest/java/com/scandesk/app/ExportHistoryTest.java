package com.scandesk.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class ExportHistoryTest {
    private static final Uri TABLE = Uri.parse("content://com.example.documents/document/表格%3A结果");
    private static final Uri PDF = Uri.parse("content://com.example.documents/document/13");

    private File directory;
    private Context context;

    @Before public void isolateHistory() throws IOException {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        directory = Files.createTempDirectory(target.getCacheDir().toPath(), "export-history-test").toFile();
        context = new ContextWrapper(target) {
            @Override public File getFilesDir() {
                return directory;
            }
        };
    }

    @After public void clearHistory() {
        if (directory == null) return;
        File[] records = directory.listFiles();
        if (records != null) for (File record : records) record.delete();
        directory.delete();
    }

    @Test public void firstOpenIsEmptyAndReopenKeepsSavedDestinationAndMetadata() throws Exception {
        ExportHistory writer = new ExportHistory(context);
        assertTrue(writer.list().isEmpty());
        writer.record("source", "费用.xlsx", "XLSX", TABLE, 4302);

        List<ExportHistory.Entry> entries = new ExportHistory(context).list();
        assertEquals(1, entries.size());
        ExportHistory.Entry entry = entries.get(0);
        assertFalse(entry.id.isEmpty());
        assertEquals("source", entry.documentId);
        assertEquals("费用.xlsx", entry.name);
        assertEquals("XLSX", entry.format);
        assertEquals(TABLE, entry.uri);
        assertEquals(4302L, entry.bytes);
        assertTrue(entry.createdAt > 0);
    }

    @Test public void separateInstancesKeepNewestFirstAndRemovingOnePreservesTheOther() throws Exception {
        ExportHistory first = new ExportHistory(context);
        ExportHistory second = new ExportHistory(context);
        first.record("source", "费用.xlsx", "XLSX", TABLE, 4302);
        second.record(null, "合并.pdf", "PDF", PDF, 5800);

        List<ExportHistory.Entry> entries = first.list();
        assertEquals(2, entries.size());
        assertEquals(PDF, entries.get(0).uri);
        assertNull(entries.get(0).documentId);
        assertEquals(TABLE, entries.get(1).uri);
        assertNotEquals(entries.get(0).id, entries.get(1).id);

        first.remove(entries.get(1).id);
        second.remove("already-absent");
        List<ExportHistory.Entry> remaining = new ExportHistory(context).list();
        assertEquals(1, remaining.size());
        assertEquals(entries.get(0).id, remaining.get(0).id);
        assertEquals(PDF, remaining.get(0).uri);
        assertEquals(5800L, remaining.get(0).bytes);
    }

    @Test public void refusingPrivatePathsDoesNotEraseSavedEntries() throws Exception {
        ExportHistory history = new ExportHistory(context);
        history.record("source", "费用.xlsx", "XLSX", TABLE, 4302);
        for (Uri privateUri : List.of(Uri.fromFile(new File(directory, "scratch.pdf")),
                Uri.parse("content://" + context.getPackageName() + ".files/exports/scratch.pdf"))) {
            try {
                history.record(null, "scratch.pdf", "PDF", privateUri, 12);
                fail("Private storage must not be recorded");
            } catch (IOException expected) {
                assertEquals(1, history.list().size());
                assertEquals(TABLE, history.list().get(0).uri);
            }
        }
    }
}
