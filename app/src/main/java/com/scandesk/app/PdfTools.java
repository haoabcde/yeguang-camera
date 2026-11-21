package com.scandesk.app;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.contentstream.operator.Operator;
import com.tom_roush.pdfbox.cos.COSBase;
import com.tom_roush.pdfbox.cos.COSDictionary;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.cos.COSStream;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.multipdf.PDFCloneUtility;
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.PDResources;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;
import com.tom_roush.pdfbox.pdmodel.graphics.PDXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;
import com.tom_roush.pdfbox.rendering.ImageType;
import com.tom_roush.pdfbox.rendering.PDFRenderer;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.util.Matrix;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class PdfTools {
    private static final int PAGE_PROGRAM_LIMIT = 256 * 1024;
    private static final long SCRATCH_MEMORY = 4L * 1024 * 1024;
    private static final long SCRATCH_LIMIT = 384L * 1024 * 1024;
    private static final long MIN_PHOTO_PIXELS = 640L * 640;
    private static final long MAX_PHOTO_PIXELS = 12L * 1024 * 1024;

    private PdfTools() {}

    static void initialize(Context context) {
        PDFBoxResourceLoader.init(context.getApplicationContext());
    }

    static int pageCount(File file, int sourcePosition) throws IOException {
        String label = "第 " + sourcePosition + " 份 PDF";
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] head = new byte[1024];
            int length = input.read(head);
            if (length < 5 || !new String(head, 0, length, StandardCharsets.US_ASCII).contains("%PDF-")) {
                throw new IOException(label + "格式不正确");
            }
        }
        try (PDDocument document = open(file)) {
            int pages = document.getNumberOfPages();
            if (pages > 0) return pages;
        } catch (InvalidPasswordException | SecurityException locked) {
            throw new IOException(label + "已加密，请导入无密码的副本", locked);
        } catch (IOException | RuntimeException damaged) {
            throw new IOException(label + "无法读取，请检查文件是否完整", damaged);
        }
        throw new IOException(label + "没有可用页面");
    }

    static Bitmap render(PdfWorkspace workspace, PdfWorkspace.Page page, int maximumEdge) throws IOException {
        try (PDDocument document = open(workspace.source(page.source))) {
            return render(document, page, maximumEdge);
        }
    }

    private static Bitmap render(PDDocument document, PdfWorkspace.Page page, int maximumEdge)
            throws IOException {
        checkInterrupted();
        if (maximumEdge < 1) throw new IllegalArgumentException("Invalid preview size");
        PDPage source = getPage(document, page);
        PDRectangle bounds = source.getCropBox();
        float longest = Math.max(bounds.getWidth(), bounds.getHeight());
        if (!Float.isFinite(longest) || longest <= 0 || bounds.getWidth() <= 0 || bounds.getHeight() <= 0) {
            throw new IOException("PDF 页面尺寸无效");
        }
        int rotation = source.getRotation();
        if (page.turns != 0) source.setRotation((rotation + page.turns * 90) % 360);
        try {
            PDFRenderer reader = new PDFRenderer(document);
            reader.setSubsamplingAllowed(true);
            return reader.renderImage(page.index, Math.min(maximumEdge, 4096) / longest, ImageType.RGB);
        } finally {
            if (page.turns != 0) source.setRotation(rotation);
        }
    }

    static void write(PdfWorkspace workspace, List<PdfWorkspace.Page> pages, OutputStream output,
            ScanExport.Progress progress) throws IOException {
        write(workspace, pages, output, progress, null, false);
    }

    static void write(PdfWorkspace workspace, List<PdfWorkspace.Page> pages, OutputStream output,
            ScanExport.Progress progress, Stamp stamp, boolean compress) throws IOException {
        if (pages.isEmpty()) throw new IOException("请至少选择一页");
        if (stamp != null && (stamp.pageIndex < -1 || stamp.pageIndex >= pages.size())) {
            throw new IOException("请选择有效的签名页");
        }
        if (stamp != null && (stamp.bitmap == null || stamp.bitmap.isRecycled())) {
            throw new IOException("请重新填写水印或签名");
        }
        File scratch = workspace.source(pages.get(0).source).getParentFile();
        Map<String, List<Integer>> sources = bySource(pages);
        try (PDDocument result = new PDDocument(memory(scratch))) {
            result.setVersion(1.7f);
            PDPage[] ordered = new PDPage[pages.size()];
            int completed = 0;
            long inputSize = Long.MAX_VALUE;
            for (Map.Entry<String, List<Integer>> item : sources.entrySet()) {
                File file = workspace.source(item.getKey());
                try (PDDocument original = open(file)) {
                    if (compress && sources.size() == 1 && pages.size() == original.getNumberOfPages()) {
                        inputSize = file.length();
                    }
                    PDFCloneUtility clone = new PDFCloneUtility(result);
                    for (int position : item.getValue()) {
                        checkInterrupted();
                        PdfWorkspace.Page page = pages.get(position);
                        ordered[position] = copyPage(clone, getPage(original, page), page.turns);
                        progress.page(++completed, pages.size());
                    }
                }
            }
            for (PDPage page : ordered) result.addPage(page);
            if (stamp != null) {
                PDImageXObject mark = LosslessFactory.createFromImage(result, stamp.bitmap);
                for (int i = 0; i < ordered.length; i++) {
                    checkInterrupted();
                    if (stamp.pageIndex == -1 || stamp.pageIndex == i) {
                        drawStamp(result, ordered[i], mark, stamp.centered);
                    }
                }
            }
            if (compress) saveCompressed(result, scratch, inputSize, output);
            else save(result, output);
        }
    }

    static List<String> text(PdfWorkspace workspace, List<PdfWorkspace.Page> pages, AssetManager assets,
            ScanExport.Progress progress) throws IOException {
        PDFTextStripper nativeText = new PDFTextStripper();
        nativeText.setSortByPosition(true);
        try (ScanRecognition.Reader ocr = new ScanRecognition.Reader(assets)) {
            return readPages(workspace, pages, progress, (source, page, position) -> {
                int oneBased = page.index + 1;
                nativeText.setStartPage(oneBased);
                nativeText.setEndPage(oneBased);
                String words = nativeText.getText(source).trim();
                if (!words.isEmpty()) return words;
                Bitmap image = render(source, page, ScanImages.OCR_EDGE);
                return ocr.word(image, position);
            });
        }
    }

    static List<OfficeTable> tables(PdfWorkspace workspace, List<PdfWorkspace.Page> pages, AssetManager assets,
            ScanExport.Progress progress) throws IOException {
        try (ScanRecognition.Reader ocr = new ScanRecognition.Reader(assets)) {
            List<List<OfficeTable>> perPage = readPages(workspace, pages, progress,
                    (source, page, position) -> ocr.tables(render(source, page, ScanImages.OCR_EDGE), position));
            List<OfficeTable> result = new ArrayList<>();
            for (List<OfficeTable> matches : perPage) result.addAll(matches);
            return result;
        }
    }

    private static <T> List<T> readPages(PdfWorkspace workspace, List<PdfWorkspace.Page> pages,
            ScanExport.Progress progress, ReadPage<T> reader) throws IOException {
        List<T> result = new ArrayList<>(Collections.nCopies(pages.size(), null));
        int completed = 0;
        for (Map.Entry<String, List<Integer>> item : bySource(pages).entrySet()) {
            try (PDDocument source = open(workspace.source(item.getKey()))) {
                for (int position : item.getValue()) {
                    checkInterrupted();
                    PdfWorkspace.Page page = pages.get(position);
                    getPage(source, page);
                    result.set(position, reader.read(source, page, position + 1));
                    progress.page(++completed, pages.size());
                }
            }
        }
        return result;
    }

    private static PDDocument open(File file) throws IOException {
        PDDocument document = PDDocument.load(file, memory(file.getParentFile()));
        if (document.isEncrypted()) {
            document.close();
            throw new SecurityException("Encrypted PDF");
        }
        return document;
    }

    private static MemoryUsageSetting memory(File directory) {
        return MemoryUsageSetting.setupMixed(SCRATCH_MEMORY, SCRATCH_LIMIT).setTempDir(directory);
    }

    private static PDPage getPage(PDDocument document, PdfWorkspace.Page page) throws IOException {
        if (page.index < 0 || page.index >= document.getNumberOfPages()) {
            throw new IOException("原始 PDF 页码已失效");
        }
        return document.getPage(page.index);
    }

    private static Map<String, List<Integer>> bySource(List<PdfWorkspace.Page> pages) {
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < pages.size(); i++) {
            groups.computeIfAbsent(pages.get(i).source, ignored -> new ArrayList<>()).add(i);
        }
        return groups;
    }

    private static PDPage copyPage(PDFCloneUtility cloner, PDPage original, int turns) throws IOException {
        // Copy the visible page and resolve inherited boxes/resources without following document links.
        COSDictionary visible = new COSDictionary();
        visible.setItem(COSName.TYPE, COSName.PAGE);
        visible.setItem(COSName.CONTENTS, original.getCOSObject().getDictionaryObject(COSName.CONTENTS));
        visible.setItem(COSName.MEDIA_BOX, original.getMediaBox());
        visible.setItem(COSName.CROP_BOX, original.getCropBox());
        visible.setItem(COSName.RESOURCES, pageResources(original));
        for (COSName key : new COSName[]{COSName.BLEED_BOX, COSName.TRIM_BOX, COSName.ART_BOX,
                COSName.GROUP, COSName.USER_UNIT}) {
            visible.setItem(key, original.getCOSObject().getDictionaryObject(key));
        }
        visible.setInt(COSName.ROTATE, (original.getRotation() + turns * 90) % 360);
        return new PDPage((COSDictionary) cloner.cloneForNewDocument(visible));
    }

    private static COSDictionary pageResources(PDPage page) {
        PDResources resources = page.getResources();
        if (resources == null) return null;
        COSDictionary all = resources.getCOSObject();
        COSDictionary images = all.getCOSDictionary(COSName.XOBJECT);
        if (images == null || images.size() == 0 || page.getCOSObject().containsKey(COSName.ANNOTS)
                || page.getCOSObject().containsKey(COSName.GROUP)) return all;

        // Trim direct images only when the page program describes all their uses.
        // A referenced image's mask and other indirect dependencies travel with that image.
        for (COSName key : all.keySet()) {
            if (!COSName.XOBJECT.equals(key) && !COSName.FONT.equals(key) && !COSName.PROC_SET.equals(key)) {
                return all;
            }
        }
        COSDictionary fonts = all.getCOSDictionary(COSName.FONT);
        if (fonts == null && all.containsKey(COSName.FONT)) return all;
        if (fonts != null) {
            for (COSName name : fonts.keySet()) {
                COSDictionary font = fonts.getCOSDictionary(name);
                if (font == null || font.getCOSName(COSName.SUBTYPE) == null
                        || COSName.TYPE3.equals(font.getCOSName(COSName.SUBTYPE))) return all;
            }
        }
        for (COSName name : images.keySet()) if (!resources.isImageXObject(name)) return all;

        try (InputStream contents = page.getContents()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int count; (count = contents.read(buffer)) != -1;) {
                if (bytes.size() + count > PAGE_PROGRAM_LIMIT) return all;
                bytes.write(buffer, 0, count);
            }
            PDFStreamParser parser = new PDFStreamParser(bytes.toByteArray());
            COSDictionary used = new COSDictionary();
            Object previous = null;
            for (Object token; (token = parser.parseNextToken()) != null; previous = token) {
                if (token instanceof Operator operator && "Do".equals(operator.getName())) {
                    if (!(previous instanceof COSName name) || !images.containsKey(name)) return all;
                    used.setItem(name, images.getItem(name));
                }
            }
            if (used.size() == images.size()) return all;
            COSDictionary filtered = new COSDictionary(all);
            filtered.setItem(COSName.XOBJECT, used);
            return filtered;
        } catch (IOException | RuntimeException uncertain) {
            return all;
        }
    }

    private static void drawStamp(PDDocument document, PDPage page, PDImageXObject mark, boolean centered)
            throws IOException {
        PDRectangle bounds = page.getCropBox();
        int rotation = page.getRotation();
        float width = bounds.getWidth();
        float height = bounds.getHeight();
        boolean swapped = rotation == 90 || rotation == 270;
        float visualWidth = swapped ? height : width;
        float visualHeight = swapped ? width : height;
        float scale = Math.min(visualWidth * (centered ? .66f : .28f) / mark.getWidth(),
                visualHeight * (centered ? .30f : .17f) / mark.getHeight());
        if (!Float.isFinite(scale) || scale <= 0) throw new IOException("该页面尺寸无法添加标记");
        float markWidth = mark.getWidth() * scale;
        float markHeight = mark.getHeight() * scale;
        float left = centered ? (visualWidth - markWidth) / 2 : visualWidth * .94f - markWidth;
        float bottom = centered ? (visualHeight - markHeight) / 2 : visualHeight * .06f;
        float x = bounds.getLowerLeftX();
        float y = bounds.getLowerLeftY();
        Matrix transform;
        switch (rotation) {
            case 90: transform = new Matrix(0, 1, -1, 0, x + width, y); break;
            case 180: transform = new Matrix(-1, 0, 0, -1, x + width, y + height); break;
            case 270: transform = new Matrix(0, -1, 1, 0, x, y + height); break;
            default: transform = new Matrix(1, 0, 0, 1, x, y);
        }
        try (PDPageContentStream stream = new PDPageContentStream(document, page,
                PDPageContentStream.AppendMode.APPEND, true, true)) {
            stream.transform(transform);
            stream.drawImage(mark, left, bottom, markWidth, markHeight);
        }
    }

    private static void saveCompressed(PDDocument document, File scratch, long inputSize,
            OutputStream output) throws IOException {
        File original = File.createTempFile("pdf-before-", ".tmp", scratch);
        File compressed = null;
        try {
            document.save(original);
            if (!compressPhotos(document)) throw new IOException("没有可压缩的页面图片");
            compressed = File.createTempFile("pdf-after-", ".tmp", scratch);
            document.save(compressed);
            if (compressed.length() >= Math.min(original.length(), inputSize)) {
                throw new IOException("图片压缩后文件没有减小");
            }
            try (FileInputStream input = new FileInputStream(compressed)) {
                byte[] buffer = new byte[64 * 1024];
                for (int count; (count = input.read(buffer)) != -1;) {
                    checkInterrupted();
                    output.write(buffer, 0, count);
                }
            }
        } finally {
            original.delete();
            if (compressed != null) compressed.delete();
        }
    }

    private static boolean compressPhotos(PDDocument document) throws IOException {
        boolean changed = false;
        Set<COSDictionary> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<COSDictionary, PDImageXObject> replacements = new IdentityHashMap<>();
        for (PDPage page : document.getPages()) {
            checkInterrupted();
            PDResources resources = page.getResources();
            if (resources == null || !visited.add(resources.getCOSObject())) continue;
            for (COSName name : resources.getXObjectNames()) {
                checkInterrupted();
                PDXObject resource = resources.getXObject(name);
                if (!(resource instanceof PDImageXObject)) continue;
                PDImageXObject original = (PDImageXObject) resource;
                COSDictionary dictionary = original.getCOSObject();
                if (!replacements.containsKey(dictionary)) {
                    replacements.put(dictionary, compressPhoto(document, original));
                }
                PDImageXObject replacement = replacements.get(dictionary);
                if (replacement != null) {
                    resources.put(name, replacement);
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static PDImageXObject compressPhoto(PDDocument document, PDImageXObject image) throws IOException {
        COSDictionary source = image.getCOSObject();
        long pixels = (long) image.getWidth() * image.getHeight();
        if (pixels < MIN_PHOTO_PIXELS || pixels > MAX_PHOTO_PIXELS || image.getBitsPerComponent() != 8
                || image.isStencil() || source.containsKey(COSName.MASK)
                || source.containsKey(COSName.DECODE)) return null;
        if (!(image.getColorSpace() instanceof PDDeviceRGB)
                && !(image.getColorSpace() instanceof PDDeviceGray)) return null;
        COSBase softMask = source.getDictionaryObject(COSName.SMASK);
        if (softMask instanceof COSStream) {
            if (((COSStream) softMask).containsKey(COSName.MATTE)) return null;
        } else if (softMask != null && !COSName.NONE.equals(softMask)) {
            return null;
        }
        // Recompress the color plane. An ordinary soft mask is still attached without re-encoding it.
        Bitmap decoded = image.getOpaqueImage();
        if (decoded == null) return null;
        try {
            if (decoded.getWidth() != image.getWidth() || decoded.getHeight() != image.getHeight()) return null;
            decoded.setHasAlpha(false);
            PDImageXObject smaller = JPEGFactory.createFromImage(document, decoded, .68f);
            if (smaller.getCOSObject().getLength() + 256 >= image.getCOSObject().getLength()) return null;
            smaller.getCOSObject().setItem(COSName.SMASK, softMask);
            smaller.getCOSObject().setItem(COSName.INTERPOLATE, source.getDictionaryObject(COSName.INTERPOLATE));
            return smaller;
        } finally {
            decoded.recycle();
        }
    }

    private static void save(PDDocument document, OutputStream output) throws IOException {
        document.save(new FilterOutputStream(output) {
            @Override public void write(byte[] bytes, int offset, int size) throws IOException {
                out.write(bytes, offset, size);
            }

            @Override public void close() throws IOException {
                flush();
            }
        });
    }

    private static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("已取消");
    }

    private interface ReadPage<T> {
        T read(PDDocument source, PdfWorkspace.Page page, int position) throws IOException;
    }

    static final class Stamp {
        final Bitmap bitmap;
        final boolean centered;
        final int pageIndex;

        Stamp(Bitmap bitmap, boolean centered, int pageIndex) {
            this.bitmap = bitmap;
            this.centered = centered;
            this.pageIndex = pageIndex;
        }
    }
}
