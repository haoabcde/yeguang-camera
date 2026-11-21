package com.scandesk.app;

import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class ScanExport {
    private ScanExport() {}

    static String displayName(ContentResolver resolver, Uri uri, String fallback) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) {
                    return name.trim().replaceAll("[\\p{Cntrl}]", " ");
                }
            }
        } catch (RuntimeException unavailable) {
            // Some providers supply a stream without display metadata.
        }
        return fallback;
    }

    interface Progress {
        void page(int completed, int total);
    }

    interface BitmapSource {
        Bitmap page(int index) throws IOException;
    }

    static void image(ScanStore store, ScanDocument document, ScanDocument.Page page,
            boolean png, OutputStream output) throws IOException {
        Bitmap bitmap = ScanImages.render(store.image(document, page), page, ScanImages.EXPORT_EDGE);
        try {
            Bitmap.CompressFormat format = png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG;
            if (!bitmap.compress(format, 94, output)) throw new IOException("保存图片失败");
        } finally {
            bitmap.recycle();
        }
    }

    static void pdf(ScanStore store, ScanDocument document, boolean a4, OutputStream stream,
            Progress progress) throws IOException {
        rasterPdf(document.pages.size(), index -> {
            ScanDocument.Page page = document.pages.get(index);
            return ScanImages.render(store.image(document, page), page, ScanImages.EXPORT_EDGE);
        }, a4, stream, progress);
    }

    static void rasterPdf(int count, BitmapSource source, boolean a4, OutputStream stream,
            Progress progress) throws IOException {
        if (count == 0) throw new IOException("请先添加图片");
        PdfWriter pdf = new PdfWriter(stream, count);
        for (int i = 0; i < count; i++) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("已取消");
            Bitmap bitmap = source.page(i);
            try {
                pdf.page(i, bitmap, a4);
            } finally {
                bitmap.recycle();
            }
            progress.page(i + 1, count);
        }
        pdf.finish();
    }

    static void save(ContentResolver resolver, File generated, Uri destination) throws IOException {
        try (FileInputStream source = new FileInputStream(generated);
                OutputStream output = resolver.openOutputStream(destination, "wt")) {
            if (output == null) throw new IOException("No output stream");
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = source.read(buffer)) != -1;) output.write(buffer, 0, count);
            output.flush();
        } catch (IOException | RuntimeException failure) {
            throw new IOException("未能保存到所选位置。文件已生成，可重试保存", failure);
        }
    }

    /** A page-at-a-time raster PDF writer. The largest retained image is one compressed page. */
    private static final class PdfWriter {
        private final CountingOutput output;
        private final long[] offsets;
        private final ByteArrayOutputStream jpeg = new ByteArrayOutputStream();

        PdfWriter(OutputStream stream, int pages) throws IOException {
            output = new CountingOutput(stream);
            offsets = new long[3 + pages * 3];
            ascii("%PDF-1.4\n");
            output.write(new byte[]{'%', (byte) 0xe2, (byte) 0xe3, (byte) 0xcf, (byte) 0xd3, '\n'});
            begin(1);
            ascii("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
            begin(2);
            ascii("<< /Type /Pages /Count " + pages + " /Kids [ ");
            for (int i = 0; i < pages; i++) ascii((3 + i * 3) + " 0 R ");
            ascii("] >>\nendobj\n");
        }

        void page(int index, Bitmap bitmap, boolean a4) throws IOException {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            double pageWidth = a4 ? (width > height ? 842 : 595) : width * 72.0 / 220;
            double pageHeight = a4 ? (width > height ? 595 : 842) : height * 72.0 / 220;
            if (!a4 && Math.min(pageWidth, pageHeight) < 24) {
                double adjustment = 24 / Math.min(pageWidth, pageHeight);
                pageWidth *= adjustment;
                pageHeight *= adjustment;
            }
            double border = a4 ? 16 : 0;
            double scale = Math.min((pageWidth - border * 2) / width,
                    (pageHeight - border * 2) / height);
            double drawWidth = width * scale;
            double drawHeight = height * scale;
            int pageObject = 3 + index * 3;
            int imageObject = pageObject + 1;
            int contentObject = pageObject + 2;

            begin(pageObject);
            ascii(String.format(Locale.ROOT,
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 %.3f %.3f]"
                    + " /Resources << /XObject << /Scan %d 0 R >> >> /Contents %d 0 R >>\nendobj\n",
                    pageWidth, pageHeight, imageObject, contentObject));

            jpeg.reset();
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 91, jpeg)) {
                throw new IOException("无法压缩第 " + (index + 1) + " 页");
            }
            begin(imageObject);
            ascii("<< /Type /XObject /Subtype /Image /Width " + width + " /Height " + height
                    + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length "
                    + jpeg.size() + " >>\nstream\n");
            jpeg.writeTo(output);
            ascii("\nendstream\nendobj\n");

            String instructions = String.format(Locale.ROOT, "q\n%.4f 0 0 %.4f %.4f %.4f cm\n/Scan Do\nQ\n",
                    drawWidth, drawHeight, (pageWidth - drawWidth) / 2, (pageHeight - drawHeight) / 2);
            begin(contentObject);
            ascii("<< /Length " + instructions.length() + " >>\nstream\n");
            ascii(instructions);
            ascii("endstream\nendobj\n");
        }

        void finish() throws IOException {
            long xref = output.count;
            ascii("xref\n0 " + offsets.length + "\n0000000000 65535 f \n");
            for (int i = 1; i < offsets.length; i++) {
                ascii(String.format(Locale.ROOT, "%010d 00000 n \n", offsets[i]));
            }
            ascii("trailer\n<< /Size " + offsets.length + " /Root 1 0 R >>\nstartxref\n"
                    + xref + "\n%%EOF\n");
        }

        private void begin(int id) throws IOException {
            offsets[id] = output.count;
            ascii(id + " 0 obj\n");
        }

        private void ascii(String text) throws IOException {
            output.write(text.getBytes(StandardCharsets.US_ASCII));
        }
    }

    private static final class CountingOutput extends FilterOutputStream {
        long count;

        CountingOutput(OutputStream output) {
            super(output);
        }

        @Override public void write(int value) throws IOException {
            out.write(value);
            count++;
        }

        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            out.write(bytes, offset, length);
            count += length;
        }
    }
}
