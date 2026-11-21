package com.scandesk.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.PDResources;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission;
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(AndroidJUnit4.class)
public final class PdfToolsTest {
    private static final ScanExport.Progress IGNORE_PROGRESS = (done, total) -> {};
    private Context context;
    private File directory;
    private PdfWorkspace workspace;

    @Before public void setUp() throws IOException {
        context = ApplicationProvider.getApplicationContext();
        directory = Files.createTempDirectory(context.getCacheDir().toPath(), "pdf-test-").toFile();
        workspace = new PdfWorkspace(directory);
        PdfTools.initialize(context);
    }

    @After public void tearDown() {
        File[] files = directory.listFiles();
        if (files != null) for (File child : files) child.delete();
        directory.delete();
    }

    @Test public void mergesAndExtractsOrderedPagesWithSearchableTextAndVectors() throws Exception {
        String first = ScanStore.newId();
        String second = ScanStore.newId();
        makeTextPdf(workspace.source(first), "FIRST SOURCE", "OMIT THIS PAGE", "LAST SOURCE");
        makeTextPdf(workspace.source(second), "SECOND FILE");
        byte[] unchanged = Files.readAllBytes(workspace.source(first).toPath());
        List<PdfWorkspace.Page> choice = Arrays.asList(page(first, 2, 1), page(second, 0, 0), page(first, 0, 0));
        assertEquals(3, PdfTools.pageCount(workspace.source(first), 1));
        Bitmap editedPreview = PdfTools.render(workspace, choice.get(0), 480);
        try {
            assertEquals(480, editedPreview.getWidth());
            assertEquals(360, editedPreview.getHeight());
        } finally {
            editedPreview.recycle();
        }

        File result = new File(directory, "merged.pdf");
        try (FileOutputStream output = new FileOutputStream(result)) {
            PdfTools.write(workspace, choice, output, IGNORE_PROGRESS);
        }
        try (PDDocument exported = PDDocument.load(result)) {
            assertEquals(3, exported.getNumberOfPages());
            assertEquals(90, exported.getPage(0).getRotation());
            assertEquals("LAST SOURCE\nSECOND FILE\nFIRST SOURCE", searchableText(exported));
        }
        Bitmap preview = platformPreview(result, 0);
        try {
            assertTrue(preview.getWidth() > preview.getHeight());
            assertTrue(Color.blue(preview.getPixel(preview.getWidth() / 2, preview.getHeight() / 2)) > 170);
            assertTrue(Color.red(preview.getPixel(preview.getWidth() / 2, preview.getHeight() / 2)) < 100);
        } finally {
            preview.recycle();
        }
        assertArrayEquals(unchanged, Files.readAllBytes(workspace.source(first).toPath()));
    }

    @Test public void extractsSharedImageResourcesUsedByThePageAndPreservesItsSoftMask() throws Exception {
        String input = ScanStore.newId();
        String output = ScanStore.newId();
        Bitmap keptMark = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888);
        Paint red = new Paint();
        red.setColor(0xffff3822);
        new Canvas(keptMark).drawRect(20, 20, 100, 100, red);
        Bitmap otherMark = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888);
        otherMark.eraseColor(0xff0022dd);
        Set<COSName> expected = new HashSet<>();
        try (PDDocument document = new PDDocument()) {
            PDResources shared = new PDResources();
            PDImageXObject keptImage = LosslessFactory.createFromImage(document, keptMark);
            PDImageXObject otherImage = LosslessFactory.createFromImage(document, otherMark);
            expected.add(shared.add(keptImage));
            shared.add(otherImage);
            for (int i = 0; i < 2; i++) {
                PDPage page = new PDPage(new PDRectangle(320, 420));
                document.addPage(page);
                page.setResources(shared);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.drawImage(i == 0 ? keptImage : otherImage, 45, 80, 200, 200);
                    label(stream, i == 0 ? "KEEP THIS PAGE" : "EXCLUDED PAGE", 45, 365);
                }
                page.setResources(null);
            }
            document.getPages().getCOSObject().setItem(COSName.RESOURCES, shared);
            document.save(workspace.source(input));
        } finally {
            keptMark.recycle();
            otherMark.recycle();
        }

        try (FileOutputStream stream = new FileOutputStream(workspace.source(output))) {
            PdfTools.write(workspace, List.of(page(input, 0, 0)), stream, IGNORE_PROGRESS);
        }
        try (PDDocument extracted = PDDocument.load(workspace.source(output))) {
            assertEquals(1, extracted.getNumberOfPages());
            assertEquals("KEEP THIS PAGE", searchableText(extracted));
            PDResources resources = extracted.getPage(0).getResources();
            Set<COSName> names = new HashSet<>();
            for (COSName name : resources.getXObjectNames()) names.add(name);
            assertEquals(expected, names);
            PDImageXObject image = (PDImageXObject) resources.getXObject(expected.iterator().next());
            assertNotNull(image.getSoftMask());
        }
        Bitmap original = PdfTools.render(workspace, page(input, 0, 0), 420);
        Bitmap extracted = PdfTools.render(workspace, page(output, 0, 0), 420);
        try {
            assertTrue(original.sameAs(extracted));
        } finally {
            original.recycle();
            extracted.recycle();
        }
    }

    @Test public void extractsLayeredTextAndRecognizesAnImageOnlyPdfPage() throws Exception {
        String id = ScanStore.newId();
        Bitmap scan = Bitmap.createBitmap(960, 720, Bitmap.Config.ARGB_8888);
        try (PDDocument source = new PDDocument()) {
            PDPage scanPage = new PDPage(new PDRectangle(480, 360));
            PDPage textPage = new PDPage(new PDRectangle(480, 360));
            source.addPage(scanPage);
            source.addPage(textPage);
            Canvas canvas = new Canvas(scan);
            canvas.drawColor(Color.WHITE);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.BLACK);
            paint.setTextSize(63f);
            canvas.drawText("OFFLINE FILE 482", 92, 310, paint);
            try (PDPageContentStream writer = new PDPageContentStream(source, scanPage)) {
                writer.drawImage(LosslessFactory.createFromImage(source, scan), 0, 0, 480, 360);
            }
            try (PDPageContentStream writer = new PDPageContentStream(source, textPage)) {
                label(writer, "REFERENCE A-029", 50, 175);
            }
            source.save(workspace.source(id));
        } finally {
            scan.recycle();
        }
        List<String> words = PdfTools.text(workspace,
                Arrays.asList(page(id, 1, 0), page(id, 0, 0)), context.getAssets(), IGNORE_PROGRESS);
        assertEquals("REFERENCE A-029", words.get(0));
        assertTrue(words.get(1), words.get(1).contains("OFFLINE FILE 482"));
    }

    @Test public void detectsAnEditableTableFromPdfPage() throws Exception {
        String id = ScanStore.newId();
        try (PDDocument source = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(480, 600));
            source.addPage(page);
            try (PDPageContentStream writer = new PDPageContentStream(source, page)) {
                writer.setLineWidth(1.3f);
                writer.setStrokingColor(0f, 0f, 0f);
                for (int y = 240; y <= 440; y += 50) {
                    writer.moveTo(36, y);
                    writer.lineTo(444, y);
                }
                for (int x = 36; x <= 444; x += 136) {
                    writer.moveTo(x, 240);
                    writer.lineTo(x, 440);
                }
                writer.stroke();
                String[][] rows = {{"Code", "Item", "Qty"}, {"A12", "Apple", "20"},
                        {"B14", "Grape", "35"}, {"C19", "Melon", "42"}};
                for (int row = 0; row < rows.length; row++) {
                    for (int col = 0; col < rows[row].length; col++) {
                        label(writer, rows[row][col], 56 + 136 * col, 407 - 50 * row);
                    }
                }
            }
            source.save(workspace.source(id));
        }
        List<OfficeTable> tables = PdfTools.tables(workspace,
                Collections.singletonList(page(id, 0, 0)), context.getAssets(), IGNORE_PROGRESS);
        assertEquals(1, tables.size());
        OfficeTable table = tables.get(0);
        assertEquals(4, table.rows);
        assertEquals(3, table.columns);
        assertEquals("Apple", table.cell(1, 1).text);
        assertEquals("42", table.cell(3, 2).text);
    }

    @Test public void signsOnlyTheChosenPageInItsVisibleOrientation() throws Exception {
        String id = ScanStore.newId();
        makeTextPdf(workspace.source(id), "STAYS UNSIGNED", "SIGNED COPY");
        Bitmap mark = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888);
        mark.eraseColor(0xffe32c37);
        String exported = ScanStore.newId();
        File result = workspace.source(exported);
        try (FileOutputStream output = new FileOutputStream(result)) {
            PdfTools.write(workspace, Arrays.asList(page(id, 0, 0), page(id, 1, 1)), output, IGNORE_PROGRESS,
                    new PdfTools.Stamp(mark, false, 1), false);
        } finally {
            mark.recycle();
        }
        Bitmap unsigned = PdfTools.render(workspace, page(exported, 0, 0), 640);
        Bitmap signed = PdfTools.render(workspace, page(exported, 1, 0), 640);
        try {
            assertEquals(Color.WHITE, sample(unsigned, .82f, .86f));
            int pixel = sample(signed, .82f, .86f);
            assertTrue("Expected rotated lower-right signature, found " + Integer.toHexString(pixel),
                    Color.red(pixel) > 170 && Color.green(pixel) < 90 && Color.blue(pixel) < 90);
        } finally {
            unsigned.recycle();
            signed.recycle();
        }
        try (PDDocument marked = PDDocument.load(result)) {
            assertEquals("STAYS UNSIGNED\nSIGNED COPY", searchableText(marked));
        }
    }

    @Test public void reducesPhotoHeavyPdfAndRejectsTextOnlyCompressionWithoutWriting() throws Exception {
        String photo = ScanStore.newId();
        Bitmap image = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[800 * 600];
        Random random = new Random(276);
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = i / 800 < 64 && i % 800 < 64 ? 0 : 0xff000000 | random.nextInt(0x1000000);
        }
        image.setPixels(pixels, 0, 800, 0, 0, 800, 600);
        try (PDDocument source = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(480, 480));
            source.addPage(page);
            try (PDPageContentStream writer = new PDPageContentStream(source, page)) {
                writer.drawImage(LosslessFactory.createFromImage(source, image), 0, 0, 480, 360);
                label(writer, "SEARCHABLE PHOTO", 30, 420);
            }
            source.save(workspace.source(photo));
        } finally {
            image.recycle();
        }
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        PdfTools.write(workspace, Collections.singletonList(page(photo, 0, 0)), compressed, IGNORE_PROGRESS,
                null, true);
        assertTrue(compressed.size() < workspace.source(photo).length() / 2);
        try (PDDocument reopened = PDDocument.load(compressed.toByteArray())) {
            assertEquals("SEARCHABLE PHOTO", new PDFTextStripper().getText(reopened).trim());
            PDResources resources = reopened.getPage(0).getResources();
            PDImageXObject photoObject = (PDImageXObject) resources.getXObject(
                    resources.getXObjectNames().iterator().next());
            assertNotNull(photoObject.getSoftMask());
            Bitmap alpha = photoObject.getSoftMask().getOpaqueImage();
            try {
                assertEquals(0, Color.red(alpha.getPixel(16, 16)));
                assertEquals(255, Color.red(alpha.getPixel(700, 160)));
            } finally {
                alpha.recycle();
            }
        }

        String text = ScanStore.newId();
        makeTextPdf(workspace.source(text), "TEXT ONLY");
        ByteArrayOutputStream originalSize = new ByteArrayOutputStream();
        try {
            PdfTools.write(workspace, Collections.singletonList(page(text, 0, 0)), originalSize, IGNORE_PROGRESS,
                    null, true);
            fail("Expected a no-benefit result");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("没有可压缩"));
        }
        assertEquals(0, originalSize.size());
    }

    @Test public void importsValidPdfAndLeavesWorkspaceIntactOnFailure() throws Exception {
        File source = new File(directory, "import.pdf");
        makeTextPdf(source, "RECOVERABLE PAGE");
        List<PdfWorkspace.Page> imported = workspace.add(context.getContentResolver(),
                Collections.emptyList(), Collections.singletonList(Uri.fromFile(source)), false,
                () -> {}, IGNORE_PROGRESS);
        assertEquals(1, imported.size());
        File malformed = new File(directory, "damaged.pdf");
        Files.write(malformed.toPath(), "invalid file".getBytes(StandardCharsets.US_ASCII));
        try {
            workspace.add(context.getContentResolver(), imported,
                    Collections.singletonList(Uri.fromFile(malformed)), true, () -> {}, IGNORE_PROGRESS);
            fail("Imported invalid PDF");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("格式不正确"));
        }
        assertEquals(imported.get(0).source, workspace.load().get(0).source);

        try (PDDocument locked = new PDDocument()) {
            locked.addPage(new PDPage());
            locked.protect(new StandardProtectionPolicy("test-owner", "test-open", new AccessPermission()));
            locked.save(malformed);
        }
        try {
            PdfTools.pageCount(malformed, 2);
            fail("Opened password-protected PDF");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("第 2 份 PDF已加密"));
        }
    }

    @Test public void scopedPdfImportPreservesOldPagesAndCommitsOnlyWhenCompleted() throws Exception {
        File first = new File(directory, "existing.pdf");
        File second = new File(directory, "new-file.pdf");
        makeTextPdf(first, "EXISTING DOCUMENT");
        makeTextPdf(second, "NEW DOCUMENT");
        List<PdfWorkspace.Page> existing = workspace.add(context.getContentResolver(),
                Collections.emptyList(), List.of(Uri.fromFile(first)), false, () -> {}, IGNORE_PROGRESS);
        PdfWorkspace.Page rotated = existing.get(0).rotate();
        List<PdfWorkspace.Page> before = List.of(rotated);
        workspace.save(before);

        AtomicBoolean cancelled = new AtomicBoolean();
        try {
            workspace.add(context.getContentResolver(), before, List.of(Uri.fromFile(second)), true,
                    () -> { if (cancelled.get()) throw new CancellationException(); },
                    (done, total) -> cancelled.set(true));
            fail("Cancelled import must leave the previous selection");
        } catch (CancellationException expected) {
            assertEquals(1, workspace.load().size());
            assertTrue(workspace.load().get(0).selected);
            assertEquals(1, workspace.load().get(0).turns);
        }

        List<PdfWorkspace.Page> imported = workspace.add(context.getContentResolver(), before,
                List.of(Uri.fromFile(second)), true, () -> {}, IGNORE_PROGRESS);
        assertEquals(2, imported.size());
        assertEquals(rotated.source, imported.get(0).source);
        assertEquals(1, imported.get(0).turns);
        assertFalse(imported.get(0).selected);
        assertTrue(imported.get(1).selected);
        assertEquals(1, PdfTools.pageCount(workspace.source(rotated.source), 1));
        assertFalse(workspace.load().get(0).selected);
        assertEquals(List.of("NEW DOCUMENT"), PdfTools.text(workspace,
                List.of(imported.get(1)), context.getAssets(), IGNORE_PROGRESS));
    }

    @Test public void recognizesLocalizedRangesWithoutJoiningMistypedPageNumbers() {
        BitSet wanted = new BitSet(12);
        wanted.set(0, 3);
        wanted.set(5);
        assertEquals(wanted, PdfPagesDialog.parse(" １ — ３、６ ", 12));
        for (String invalid : List.of("1 2", "1,,2", "0", "3–2", "13")) {
            try {
                PdfPagesDialog.parse(invalid, 12);
                fail("Accepted invalid page range " + invalid);
            } catch (IllegalArgumentException expected) {
                // A mistyped range must not silently select different pages.
            }
        }
    }

    private static PdfWorkspace.Page page(String id, int index, int turns) {
        return new PdfWorkspace.Page(id, "测试.pdf", index, true, turns);
    }

    private static void makeTextPdf(File file, String... lines) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDResources resources = new PDResources();
            for (String line : lines) {
                PDPage page = new PDPage(new PDRectangle(480, 640));
                document.addPage(page);
                page.setResources(resources);
                try (PDPageContentStream writer = new PDPageContentStream(document, page)) {
                    writer.setNonStrokingColor(31 / 255f, 63 / 255f, 217 / 255f);
                    writer.addRect(215, 293, 50, 54);
                    writer.fill();
                    label(writer, line, 46, 540);
                }
                page.setResources(null);
            }
            document.getPages().getCOSObject().setItem(COSName.RESOURCES, resources);
            document.save(file);
        }
    }

    private static void label(PDPageContentStream writer, String text, float x, float y) throws IOException {
        writer.setNonStrokingColor(0f, 0f, 0f);
        writer.beginText();
        writer.setFont(PDType1Font.HELVETICA, 18);
        writer.newLineAtOffset(x, y);
        writer.showText(text);
        writer.endText();
    }

    private static Bitmap platformPreview(File file, int index) throws IOException {
        try (PdfRenderer renderer = new PdfRenderer(ParcelFileDescriptor.open(file,
                ParcelFileDescriptor.MODE_READ_ONLY));
                PdfRenderer.Page page = renderer.openPage(index)) {
            Bitmap result = Bitmap.createBitmap(page.getWidth(), page.getHeight(), Bitmap.Config.ARGB_8888);
            result.eraseColor(Color.WHITE);
            page.render(result, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return result;
        }
    }

    private static int sample(Bitmap bitmap, float x, float y) {
        return bitmap.getPixel((int) (bitmap.getWidth() * x), (int) (bitmap.getHeight() * y));
    }

    private static String searchableText(PDDocument document) throws IOException {
        PDFTextStripper text = new PDFTextStripper();
        text.setSortByPosition(true);
        return text.getText(document).trim();
    }
}
