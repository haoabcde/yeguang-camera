package com.scandesk.app;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class ScanRecognition {
    static final String WORD = "word";
    static final String HANDWRITING = "handwriting";
    static final String EXCEL = "table";
    static final String HAND_TABLE = "hand-table";

    private ScanRecognition() {}

    static WordDraft handwriting(AssetManager assets, ScanStore store, ScanDocument document,
            ScanExport.Progress progress) throws IOException {
        WordDraft draft = new WordDraft(new ArrayList<>());
        try (HandwritingOcr reader = new HandwritingOcr(assets)) {
            int total = document.pages.size();
            for (int i = 0; i < total; i++) {
                checkInterrupted();
                ScanDocument.Page page = document.pages.get(i);
                Bitmap bitmap = ScanImages.renderWord(store.image(document, page), page);
                List<NumberLine> numbers = new ArrayList<>();
                try {
                    draft.pages.add(reader.page(bitmap, numbers));
                    draft.numberLines.add(numbers);
                } finally {
                    bitmap.recycle();
                }
                progress.page(i + 1, total);
            }
        }
        return draft;
    }

    static WordDraft word(AssetManager assets, ScanStore store, ScanDocument document,
            ScanExport.Progress progress) throws IOException {
        List<String> pages = new ArrayList<>();
        WordDraft draft = new WordDraft(pages);
        try (Reader reader = new Reader(assets)) {
            int total = document.pages.size();
            for (int i = 0; i < total; i++) {
                checkInterrupted();
                ScanDocument.Page page = document.pages.get(i);
                Bitmap image = ScanImages.renderWord(store.image(document, page), page);
                List<NumberLine> positions = new ArrayList<>();
                pages.add(reader.word(image, i + 1, positions));
                draft.numberLines.add(positions);
                progress.page(i + 1, total);
            }
        }
        return draft;
    }

    static TableDraft tables(AssetManager assets, ScanStore store, ScanDocument document,
            ScanExport.Progress progress) throws IOException {
        List<OfficeTable> tables = new ArrayList<>();
        List<Integer> skipped = new ArrayList<>();
        try (Reader reader = new Reader(assets)) {
            int total = document.pages.size();
            for (int i = 0; i < total; i++) {
                checkInterrupted();
                ScanDocument.Page page = document.pages.get(i);
                Bitmap image = ScanImages.renderTable(store.image(document, page), page);
                List<OfficeTable> recognized = reader.tables(image, i + 1);
                if (recognized.isEmpty()) skipped.add(i + 1);
                tables.addAll(recognized);
                progress.page(i + 1, total);
            }
        }
        return new TableDraft(tables, skipped);
    }

    static TableDraft handwrittenTables(AssetManager assets, ScanStore store, ScanDocument document,
            ScanExport.Progress progress) throws IOException {
        List<OfficeTable> tables = new ArrayList<>();
        List<Integer> skipped = new ArrayList<>();
        try (HandwritingOcr reader = new HandwritingOcr(assets)) {
            for (int i = 0; i < document.pages.size(); i++) {
                checkInterrupted();
                ScanDocument.Page page = document.pages.get(i);
                Bitmap bitmap = ScanImages.renderWord(store.image(document, page), page);
                try {
                    int completed = i;
                    List<HandwritingOcr.Line> lines = reader.tableLines(bitmap,
                            () -> progress.page(completed, document.pages.size()));
                    List<OfficeTable> recognized = TableVision.handwritten(bitmap, lines, i + 1);
                    if (recognized.isEmpty()) skipped.add(i + 1);
                    tables.addAll(recognized);
                } finally {
                    bitmap.recycle();
                }
                progress.page(i + 1, document.pages.size());
            }
        }
        return new TableDraft(tables, skipped);
    }

    private static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("已取消");
    }

    /** One operation's recognition resources. The reader takes ownership of each input bitmap. */
    static final class Reader implements AutoCloseable {
        private final ChineseOcr chinese;
        private TextRecognizer recognizer;

        Reader(AssetManager assets) {
            chinese = new ChineseOcr(assets);
        }

        String word(Bitmap bitmap, int page) throws IOException {
            return word(bitmap, page, null);
        }

        String word(Bitmap bitmap, int page, List<NumberLine> numbers) throws IOException {
            return read(bitmap, page, (image, text, ocr) -> {
                List<Text.TextBlock> blocks = new ArrayList<>(text.getTextBlocks());
                blocks.sort(Comparator.comparingInt((Text.TextBlock block) -> top(block.getBoundingBox()))
                        .thenComparingInt(block -> left(block.getBoundingBox())));
                List<WordGrid> grids = wordGrids(image, blocks);
                List<List<String>> paragraphs = new ArrayList<>();
                for (Text.TextBlock block : blocks) {
                    List<String> lines = new ArrayList<>();
                    for (Text.Line line : block.getLines()) {
                        Rect box = line.getBoundingBox();
                        String value = ocr.refine(line.getText(), box, null);
                        WordGrid table = null;
                        for (WordGrid grid : grids) {
                            if (grid.place(value, box)) {
                                table = grid;
                                break;
                            }
                        }
                        if (table == null) {
                            lines.add(value);
                            numberAnchor(numbers, value, box, image);
                        } else {
                            if (!lines.isEmpty()) {
                                paragraphs.add(lines);
                                lines = new ArrayList<>();
                            }
                            if (!table.inserted) {
                                paragraphs.add(table.output);
                                table.inserted = true;
                            }
                        }
                    }
                    if (block.getLines().isEmpty()) lines.add(block.getText());
                    if (!lines.isEmpty()) paragraphs.add(lines);
                }
                for (WordGrid grid : grids) grid.finish(numbers, image);
                List<String> content = new ArrayList<>();
                for (List<String> lines : paragraphs) content.add(String.join("\n", lines).trim());
                return String.join("\n\n", content);
            });
        }

        List<OfficeTable> tables(Bitmap bitmap, int page) throws IOException {
            return tables(bitmap, page, true);
        }

        // The explicit native-size path is used by the local regression harness for paired comparisons.
        List<OfficeTable> tables(Bitmap bitmap, int page, boolean enlargeSmall) throws IOException {
            Bitmap working = bitmap;
            try {
                checkInterrupted();
                Bitmap aligned = TableVision.straighten(working);
                if (aligned != working) {
                    working.recycle();
                    working = aligned;
                }
                checkInterrupted();
                Bitmap prepared = enlargeSmall ? tableInput(working) : working;
                if (prepared != working) {
                    working.recycle();
                    working = prepared;
                }
                checkInterrupted();
                Bitmap ready = working;
                working = null; // read owns this bitmap, including asynchronous cancellation cleanup.
                return read(ready, page, (image, text, ocr) -> TableVision.recognize(image, text, ocr, page));
            } finally {
                if (working != null) working.recycle();
            }
        }

        static Bitmap tableInput(Bitmap source) throws IOException {
            checkInterrupted();
            int edge = Math.max(source.getWidth(), source.getHeight());
            if (edge >= 1200) return source;
            // ML Kit recommends sufficient pixels per character. Interpolation can help its input
            // sampling; it cannot reconstruct detail absent from the captured image.
            // https://developers.google.com/ml-kit/vision/text-recognition/v2/android#input-image-guidelines
            float scale = Math.min(4f, 1600f / edge);
            return Bitmap.createScaledBitmap(source, Math.max(1, Math.round(source.getWidth() * scale)),
                    Math.max(1, Math.round(source.getHeight() * scale)), true);
        }

        private <T> T read(Bitmap bitmap, int page, PageReader<T> consumer) throws IOException {
            Task<Text> pending = null;
            try {
                checkInterrupted();
                if (recognizer == null) {
                    recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
                }
                pending = recognizer.process(InputImage.fromBitmap(bitmap, 0));
                Text result = Tasks.await(pending, 60, TimeUnit.SECONDS);
                return consumer.read(bitmap, result, chinese.on(bitmap));
            } catch (InterruptedException cause) {
                Thread.currentThread().interrupt();
                throw new IOException("已取消", cause);
            } catch (TimeoutException | ExecutionException cause) {
                throw new IOException("第 " + page + " 页识别失败，可以重试", cause);
            } finally {
                if (pending == null || pending.isComplete()) {
                    bitmap.recycle();
                } else {
                    pending.addOnCompleteListener(Runnable::run, ignored -> bitmap.recycle());
                }
            }
        }

        @Override public void close() {
            try {
                chinese.close();
            } finally {
                if (recognizer != null) recognizer.close();
            }
        }
    }

    private interface PageReader<T> {
        T read(Bitmap bitmap, Text text, ChineseOcr.Page ocr) throws IOException;
    }

    private static int top(Rect rect) { return rect == null ? Integer.MAX_VALUE : rect.top; }
    private static int left(Rect rect) { return rect == null ? Integer.MAX_VALUE : rect.left; }

    private static List<WordGrid> wordGrids(Bitmap image, List<Text.TextBlock> blocks) {
        List<Rect> lines = new ArrayList<>();
        for (Text.TextBlock block : blocks) {
            for (Text.Line line : block.getLines()) {
                Rect box = line.getBoundingBox();
                if (box != null && !box.isEmpty()) lines.add(box);
            }
        }
        if (!hasParallelLines(lines, image.getWidth())) return Collections.emptyList();
        float scale = Math.min(1f, 1280f / Math.max(image.getWidth(), image.getHeight()));
        Bitmap probe = Bitmap.createScaledBitmap(image, Math.max(1, Math.round(image.getWidth() * scale)),
                Math.max(1, Math.round(image.getHeight() * scale)), true);
        try {
            List<WordGrid> grids = new ArrayList<>();
            for (List<Rect> bands : TableVision.ruledRows(probe)) {
                float dx = image.getWidth() / (float) probe.getWidth(), dy = image.getHeight() / (float) probe.getHeight();
                for (Rect band : bands) band.set(Math.round(band.left * dx), Math.round(band.top * dy),
                        Math.round(band.right * dx), Math.round(band.bottom * dy));
                grids.add(new WordGrid(bands));
            }
            return grids;
        } catch (IOException | RuntimeException unavailable) {
            return Collections.emptyList();
        } finally {
            if (probe != image) probe.recycle();
        }
    }

    private static boolean hasParallelLines(List<Rect> lines, int width) {
        // Ordinary single-column paragraphs do not need a grid image pass. Require independent
        // fragments on at least two baselines before checking the reduced bitmap for ruling.
        lines.sort(Comparator.comparingInt(Rect::centerY));
        int baselines = 0, lastY = -1;
        for (int i = 0; i < lines.size(); i++) {
            Rect a = lines.get(i);
            if (lastY >= 0 && a.centerY() - lastY <= a.height() / 2) continue;
            for (int j = i + 1; j < lines.size(); j++) {
                Rect b = lines.get(j);
                if (b.centerY() - a.centerY() > a.height() / 2) break;
                if (Math.max(b.left - a.right, a.left - b.right) <= width / 50) continue;
                if (++baselines == 2) return true;
                lastY = a.centerY();
                break;
            }
        }
        return false;
    }

    private static void numberAnchor(List<NumberLine> numbers, String value, Rect box, Bitmap image) {
        if (numbers != null && box != null && !box.isEmpty() && NumberLine.hasDigit(value)) {
            numbers.add(new NumberLine(value, new RectF(box.left / (float) image.getWidth(),
                    box.top / (float) image.getHeight(), box.right / (float) image.getWidth(),
                    box.bottom / (float) image.getHeight())));
        }
    }

    private static final class WordGrid {
        private final List<Rect> bands;
        private final List<List<WordFragment>> rows = new ArrayList<>();
        final List<String> output = new ArrayList<>();
        boolean inserted;

        WordGrid(List<Rect> bands) {
            this.bands = bands;
            for (int i = 0; i < bands.size(); i++) rows.add(new ArrayList<>());
        }

        boolean place(String value, Rect box) {
            if (box == null || box.isEmpty()) return false;
            for (int i = 0; i < bands.size(); i++) {
                if (bands.get(i).contains(box.centerX(), box.centerY())) {
                    rows.get(i).add(new WordFragment(value, box));
                    return true;
                }
            }
            return false;
        }

        void finish(List<NumberLine> numbers, Bitmap image) {
            for (int i = 0; i < rows.size(); i++) {
                List<WordFragment> row = rows.get(i);
                if (row.isEmpty()) continue;
                row.sort(Comparator.comparingInt((WordFragment word) -> word.box.left)
                        .thenComparingInt(word -> word.box.top));
                List<String> cells = new ArrayList<>();
                Rect bounds = new Rect();
                for (WordFragment fragment : row) {
                    cells.add(fragment.text);
                    bounds.union(fragment.box);
                }
                String value = String.join("  ", cells).trim();
                output.add(value);
                bounds.intersect(bands.get(i));
                numberAnchor(numbers, value, bounds, image);
            }
        }
    }

    private static final class WordFragment {
        final String text;
        final Rect box;

        WordFragment(String text, Rect box) {
            this.text = text;
            this.box = box;
        }
    }

    static final class WordDraft {
        final List<String> pages;
        final List<List<NumberLine>> numberLines = new ArrayList<>();

        WordDraft(List<String> pages) {
            this.pages = pages;
        }

        JSONObject json() throws JSONException {
            JSONArray locations = new JSONArray();
            for (List<NumberLine> page : numberLines) {
                JSONArray values = new JSONArray();
                for (NumberLine line : page) values.put(line.json());
                locations.put(values);
            }
            return new JSONObject().put("pages", new JSONArray(pages)).put("numberLines", locations);
        }

        static WordDraft from(JSONObject json) throws JSONException {
            JSONArray values = json.getJSONArray("pages");
            List<String> pages = new ArrayList<>();
            for (int i = 0; i < values.length(); i++) pages.add(values.getString(i));
            WordDraft draft = new WordDraft(pages);
            JSONArray positions = json.optJSONArray("numberLines");
            for (int i = 0; positions != null && i < Math.min(positions.length(), pages.size()); i++) {
                List<NumberLine> page = new ArrayList<>();
                JSONArray entries = positions.optJSONArray(i);
                for (int j = 0; entries != null && j < entries.length(); j++) {
                    JSONArray e = entries.optJSONArray(j);
                    if (e == null || e.length() < 5) continue;
                    RectF rect = new RectF((float)e.optDouble(1), (float)e.optDouble(2),
                            (float)e.optDouble(3), (float)e.optDouble(4));
                    if (rect.intersect(0, 0, 1, 1)) {
                        NumberLine line = new NumberLine(e.optString(0), rect);
                        line.reviewedText = e.optString(5, line.text);
                        page.add(line);
                    }
                }
                draft.numberLines.add(page);
            }
            return draft;
        }
    }

    static final class NumberLine {
        final String text;
        final RectF bounds;
        String reviewedText;
        NumberLine(String text, RectF bounds) {
            this.text = text;
            this.reviewedText = text;
            this.bounds = bounds;
        }
        JSONArray json() throws JSONException {
            JSONArray row = new JSONArray().put(text).put(bounds.left).put(bounds.top).put(bounds.right).put(bounds.bottom);
            if (!text.equals(reviewedText)) row.put(reviewedText);
            return row;
        }
        static boolean hasDigit(String line) { return line.codePoints().anyMatch(Character::isDigit); }
    }

    static final class TableDraft {
        final List<OfficeTable> tables;
        final List<Integer> skipped;

        TableDraft(List<OfficeTable> tables, List<Integer> skipped) {
            this.tables = tables;
            this.skipped = skipped;
        }

        JSONObject json() throws JSONException {
            JSONArray values = new JSONArray();
            for (OfficeTable table : tables) values.put(table.json());
            return new JSONObject().put("tables", values).put("skipped", new JSONArray(skipped));
        }

        static TableDraft from(JSONObject json) throws JSONException {
            List<OfficeTable> tables = new ArrayList<>();
            JSONArray rows = json.getJSONArray("tables");
            for (int i = 0; i < rows.length(); i++) tables.add(OfficeTable.from(rows.getJSONObject(i)));
            List<Integer> skipped = new ArrayList<>();
            JSONArray missing = json.getJSONArray("skipped");
            for (int i = 0; i < missing.length(); i++) skipped.add(missing.getInt(i));
            return new TableDraft(tables, skipped);
        }
    }
}
