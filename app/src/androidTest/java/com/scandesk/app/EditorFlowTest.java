package com.scandesk.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.content.FileProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class EditorFlowTest {
    private final Instrumentation device = InstrumentationRegistry.getInstrumentation();

    @Test public void shortCornerDragKeepsItsEndpointAndIgnoresTheRemainingFinger() {
        device.runOnMainSync(() -> {
            Context context = ApplicationProvider.getApplicationContext();
            ScanUi ui = new ScanUi(context);
            ScanPreview preview = new ScanPreview(context, ui);
            new FrameLayout(context).addView(preview);
            int edge = 400 + 2 * ui.dp(12);
            preview.layout(0, 0, edge, edge);
            Bitmap page = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888);
            AtomicReference<CropQuad> crop = new AtomicReference<>(CropQuad.FULL);
            try {
                preview.show(page, crop.get(), crop::set);
                float start = ui.dp(12) + 4;
                touch(preview, MotionEvent.ACTION_DOWN, 3, start, start);
                touch(preview, MotionEvent.ACTION_UP, 3, start + 16, start + 20);
                assertEquals(.04f, crop.get().x(0), .001f);
                assertEquals(.05f, crop.get().y(0), .001f);

                touch(preview, MotionEvent.ACTION_DOWN, 3, start + 16, start + 20);
                int addSecond = MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
                touch(preview, addSecond, 3, start + 16, start + 20, 9, 280, 220);
                touch(preview, MotionEvent.ACTION_POINTER_UP, 3, start + 40, start + 32, 9, 280, 220);
                touch(preview, MotionEvent.ACTION_MOVE, 9, 255, 200);
                touch(preview, MotionEvent.ACTION_UP, 9, 251, 199);
                assertEquals(.10f, crop.get().x(0), .001f);
                assertEquals(.08f, crop.get().y(0), .001f);
            } finally {
                page.recycle();
            }
        });
    }

    @Test public void cancelledAddAndReorderPreservePageIdentityAndEachCrop() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        ScanStore store = new ScanStore(context);
        CropQuad inset = CropQuad.FULL.move(0, .12f, .16f);
        ScanDocument.Page first = new ScanDocument.Page(ScanStore.newId(), 0, inset, ScanDocument.Filter.ORIGINAL);
        ScanDocument.Page second = new ScanDocument.Page(ScanStore.newId(), 0, inset, ScanDocument.Filter.ORIGINAL);
        ScanDocument sample = store.newDocument().renamed("裁切与页序回归").withPages(List.of(first, second));
        store.save(sample);
        SharedPreferences prefs = context.getSharedPreferences("workspace", Context.MODE_PRIVATE);
        Map<String, ?> before = prefs.getAll();
        Instrumentation.ActivityResult cancelled = new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
        Instrumentation.ActivityMonitor cancelAlbum = device.addMonitor(picturePicker(), cancelled, true);
        Instrumentation.ActivityMonitor cancelCamera = device.addMonitor(CaptureActivity.class.getName(), cancelled, true);
        try {
            Bitmap bitmap = Bitmap.createBitmap(160, 240, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(0xffeeeeee);
            try {
                for (ScanDocument.Page page : sample.pages) {
                    try (FileOutputStream out = new FileOutputStream(store.image(sample, page))) {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
                    }
                }
            } finally {
                bitmap.recycle();
            }
            assertTrue(prefs.edit().putString("document", sample.id).putString("page", first.id)
                    .putBoolean("crop", false).commit());
            try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
                click(screen, sample.name + "，2 页");
                click(screen, "编辑图片");
                click(screen, "裁切");
                click(screen, "重置");
                click(screen, "第 2 页");
                ScanDocument saved = store.load(sample.id);
                assertEquals(CropQuad.FULL, saved.pages.get(0).crop);
                assertEquals(inset, saved.pages.get(1).crop);

                click(screen, "添加页面");
                clickDialog("从相册导入");
                assertEquals(1, cancelAlbum.getHits());
                click(screen, "添加页面");
                clickDialog("拍照扫描");
                assertEquals(1, cancelCamera.getHits());
                assertEquals(second.id, prefs.getString("page", null));
                assertTrue(prefs.getBoolean("crop", false));
                assertEquals(saved.revision, store.load(sample.id).revision);

                screen.onActivity(activity -> activity.getOnBackPressedDispatcher().onBackPressed());
                assertEquals(sample.id, prefs.getString("document", null));
                assertFalse(prefs.getBoolean("crop", true));
                click(screen, "页操作");
                clickDialog("向前移动");
                saved = store.load(sample.id);
                assertEquals(second.id, saved.pages.get(0).id);
                assertEquals(inset, saved.pages.get(0).crop);
                assertEquals(first.id, saved.pages.get(1).id);
                assertEquals(CropQuad.FULL, saved.pages.get(1).crop);
                assertEquals(second.id, prefs.getString("page", null));

                click(screen, "裁切");
                click(screen, "重置");
                click(screen, "保存修改");
                assertEquals(CropQuad.FULL, store.load(sample.id).pages.get(0).crop);
                assertFalse(prefs.getBoolean("crop", true));
                screen.onActivity(activity -> assertNotNull(clickable(
                        activity.findViewById(android.R.id.content), "编辑图片")));
                click(screen, "返回");
                screen.onActivity(activity -> {
                    View home = clickable(activity.findViewById(android.R.id.content), "首页");
                    assertNotNull(home);
                    assertTrue(home.isSelected());
                });
            }
        } finally {
            device.removeMonitor(cancelCamera);
            device.removeMonitor(cancelAlbum);
            restoreSelection(prefs, before);
            store.delete(sample);
        }
    }

    @Test public void galleryBatchSkipsBrokenPhotoAndKeepsFirstSuccessfulTitleAndPageOrder() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = context.getSharedPreferences("workspace", Context.MODE_PRIVATE);
        Map<String, ?> before = prefs.getAll();
        ScanStore store = new ScanStore(context);
        File exports = new File(context.getCacheDir(), "exports");
        ScanStore.ensureDirectory(exports);
        File batch = Files.createTempDirectory(exports.toPath(), "gallery-test-").toFile();
        File[] files = {new File(batch, "损坏.png"), new File(batch, "收货清单.png"), new File(batch, "补充页.png")};
        Instrumentation.ActivityMonitor album = null;
        boolean openedLibrary = false;
        try {
            Files.write(files[0].toPath(), new byte[]{1, 2, 3});
            int[] colors = {0xffb45746, 0xff4b6d94};
            Bitmap bitmap = Bitmap.createBitmap(160, 240, Bitmap.Config.ARGB_8888);
            try {
                for (int i = 0; i < colors.length; i++) {
                    bitmap.eraseColor(colors[i]);
                    try (FileOutputStream out = new FileOutputStream(files[i + 1])) {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
                    }
                }
            } finally {
                bitmap.recycle();
            }
            String authority = context.getPackageName() + ".files";
            ClipData chosen = ClipData.newRawUri("图片", FileProvider.getUriForFile(context, authority, files[0]));
            for (int i = 1; i < files.length; i++) {
                Uri uri = FileProvider.getUriForFile(context, authority, files[i]);
                chosen.addItem(new ClipData.Item(uri));
            }
            Intent result = new Intent();
            result.setClipData(chosen);
            Instrumentation.ActivityResult picked = new Instrumentation.ActivityResult(Activity.RESULT_OK, result);
            album = device.addMonitor(picturePicker(), picked, true);
            assertTrue(prefs.edit().remove("document").remove("page").remove("crop").commit());
            openedLibrary = true;
            try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
                click(screen, "从相册导入");
                clickDialog("保存文档");
                assertEquals(1, album.getHits());
                ScanDocument imported = store.load(prefs.getString("document", null));
                assertNotNull(imported);
                assertEquals("收货清单", imported.name);
                assertEquals(2, imported.pages.size());
                assertEquals(imported.pages.get(0).id, prefs.getString("page", null));
                for (int i = 0; i < colors.length; i++) {
                    Bitmap original = BitmapFactory.decodeFile(store.image(imported, imported.pages.get(i)).getPath());
                    assertNotNull(original);
                    try {
                        assertEquals(colors[i], original.getPixel(10, 10));
                    } finally {
                        original.recycle();
                    }
                }
            }
        } finally {
            if (album != null) device.removeMonitor(album);
            ScanDocument imported = openedLibrary ? store.load(prefs.getString("document", null)) : null;
            restoreSelection(prefs, before);
            if (imported != null) store.delete(imported);
            for (File file : files) file.delete();
            batch.delete();
        }
    }

    @Test public void topLevelNavigationShowsFilesAndToolsThenOpensScanDetail() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences prefs = context.getSharedPreferences("workspace", Context.MODE_PRIVATE);
        Map<String, ?> before = prefs.getAll();
        ScanStore store = new ScanStore(context);
        ScanDocument.Page page = new ScanDocument.Page(ScanStore.newId(), 0, CropQuad.FULL,
                ScanDocument.Filter.ORIGINAL);
        ScanDocument sample = store.newDocument().renamed("导航测试扫描件").withPages(List.of(page));
        try {
            store.save(sample);
            assertTrue(prefs.edit().remove("document").remove("page").remove("crop").commit());
            try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
                screen.onActivity(activity -> assertNotNull(clickable(
                        activity.findViewById(android.R.id.content), "从相册导入")));
                click(screen, "工具");
                click(screen, "提取表格");
                assertNotNull(awaitDialogNode("从已有扫描件选择"));
                clickDialog("关闭");
                click(screen, "文件");
                click(screen, sample.name + "，1 页");
                screen.onActivity(activity -> {
                    View content = activity.findViewById(android.R.id.content);
                    assertNotNull(clickable(content, "提取文字"));
                    assertNotNull(clickable(content, "提取表格"));
                    assertNull(clickable(content, "文件"));
                });
                click(screen, "返回");
                screen.onActivity(activity -> {
                    View fileTab = clickable(activity.findViewById(android.R.id.content), "文件");
                    assertNotNull(fileTab);
                    assertTrue(fileTab.isSelected());
                });
            }
        } finally {
            restoreSelection(prefs, before);
            store.delete(sample);
        }
    }

    private static IntentFilter picturePicker() throws IntentFilter.MalformedMimeTypeException {
        IntentFilter picker = new IntentFilter("android.provider.action.PICK_IMAGES");
        picker.addAction(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.addDataType("image/*");
        return picker;
    }

    private static void restoreSelection(SharedPreferences prefs, Map<String, ?> before) {
        SharedPreferences.Editor restore = prefs.edit().putString("document", (String) before.get("document"))
                .putString("page", (String) before.get("page"));
        if (before.containsKey("crop")) restore.putBoolean("crop", (boolean) before.get("crop"));
        else restore.remove("crop");
        restore.commit();
    }

    private static void click(ActivityScenario<MainActivity> screen, String label) {
        screen.onActivity(activity -> {
            View target = clickable(activity.findViewById(android.R.id.content), label);
            assertNotNull(label, target);
            assertTrue(label, target.performClick());
        });
    }

    private static View clickable(View view, String label) {
        String description = String.valueOf(view.getContentDescription());
        if (view.isClickable() && (description.equals(label) || description.startsWith(label + "，")
                || view instanceof TextView text && label.contentEquals(text.getText()))) return view;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = clickable(group.getChildAt(i), label);
                if (child != null) return child;
            }
        }
        return null;
    }

    private void clickDialog(String label) {
        AccessibilityNodeInfo item = awaitDialogNode(label);
        assertTrue(label, item.performAction(AccessibilityNodeInfo.ACTION_CLICK));
        device.waitForIdleSync();
    }

    private AccessibilityNodeInfo awaitDialogNode(String label) {
        long until = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < until) {
            AccessibilityNodeInfo item = clickableNode(device.getUiAutomation().getRootInActiveWindow(), label);
            if (item != null) return item;
            SystemClock.sleep(35);
        }
        throw new AssertionError("找不到可点击的选项 " + label);
    }

    private static AccessibilityNodeInfo clickableNode(AccessibilityNodeInfo node, String label) {
        if (node == null) return null;
        String description = String.valueOf(node.getContentDescription());
        if (node.isClickable() && (label.equals(String.valueOf(node.getText()))
                || description.equals(label) || description.startsWith(label + "，"))) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = clickableNode(node.getChild(i), label);
            if (child != null) return child;
        }
        return null;
    }

    private static void touch(ScanPreview view, int action, float... points) {
        int count = points.length / 3;
        MotionEvent.PointerProperties[] pointers = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            pointers[i] = new MotionEvent.PointerProperties();
            pointers[i].id = (int) points[i * 3];
            pointers[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i] = new MotionEvent.PointerCoords();
            coordinates[i].x = points[i * 3 + 1];
            coordinates[i].y = points[i * 3 + 2];
        }
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, count, pointers, coordinates, 0, 0,
                1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            assertTrue(view.onTouchEvent(event));
        } finally {
            event.recycle();
        }
    }
}
