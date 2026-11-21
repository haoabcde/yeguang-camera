package com.scandesk.app;

import android.app.Instrumentation;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Exercises the actual comparison/apply/restore UI; model geometry is tested separately. */
@RunWith(AndroidJUnit4.class)
public final class BookPreviewFlowTest {
    private final Instrumentation device = InstrumentationRegistry.getInstrumentation();

    @Test public void comparisonAndCancelReleaseCandidateWithoutChangingTheDocument() throws Exception {
        try (Fixture sample = new Fixture()) {
            BookFlatteningPreview proposal = sample.proposal();
            try (ActivityScenario<MainActivity> app = ActivityScenario.launch(MainActivity.class)) {
                AtomicInteger priorOrientation = new AtomicInteger();
                app.onActivity(activity -> priorOrientation.set(activity.getRequestedOrientation()));
                try {
                    rotate(app, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT);
                    show(app, sample, proposal);
                    checkComparison(app, proposal);
                    screenshot(app, sample.context, "portrait");
                    rotate(app, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE);
                    screenshot(app, sample.context, "landscape");
                    checkComparison(app, proposal);
                    rotate(app, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT);
                    checkComparison(app, proposal);
                    app.onActivity(activity -> assertTrue(dialog(activity)
                            .getButton(AlertDialog.BUTTON_NEGATIVE).performClick()));
                    device.waitForIdleSync();
                    assertFalse(proposal.flattened.exists());
                    assertTrue(proposal.before.isRecycled());
                    assertTrue(proposal.after.isRecycled());
                    assertEquals(sample.document.revision, sample.store.load(sample.document.id).revision);
                    assertFalse(sample.store.load(sample.document.id).pages.get(0).isFlattened());
                    assertArrayEquals(sample.originalBytes, Files.readAllBytes(sample.originalFile.toPath()));
                } finally { app.onActivity(activity -> activity.setRequestedOrientation(priorOrientation.get())); }
            } finally { proposal.close(); }
        }
    }

    @Test public void appliedPageSurvivesReopeningExportsTheActiveImageAndRestoresOriginalSettings() throws Exception {
        try (Fixture sample = new Fixture()) {
            BookFlatteningPreview proposal = sample.proposal();
            try (ActivityScenario<MainActivity> app = ActivityScenario.launch(MainActivity.class)) {
                // The editor was in crop mode before opening the image tool. The old crop must
                // not be applied a second time to the already cropped, flattened candidate.
                app.onActivity(activity -> {
                    setField(activity, "cropping", true);
                    setField(activity, "pendingCrop", sample.page.crop);
                });
                show(app, sample, proposal);
                app.onActivity(activity -> assertTrue(dialog(activity)
                        .getButton(AlertDialog.BUTTON_POSITIVE).performClick()));
                awaitJob(app);
                app.onActivity(activity -> {
                    assertNull(field(activity, "bookPreviewDialog"));
                    assertFalse((Boolean) field(activity, "cropping"));
                    assertNull(field(activity, "pendingCrop"));
                });
                assertFalse(proposal.flattened.exists());
                app.moveToState(Lifecycle.State.CREATED);
                app.moveToState(Lifecycle.State.RESUMED);
                ScanDocument saved = sample.store.load(sample.document.id);
                assertEquals(sample.document.revision + 1, saved.revision);
                assertTrue(saved.pages.get(0).isFlattened());
                assertEquals(CropQuad.FULL, saved.pages.get(0).crop);
                assertEquals(0, saved.pages.get(0).rotation);
                assertArrayEquals(sample.originalBytes, Files.readAllBytes(sample.originalFile.toPath()));
                assertExportsUseColor(sample.context, sample.store, saved, Color.BLUE);
            } finally { proposal.close(); }

            try (ActivityScenario<MainActivity> reopened = ActivityScenario.launch(MainActivity.class)) {
                reopened.onActivity(activity -> {
                    ScanDocument current = (ScanDocument) field(activity, "document");
                    assertNotNull(current);
                    assertTrue(current.pages.get(0).isFlattened());
                    // Mirror entering a pending crop on the derived image before choosing restore.
                    setField(activity, "cropping", true);
                    setField(activity, "pendingCrop", CropQuad.FULL.move(0, .16f, .12f));
                    invoke(activity, "flattenBookPage", new Class<?>[0]);
                });
                clickActiveDialog("恢复原稿");
                awaitJob(reopened);
                reopened.onActivity(activity -> {
                    assertFalse((Boolean) field(activity, "cropping"));
                    assertNull(field(activity, "pendingCrop"));
                });
                reopened.moveToState(Lifecycle.State.CREATED);
                reopened.moveToState(Lifecycle.State.RESUMED);
                ScanDocument restored = sample.store.load(sample.document.id);
                ScanDocument.Page page = restored.pages.get(0);
                assertFalse(page.isFlattened());
                assertEquals(sample.page.rotation, page.rotation);
                assertEquals(sample.page.crop, page.crop);
                assertEquals(sample.page.filter, page.filter);
                assertEquals(sample.document.revision + 2, restored.revision);
                assertFalse(sample.store.image(restored, sample.page.flattened()).exists());
                assertArrayEquals(sample.originalBytes, Files.readAllBytes(sample.originalFile.toPath()));
                assertExportsUseColor(sample.context, sample.store, restored, Color.RED);
            }
        }
    }

    @Test public void destroyingActivityReleasesAnUnappliedComparison() throws Exception {
        try (Fixture sample = new Fixture()) {
            BookFlatteningPreview proposal = sample.proposal();
            try {
                try (ActivityScenario<MainActivity> app = ActivityScenario.launch(MainActivity.class)) {
                    show(app, sample, proposal);
                }
                device.waitForIdleSync();
                assertFalse(proposal.flattened.exists());
                assertTrue(proposal.before.isRecycled());
                assertTrue(proposal.after.isRecycled());
                assertEquals(sample.document.revision, sample.store.load(sample.document.id).revision);
                assertArrayEquals(sample.originalBytes, Files.readAllBytes(sample.originalFile.toPath()));
            } finally { proposal.close(); }
        }
    }

    private void show(ActivityScenario<MainActivity> app, Fixture sample, BookFlatteningPreview proposal) {
        app.onActivity(activity -> invoke(activity, "showBookPreview",
                new Class<?>[]{ScanDocument.class, ScanDocument.Page.class, BookFlatteningPreview.class},
                sample.document, sample.page, proposal));
        device.waitForIdleSync();
    }

    private void checkComparison(ActivityScenario<MainActivity> app, BookFlatteningPreview proposal) {
        awaitComparisonLayout(app);
        app.onActivity(activity -> {
            AlertDialog dialog = dialog(activity);
            ScanPreview preview = find(dialog.getWindow().getDecorView(), ScanPreview.class);
            assertNotNull(preview);
            assertFullyVisible("书页预览", preview);
            assertFullyVisible("应用展平", dialog.getButton(AlertDialog.BUTTON_POSITIVE));
            assertFullyVisible("取消", dialog.getButton(AlertDialog.BUTTON_NEGATIVE));
            assertFullyVisible("展平前", clickable(dialog.getWindow().getDecorView(), "展平前"));
            assertFullyVisible("展平后", clickable(dialog.getWindow().getDecorView(), "展平后"));
            click(dialog.getWindow().getDecorView(), "展平前");
            assertSame(proposal.before, field(preview, "bitmap"));
            assertTrue(clickable(dialog.getWindow().getDecorView(), "展平前").isSelected());
            click(dialog.getWindow().getDecorView(), "展平后");
            assertSame(proposal.after, field(preview, "bitmap"));
            assertTrue(clickable(dialog.getWindow().getDecorView(), "展平后").isSelected());
        });
    }

    private static void assertFullyVisible(String label, View view) {
        Rect visible = new Rect(), screen = new Rect();
        assertTrue(label, view.isShown() && view.getGlobalVisibleRect(visible));
        assertTrue(label + " 被父容器裁切", visible.width() >= view.getWidth() - 1
                && visible.height() >= view.getHeight() - 1);
        view.getWindowVisibleDisplayFrame(screen);
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        assertTrue(label + " 超出可见屏幕", location[0] >= screen.left && location[1] >= screen.top
                && location[0] + view.getWidth() <= screen.right + 1
                && location[1] + view.getHeight() <= screen.bottom + 1);
    }

    private void rotate(ActivityScenario<MainActivity> app, int request, int expected) {
        app.onActivity(activity -> activity.setRequestedOrientation(request));
        long until = SystemClock.uptimeMillis() + 10000;
        AtomicInteger actual = new AtomicInteger();
        do {
            device.waitForIdleSync();
            app.onActivity(activity -> actual.set(activity.getResources().getConfiguration().orientation));
            if (actual.get() == expected) {
                // Configuration delivery precedes the system's SurfaceFlinger rotation animation.
                // Wait off the main thread before either geometry assertions or evidence capture.
                SystemClock.sleep(700);
                device.waitForIdleSync();
                return;
            }
            SystemClock.sleep(35);
        } while (SystemClock.uptimeMillis() < until);
        assertEquals("屏幕已旋转", expected, actual.get());
    }

    private void awaitComparisonLayout(ActivityScenario<MainActivity> app) {
        long until = SystemClock.uptimeMillis() + 5000;
        String[] previous = {null};
        AtomicInteger stable = new AtomicInteger();
        AtomicBoolean focused = new AtomicBoolean();
        do {
            device.waitForIdleSync();
            app.onActivity(activity -> {
                AlertDialog comparison = dialog(activity);
                View decor = comparison.getWindow().getDecorView();
                focused.set(decor.isAttachedToWindow() && decor.hasWindowFocus());
                ScanPreview preview = find(decor, ScanPreview.class);
                assertNotNull(preview);
                StringBuilder layout = new StringBuilder();
                for (View view : new View[]{decor, preview,
                        comparison.getButton(AlertDialog.BUTTON_POSITIVE),
                        comparison.getButton(AlertDialog.BUTTON_NEGATIVE)}) {
                    int[] location = new int[2];
                    view.getLocationOnScreen(location);
                    layout.append(location[0]).append(',').append(location[1]).append(',')
                            .append(view.getWidth()).append(',').append(view.getHeight()).append(';');
                }
                String current = layout.toString();
                if (focused.get() && current.equals(previous[0])) stable.incrementAndGet();
                else stable.set(0);
                previous[0] = current;
            });
            if (focused.get() && stable.get() >= 3) return;
            SystemClock.sleep(150);
        } while (SystemClock.uptimeMillis() < until);
        fail("书页对比对话框布局未稳定");
    }

    private void screenshot(ActivityScenario<MainActivity> app, Context context, String name) throws Exception {
        // Another test may just have restored a page and displayed a short Toast. Let it expire
        // so acceptance evidence shows this comparison rather than the previous operation.
        if ("portrait".equals(name)) SystemClock.sleep(2300);
        awaitComparisonLayout(app);
        File folder = new File(context.getExternalFilesDir("acceptance"), "book-flow");
        ScanStore.ensureDirectory(folder);
        Bitmap capture = device.getUiAutomation().takeScreenshot();
        assertNotNull(capture);
        try (FileOutputStream output = new FileOutputStream(new File(folder, name + ".png"))) {
            assertTrue(capture.compress(Bitmap.CompressFormat.PNG,100,output));
        } finally { capture.recycle(); }
    }

    private void awaitJob(ActivityScenario<MainActivity> app) {
        long until = SystemClock.uptimeMillis() + 10000;
        AtomicBoolean completed = new AtomicBoolean();
        while (SystemClock.uptimeMillis() < until) {
            device.waitForIdleSync();
            app.onActivity(activity -> completed.set(field(activity, "job") == null));
            if (completed.get()) return;
            SystemClock.sleep(35);
        }
        fail("书页保存或恢复任务未完成");
    }

    private void clickActiveDialog(String label) {
        long until = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < until) {
            AccessibilityNodeInfo root = device.getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(label)) {
                    if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        device.waitForIdleSync();
                        return;
                    }
                }
            }
            SystemClock.sleep(35);
        }
        fail("找不到书页操作 " + label);
    }

    private static void assertExportsUseColor(Context context, ScanStore store, ScanDocument doc, int color)
            throws Exception {
        ScanDocument.Page page = doc.pages.get(0);
        Bitmap rendered = ScanImages.render(store.image(doc, page), page, 600);
        try { assertCenterColor(rendered, color); } finally { rendered.recycle(); }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ScanExport.image(store, doc, page, true, png);
        byte[] bytes = png.toByteArray();
        Bitmap exported = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        assertNotNull(exported);
        try { assertCenterColor(exported, color); } finally { exported.recycle(); }
        File pdf = File.createTempFile("book-flow-", ".pdf", context.getCacheDir());
        try {
            try (FileOutputStream output = new FileOutputStream(pdf)) {
                ScanExport.pdf(store, doc, false, output, (done,total) -> assertEquals(1, total));
            }
            try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
                    PdfRenderer reader = new PdfRenderer(descriptor);
                    PdfRenderer.Page renderedPage = reader.openPage(0)) {
                assertEquals(1, reader.getPageCount());
                Bitmap preview = Bitmap.createBitmap(300,450,Bitmap.Config.ARGB_8888);
                try {
                    preview.eraseColor(Color.WHITE);
                    renderedPage.render(preview, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    assertCenterColor(preview, color);
                } finally { preview.recycle(); }
            }
        } finally { pdf.delete(); }
    }

    private static void assertCenterColor(Bitmap image, int expected) {
        int actual = image.getPixel(image.getWidth() / 2, image.getHeight() / 2);
        assertEquals(Color.red(expected), Color.red(actual), 8);
        assertEquals(Color.green(expected), Color.green(actual), 8);
        assertEquals(Color.blue(expected), Color.blue(actual), 8);
    }

    private static AlertDialog dialog(MainActivity activity) {
        AlertDialog result = (AlertDialog) field(activity, "bookPreviewDialog");
        assertNotNull(result);
        assertTrue(result.isShowing());
        return result;
    }

    private static void click(View root, String text) {
        View target = clickable(root, text);
        assertNotNull(text, target);
        assertTrue(text, target.performClick());
    }

    private static View clickable(View view, String text) {
        if (view.isClickable() && view instanceof TextView && text.contentEquals(((TextView)view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = clickable(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T extends View> T find(View view, Class<T> kind) {
        if (kind.isInstance(view)) return kind.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = find(group.getChildAt(i), kind);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Exception failure) { throw new AssertionError(name, failure); }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception failure) { throw new AssertionError(name, failure); }
    }

    private static void invoke(Object target, String name, Class<?>[] signature, Object... values) {
        try {
            Method method = target.getClass().getDeclaredMethod(name, signature);
            method.setAccessible(true);
            method.invoke(target, values);
        } catch (Exception failure) { throw new AssertionError(name, failure); }
    }

    private static final class Fixture implements AutoCloseable {
        final Context context = ApplicationProvider.getApplicationContext();
        final SharedPreferences preferences = context.getSharedPreferences("workspace", Context.MODE_PRIVATE);
        final Map<String, ?> prior = preferences.getAll();
        final ScanStore store = new ScanStore(context);
        final ScanDocument.Page page = new ScanDocument.Page(ScanStore.newId(), 1,
                CropQuad.from(new float[]{.10f,.08f,.90f,.08f,.90f,.92f,.10f,.92f}),
                ScanDocument.Filter.ORIGINAL);
        final ScanDocument document = store.newDocument().withPages(List.of(page));
        final File originalFile = store.image(document, page);
        final byte[] originalBytes;

        Fixture() throws Exception {
            store.save(document);
            write(originalFile, Color.RED);
            originalBytes = Files.readAllBytes(originalFile.toPath());
            assertTrue(preferences.edit().putString("document",document.id).putString("page",page.id)
                    .putBoolean("crop",false).commit());
        }

        BookFlatteningPreview proposal() throws Exception {
            File candidate = File.createTempFile("book-proposal-", ".png", context.getCacheDir());
            write(candidate, Color.BLUE);
            Bitmap before = BitmapFactory.decodeFile(originalFile.getPath());
            Bitmap after = BitmapFactory.decodeFile(candidate.getPath());
            assertNotNull(before);assertNotNull(after);
            return new BookFlatteningPreview(before, after, candidate);
        }

        @Override public void close() throws Exception {
            SharedPreferences.Editor restore = preferences.edit().putString("document",(String)prior.get("document"))
                    .putString("page",(String)prior.get("page"));
            if (prior.containsKey("crop")) restore.putBoolean("crop",(Boolean)prior.get("crop"));
            else restore.remove("crop");
            assertTrue(restore.commit());
            store.delete(document);
        }

        private static void write(File file, int color) throws Exception {
            Bitmap image = Bitmap.createBitmap(280,420,Bitmap.Config.ARGB_8888);
            image.eraseColor(color);
            try (FileOutputStream output = new FileOutputStream(file)) {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,output));
            } finally { image.recycle(); }
        }
    }
}
