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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

final class ExportHistory {
    private static final Object LOCK = new Object();

    private final AtomicFile history;
    private final String privateAuthority;

    ExportHistory(Context context) {
        history = new AtomicFile(new File(context.getFilesDir(), "export-history.json"));
        privateAuthority = context.getPackageName() + ".files";
    }

    void record(String documentId, String displayName, String format, Uri destination, long bytes)
            throws IOException {
        if (destination == null || !ContentResolver.SCHEME_CONTENT.equals(destination.getScheme())
                || destination.getAuthority() == null || privateAuthority.equals(destination.getAuthority())) {
            throw new IOException("导出记录需要外部保存位置");
        }
        if (displayName == null || format == null) throw new IOException("导出记录缺少文件名或格式");
        synchronized (LOCK) {
            List<Entry> entries = read();
            entries.add(0, new Entry(UUID.randomUUID().toString(), documentId, displayName, format,
                    destination, bytes, System.currentTimeMillis()));
            write(entries);
        }
    }

    List<Entry> list() throws IOException {
        synchronized (LOCK) {
            return read();
        }
    }

    void remove(String id) throws IOException {
        synchronized (LOCK) {
            List<Entry> entries = read();
            if (entries.removeIf(entry -> entry.id.equals(id))) write(entries);
        }
    }

    private List<Entry> read() throws IOException {
        List<Entry> entries = new ArrayList<>();
        byte[] contents;
        try {
            contents = history.readFully();
        } catch (FileNotFoundException missing) {
            if (history.getBaseFile().exists()) throw missing;
            return entries;
        }

        try {
            JSONArray array = new JSONArray(new String(contents, StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                entries.add(new Entry(item.getString("id"),
                        item.isNull("documentId") ? null : item.getString("documentId"),
                        item.getString("name"), item.getString("format"),
                        Uri.parse(item.getString("uri")), item.getLong("bytes"), item.getLong("createdAt")));
            }
            return entries;
        } catch (JSONException invalid) {
            throw new IOException("无法读取导出记录", invalid);
        }
    }

    private void write(List<Entry> entries) throws IOException {
        JSONArray array = new JSONArray();
        try {
            for (Entry entry : entries) {
                array.put(new JSONObject().put("id", entry.id).put("documentId", entry.documentId)
                        .put("name", entry.name).put("format", entry.format).put("uri", entry.uri.toString())
                        .put("bytes", entry.bytes).put("createdAt", entry.createdAt));
            }
        } catch (JSONException invalid) {
            throw new IOException("无法保存导出记录", invalid);
        }

        byte[] data = array.toString().getBytes(StandardCharsets.UTF_8);
        FileOutputStream stream = history.startWrite();
        try {
            stream.write(data);
            stream.getFD().sync();
        } catch (IOException failure) {
            history.failWrite(stream);
            throw failure;
        }
        history.finishWrite(stream);
        // AtomicFile logs a failed rename instead of reporting it to the caller.
        if (!Arrays.equals(data, history.readFully())) throw new IOException("无法提交导出记录");
    }

    static final class Entry {
        final String id;
        final String documentId;
        final String name;
        final String format;
        final Uri uri;
        final long bytes;
        final long createdAt;

        Entry(String id, String documentId, String name, String format, Uri uri, long bytes, long createdAt) {
            this.id = id;
            this.documentId = documentId;
            this.name = name;
            this.format = format;
            this.uri = uri;
            this.bytes = bytes;
            this.createdAt = createdAt;
        }
    }
}
