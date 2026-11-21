package com.scandesk.app;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class ScanStore {
    static final int PAGE_LIMIT = 100;
    private static final int MAX_SOURCE_BYTES = 48 * 1024 * 1024;
    private final File root;

    ScanStore(Context context) {
        root = new File(context.getNoBackupFilesDir(), "scans");
    }

    ScanDocument newDocument() {
        String date = new SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.ROOT).format(new Date());
        return new ScanDocument(newId(), "扫描件 " + date, System.currentTimeMillis(), 0,
                Collections.emptyList(), ScanDocument.Folder.NONE, ScanDocument.DATE_TITLE);
    }

    List<ScanDocument> list(List<String> unreadable) throws IOException {
        List<ScanDocument> documents = new ArrayList<>();
        if (!root.exists()) return documents;
        File[] folders = root.listFiles(File::isDirectory);
        if (folders == null) throw new IOException("无法读取本机扫描件");
        for (File directory : folders) {
            if (!validId(directory.getName())) continue;
            try {
                ScanDocument document = load(directory.getName());
                if (document != null) documents.add(document);
            } catch (IOException failure) {
                unreadable.add(directory.getName());
            }
        }
        documents.sort(Comparator.comparingLong((ScanDocument doc) -> doc.modifiedAt).reversed());
        return documents;
    }

    ScanDocument load(String id) throws IOException {
        if (!validId(id)) return null;
        AtomicFile metadata = atomic(id, "document.json");
        if (!metadata.getBaseFile().isFile()) return null;
        try {
            JSONObject json = read(metadata);
            JSONArray pages = json.getJSONArray("pages");
            List<ScanDocument.Page> result = new ArrayList<>();
            for (int i = 0; i < pages.length(); i++) {
                JSONObject entry = pages.getJSONObject(i);
                String pageId = entry.getString("id");
                if (!validId(pageId)) throw new JSONException("invalid page id");
                JSONArray coordinates = entry.getJSONArray("crop");
                float[] points = new float[8];
                for (int j = 0; j < 8; j++) points[j] = (float) coordinates.getDouble(j);
                CropQuad crop = CropQuad.from(points);
                if (crop == null) throw new JSONException("invalid crop");
                result.add(new ScanDocument.Page(pageId, entry.getInt("rotation") % 4, crop,
                        ScanDocument.Filter.valueOf(entry.getString("filter"))));
            }
            return new ScanDocument(id, json.getString("name"), json.getLong("modifiedAt"),
                    json.optLong("revision"), result,
                    ScanDocument.Folder.valueOf(json.optString("folder", ScanDocument.Folder.NONE.name())),
                    json.optString("titleSource", ""));
        } catch (JSONException | IllegalArgumentException failure) {
            throw new IOException("无法恢复扫描件", failure);
        }
    }

    void save(ScanDocument document) throws IOException {
        try {
            JSONObject json = new JSONObject();
            json.put("name", document.name);
            json.put("titleSource", document.titleSource);
            json.put("modifiedAt", document.modifiedAt);
            json.put("revision", document.revision);
            json.put("folder", document.folder.name());
            JSONArray pages = new JSONArray();
            for (ScanDocument.Page page : document.pages) {
                JSONObject entry = new JSONObject();
                entry.put("id", page.id);
                entry.put("rotation", page.rotation);
                entry.put("filter", page.filter.name());
                JSONArray coordinates = new JSONArray();
                for (float value : page.crop.values()) coordinates.put(value);
                entry.put("crop", coordinates);
                pages.put(entry);
            }
            json.put("pages", pages);
            write(atomic(document.id, "document.json"), json);
        } catch (JSONException failure) {
            throw new IOException("无法保存扫描件", failure);
        }
    }

    File image(ScanDocument document, ScanDocument.Page page) {
        return new File(directory(document.id), page.id + ".image");
    }

    void importImage(ContentResolver resolver, Uri uri, ScanDocument document, ScanDocument.Page page)
            throws IOException {
        ensureDirectory(directory(document.id));
        File output = image(document, page);
        try (InputStream source = resolver.openInputStream(uri);
                FileOutputStream destination = new FileOutputStream(output)) {
            if (source == null) throw new IOException("无法读取图片");
            byte[] buffer = new byte[32 * 1024];
            int total = 0;
            for (int size; (size = source.read(buffer)) != -1; ) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("已取消");
                total += size;
                if (total > MAX_SOURCE_BYTES) throw new IOException("单张图片超过 48 MB");
                destination.write(buffer, 0, size);
            }
            if (total == 0) throw new IOException("图片没有内容");
        } catch (IOException | RuntimeException failure) {
            output.delete();
            throw failure;
        }
    }

    void removeSource(ScanDocument document, ScanDocument.Page page) {
        image(document, page).delete();
    }

    void delete(ScanDocument document) throws IOException {
        File source = directory(document.id);
        File trash = new File(root, "removed-" + document.id);
        if (!source.renameTo(trash)) throw new IOException("无法移除扫描件");
        File[] children = trash.listFiles();
        if (children != null) {
            for (File file : children) file.delete();
        }
        trash.delete();
    }

    JSONObject loadReview(ScanDocument document, String review) throws IOException, JSONException {
        JSONObject json = readReview(document, review);
        return json != null && json.getLong("revision") == document.revision ? json : null;
    }

    JSONObject loadPreviousReview(ScanDocument document, String review) throws IOException, JSONException {
        JSONObject json = readReview(document, review);
        return json != null && json.getLong("revision") != document.revision ? json : null;
    }

    private JSONObject readReview(ScanDocument document, String review) throws IOException, JSONException {
        AtomicFile data = atomic(document.id, review + ".json");
        return data.getBaseFile().isFile() ? read(data) : null;
    }

    boolean reviewIsOutdated(ScanDocument document, String review) throws IOException, JSONException {
        return loadPreviousReview(document, review) != null;
    }

    void saveReview(ScanDocument document, String review, JSONObject json) throws IOException {
        try {
            json.put("revision", document.revision);
            write(atomic(document.id, review + ".json"), json);
        } catch (JSONException failure) {
            throw new IOException("无法保存校对内容", failure);
        }
    }

    private File directory(String id) {
        return new File(root, id);
    }

    private AtomicFile atomic(String id, String name) {
        return new AtomicFile(new File(directory(id), name));
    }

    private static JSONObject read(AtomicFile file) throws IOException, JSONException {
        return new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
    }

    private static void write(AtomicFile file, JSONObject json) throws IOException {
        ensureDirectory(file.getBaseFile().getParentFile());
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            stream.write(json.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(stream);
        } catch (IOException | RuntimeException failure) {
            if (stream != null) file.failWrite(stream);
            throw failure;
        }
    }

    static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("无法创建本机文件目录");
        }
    }

    static boolean validId(String id) {
        return id != null && id.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}");
    }

    static String newId() {
        return UUID.randomUUID().toString();
    }
}
