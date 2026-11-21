package com.scandesk.app;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class PdfWorkspace {
    static final int PAGE_LIMIT = 200;
    private static final long FILE_LIMIT = 96L * 1024 * 1024;
    private static final long WORKSPACE_LIMIT = 256L * 1024 * 1024;
    private final File root;
    private final AtomicFile index;

    PdfWorkspace(Context context) {
        this(new File(context.getNoBackupFilesDir(), "pdf-tools"));
    }

    PdfWorkspace(File root) {
        this.root = root;
        index = new AtomicFile(new File(root, "pages.json"));
    }

    File source(String id) {
        if (!ScanStore.validId(id)) throw new IllegalArgumentException("Invalid PDF source");
        return new File(root, id + ".pdf");
    }

    List<Page> load() throws IOException {
        try {
            JSONArray rows = new JSONArray(new String(index.readFully(), StandardCharsets.UTF_8));
            if (rows.length() > PAGE_LIMIT) throw new JSONException("Too many pages");
            List<Page> pages = new ArrayList<>(rows.length());
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                String id = row.getString("source");
                int number = row.getInt("index");
                if (number < 0 || number >= PAGE_LIMIT || !source(id).isFile()) {
                    throw new JSONException("Missing PDF page");
                }
                pages.add(new Page(id, row.getString("name"), number, row.getBoolean("selected"),
                        Math.floorMod(row.optInt("turns"), 4)));
            }
            return pages;
        } catch (FileNotFoundException absent) {
            return new ArrayList<>();
        } catch (JSONException | IllegalArgumentException broken) {
            throw new IOException("无法恢复 PDF 工作区", broken);
        }
    }

    void save(List<Page> pages) throws IOException {
        ScanStore.ensureDirectory(root);
        FileOutputStream output = null;
        try {
            JSONArray rows = new JSONArray();
            for (Page page : pages) {
                rows.put(new JSONObject().put("source", page.source).put("name", page.name)
                        .put("index", page.index).put("selected", page.selected).put("turns", page.turns));
            }
            output = index.startWrite();
            output.write(rows.toString().getBytes(StandardCharsets.UTF_8));
            index.finishWrite(output);
        } catch (IOException | JSONException | RuntimeException failure) {
            if (output != null) index.failWrite(output);
            throw new IOException("无法保存 PDF 工作区", failure);
        }
    }

    List<Page> add(ContentResolver resolver, List<Page> current, List<Uri> files, boolean selectOnlyNew,
            Runnable check, ScanExport.Progress progress) throws IOException {
        if (files.isEmpty()) return current;
        ScanStore.ensureDirectory(root);
        List<Page> combined = new ArrayList<>(current);
        if (selectOnlyNew) combined.replaceAll(page -> page.select(false));
        List<File> staged = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        long total = 0L;
        for (Page page : current) {
            if (ids.add(page.source)) total += source(page.source).length();
        }
        boolean committed = false;
        try {
            for (int i = 0; i < files.size(); i++) {
                check.run();
                Uri uri = files.get(i);
                String id = ScanStore.newId();
                File local = source(id);
                staged.add(local);
                long ceiling = Math.min(FILE_LIMIT, WORKSPACE_LIMIT - total);
                total += copy(resolver, uri, local, ceiling, check);
                int pages = PdfTools.pageCount(local, i + 1);
                if (pages > PAGE_LIMIT - combined.size()) {
                    throw new IOException("工作台最多 200 页，请减少导入页数");
                }
                String name = ScanExport.displayName(resolver, uri, "PDF 文档");
                for (int page = 0; page < pages; page++) {
                    combined.add(new Page(id, name, page, true, 0));
                }
                progress.page(i + 1, files.size());
            }
            check.run();
            save(combined);
            committed = true;
            return combined;
        } finally {
            if (!committed) {
                for (File local : staged) local.delete();
            }
        }
    }

    void removeUnused(List<Page> pages) {
        Set<String> used = new HashSet<>();
        for (Page page : pages) used.add(page.source + ".pdf");
        File[] files = root.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".pdf") && !used.contains(name)
                    && ScanStore.validId(name.substring(0, name.length() - 4))) file.delete();
        }
    }

    private static long copy(ContentResolver resolver, Uri uri, File local, long limit, Runnable check)
            throws IOException {
        try (InputStream input = resolver.openInputStream(uri);
                FileOutputStream output = new FileOutputStream(local)) {
            if (input == null) throw new IOException("无法读取所选 PDF");
            byte[] buffer = new byte[64 * 1024];
            long size = 0;
            for (int count; (count = input.read(buffer)) != -1;) {
                check.run();
                size += count;
                if (size > limit) {
                    throw new IOException(limit == FILE_LIMIT ? "单份 PDF 最多 96 MB" : "工作台最多 256 MB");
                }
                output.write(buffer, 0, count);
            }
            return size;
        }
    }

    static final class Page {
        final String source;
        final String name;
        final int index;
        final boolean selected;
        final int turns;

        Page(String source, String name, int index, boolean selected, int turns) {
            this.source = source;
            this.name = name;
            this.index = index;
            this.selected = selected;
            this.turns = turns;
        }

        Page select(boolean value) { return value == selected ? this : new Page(source, name, index, value, turns); }
        Page rotate() { return new Page(source, name, index, selected, (turns + 1) % 4); }
    }
}
