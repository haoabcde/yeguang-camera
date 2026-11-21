package com.scandesk.app;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Pattern;

final class DocumentSearch {
    private static final String INDEX = "search-v1";
    private static final Pattern HAN_SPACE = Pattern.compile("(?<=\\p{IsHan})\\s+(?=\\p{IsHan})");
    private static final Pattern SPACES = Pattern.compile("[\\s\\p{Z}]+");

    static final class Hit {
        final String documentId, name, pageId, context;
        final String reviewKey;
        final int tableIndex, row, column;

        Hit(ScanDocument doc, ScanDocument.Page page, String context) {
            this(doc, page, context, null, -1, -1, -1);
        }

        Hit(ScanDocument doc, ScanDocument.Page page, String context, String reviewKey,
                int tableIndex, int row, int column) {
            this.documentId = doc.id;
            this.name = doc.name;
            this.pageId = page == null ? null : page.id;
            this.context = context;
            this.reviewKey = reviewKey;
            this.tableIndex = tableIndex;
            this.row = row;
            this.column = column;
        }
    }

    static final class Results {
        final List<Hit> hits = new ArrayList<>();
        int missingPages;
        int unreadableDocuments;
    }

    private DocumentSearch() {}

    // Only explicit indexing runs OCR. Metadata and corrected drafts are searchable immediately.
    static Results find(ScanStore store, AssetManager assets, String query, boolean indexMissing,
            ScanExport.Progress progress) throws IOException {
        Results result = new Results();
        String needle = searchable(query).toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return result;
        List<String> unreadable = new ArrayList<>();
        List<ScanDocument> documents = store.list(unreadable);
        result.unreadableDocuments = unreadable.size();
        int total = 0, done = 0;
        for (ScanDocument doc : documents) total += doc.pages.size();
        try (ScanRecognition.Reader reader = indexMissing ? new ScanRecognition.Reader(assets) : null) {
            for (ScanDocument doc : documents) {
                boolean nameMatch = contains(searchable(doc.name), needle);
                boolean pageMatch = false;
                JSONObject draft = review(store, doc, ScanRecognition.WORD);
                JSONArray edited = draft.optJSONArray("pages");
                JSONArray handwritten = review(store, doc, ScanRecognition.HANDWRITING).optJSONArray("pages");
                Map<Integer, Hit> tableMatches = tableMatches(store, doc, needle);
                JSONObject index = review(store, doc, INDEX);
                boolean cacheWritable = true;
                for (int i = 0; i < doc.pages.size(); i++) {
                    progress.page(done++, total);
                    ScanDocument.Page page = doc.pages.get(i);
                    String handPage = handwritten == null ? null : handwritten.optString(i, null);
                    String text = edited == null ? null : edited.optString(i, null);
                    if (text == null && handPage == null) text = index.optString(page.id, null);
                    if (text == null && handPage == null && reader != null) {
                        try {
                            Bitmap bitmap = ScanImages.renderWord(store.image(doc, page), page);
                            text = reader.word(bitmap, i + 1);
                        } catch (IOException | RuntimeException failure) {
                            if (Thread.currentThread().isInterrupted()) throw new IOException("已取消", failure);
                        }
                        if (text != null && cacheWritable) {
                            try {
                                index.put(page.id, text);
                                store.saveReview(doc, INDEX, index);
                            } catch (IOException | JSONException failedWrite) {
                                cacheWritable = false;
                            }
                        }
                    }
                    if (text == null && handPage == null) {
                        result.missingPages++;
                    }
                    Hit tableHit = tableMatches.get(i + 1);
                    if (tableHit != null) {
                        result.hits.add(tableHit);
                        pageMatch = true;
                        continue;
                    }
                    String key = ScanRecognition.HANDWRITING;
                    String snippet = excerpt(handPage, needle);
                    if (snippet == null) {
                        snippet = excerpt(text, needle);
                        key = edited != null && edited.optString(i, null) != null ? ScanRecognition.WORD : null;
                    }
                    if (snippet != null) {
                        String type = key == null ? "索引正文" : key.equals(ScanRecognition.WORD) ? "文字稿" : "手写稿";
                        result.hits.add(new Hit(doc, page, "第 " + (i + 1) + " 页 · " + type + " · " + snippet,
                                key, -1, -1, -1));
                        pageMatch = true;
                    }
                }
                if (nameMatch && !pageMatch) result.hits.add(new Hit(doc, null, "文件名匹配"));
            }
        }
        progress.page(done, total);
        return result;
    }

    // A page can contain several edited tables. Search cells individually so adjacent values
    // cannot create a spurious identifier, and keep one result per source page.
    private static Map<Integer, Hit> tableMatches(ScanStore store, ScanDocument doc, String needle) {
        Map<Integer, Hit> pages = new HashMap<>();
        for (String key : new String[]{ScanRecognition.HAND_TABLE, ScanRecognition.EXCEL}) {
            JSONObject json = review(store, doc, key);
            if (!json.has("tables")) continue;
            try {
                List<OfficeTable> tables = ScanRecognition.TableDraft.from(json).tables;
                for (int i = 0; i < tables.size(); i++) {
                    OfficeTable table = tables.get(i);
                    if (table.page < 1 || table.page > doc.pages.size() || pages.containsKey(table.page)) continue;
                    for (OfficeTable.Cell cell : table.cells) {
                        String snippet = excerpt(cell.text, needle);
                        if (snippet != null) {
                            String type = ScanRecognition.HAND_TABLE.equals(key) ? "手写表格" : "印刷表格";
                            String context = "第 " + table.page + " 页 · " + type + " · " + snippet;
                            pages.put(table.page, new Hit(doc, doc.pages.get(table.page - 1), context,
                                    key, i, cell.row, cell.column));
                            break;
                        }
                    }
                }
            } catch (JSONException | IllegalArgumentException ignored) {
                // Other valid drafts and the text index are still available.
            }
        }
        return pages;
    }

    private static String excerpt(String text, String needle) {
        if (text == null) return null;
        String line = searchable(text);
        int position = line.toLowerCase(Locale.ROOT).indexOf(needle);
        if (position < 0) return null;
        int start = Math.max(0, Math.min(line.length(), position) - 12);
        int end = Math.min(line.length(), position + needle.length() + 24);
        return (start > 0 ? "…" : "") + line.substring(start, end) + (end < line.length() ? "…" : "");
    }

    private static JSONObject review(ScanStore store, ScanDocument doc, String name) {
        try {
            JSONObject saved = store.loadReview(doc, name);
            if (saved != null) return saved;
        } catch (IOException | JSONException ignored) {
            // A damaged cache can be rebuilt. The image and any existing user draft stay intact.
        }
        return new JSONObject();
    }

    private static String searchable(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        return HAN_SPACE.matcher(SPACES.matcher(normalized).replaceAll(" ").trim()).replaceAll("");
    }

    private static boolean contains(String text, String needle) {
        return text.toLowerCase(Locale.ROOT).contains(needle);
    }
}
