package com.scandesk.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfoUnavailableException;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.ResolutionInfo;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ViewPort;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.camera.view.TransformExperimental;
import androidx.camera.view.transform.CoordinateTransform;
import androidx.camera.view.transform.ImageProxyTransformFactory;
import androidx.camera.view.transform.OutputTransform;
import androidx.annotation.OptIn;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@OptIn(markerClass = TransformExperimental.class)
public final class CaptureActivity extends ComponentActivity {
    static final String EXTRA_LIMIT = "limit";
    static final String EXTRA_MULTIPLE = "multiple";
    static final String EXTRA_FILES = "files";
    static final String EXTRA_DESTINATION = "destination";
    static final String DEST_ALBUM = "album";
    static final String DEST_PDF = "pdf";
    static final String DEST_TOOLS = "tools";

    private static final int CAMERA_SURFACE = 0xff22211e;
    private static final int FINDER_COLOR = 0xff151513;
    private static final int CAMERA_SECONDARY = 0xffcbc5ba;
    private static final long PREVIEW_TIMEOUT = 6000L;
    private final ArrayList<String> files = new ArrayList<>();
    private boolean streaming;
    private final Runnable previewTimeout = () -> {
        if (!isFinishing() && !streaming) showStatus("相机暂不可用", "重试", this::retryCamera);
    };

    private ScanUi ui;
    private File directory;
    private int limit;
    private boolean multiple;
    private boolean permissionAsked;
    private boolean connecting;
    private boolean stateSaved;
    private volatile boolean foreground;
    private boolean transferred;
    private CaptureReview review;
    private AlertDialog qualityDialog;
    private AlertDialog discardDialog;
    private ProcessCameraProvider provider;
    private Camera camera;
    private Preview cameraPreview;
    private ImageCapture imageCapture;
    private ActivityResultLauncher<String> permission;
    private PreviewView finder;
    private Grid grid;
    private ImageAnalysis imageAnalysis;
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final CaptureGuidance guidance = new CaptureGuidance();
    private final ImageProxyTransformFactory analysisTransform = new ImageProxyTransformFactory();
    private CaptureGuidanceOverlay guidanceOutline;
    private TextView guidanceMessage;
    private Button guidanceToggle;
    private volatile boolean guidanceEnabled = true;
    private volatile int guidanceGeneration;
    private boolean guidanceUnavailable;
    private long lastAnalysis;
    private final Runnable guidanceExpired = () -> {
        if (guidanceOutline != null) guidanceOutline.show(null, false);
        if (guidanceMessage != null && foreground && guidanceEnabled && streaming)
            guidanceMessage.setText("正在更新画面，请稍候");
    };
    private float focusX = Float.NaN;
    private float focusY = Float.NaN;
    private LinearLayout status;
    private TextView statusText;
    private Button statusAction;
    private Button singleMode;
    private Button multiMode;
    private TextView reviewText;
    private Button reviewAction;
    private ImageButton shutter;
    private Button tools;
    private View album;
    private View pdf;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ui = new ScanUi(this);
        directory = new File(getFilesDir(), "incoming");
        review = new ViewModelProvider(this).get(CaptureReview.class);
        limit = Math.max(0, Math.min(ScanStore.PAGE_LIMIT,
                getIntent().getIntExtra(EXTRA_LIMIT, ScanStore.PAGE_LIMIT)));
        multiple = limit > 1 && getIntent().getBooleanExtra(EXTRA_MULTIPLE, false);
        guidanceEnabled = getSharedPreferences("camera", MODE_PRIVATE).getBoolean("guidance", true);
        // Detection uses the original Y-buffer axes; PreviewView supplies rotation and mirroring.
        analysisTransform.setUsingCropRect(false);
        analysisTransform.setUsingRotationDegrees(false);
        if (saved != null) {
            restore(saved);
            String shot = saved.getString("reviewPhoto");
            if (review.file == null && validPhotoName(shot) && !files.contains(shot)) {
                File staged = new File(directory, shot);
                review.begin(staged);
                review.inspect(staged);
            }
        }
        permission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) connectCamera();
            else showPermissionStatus();
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { leave(null); }
        });
        buildUi();
        review.stage.observe(this, stage -> {
            updateControls();
            consumeReview();
        });
        updateControls();
    }

    @Override protected void onResume() {
        super.onResume();
        stateSaved = false;
        foreground = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            showStatus("此设备没有可用的相机", null, null);
        } else if (hasCameraPermission()) {
            if (imageCapture == null) connectCamera();
            else {
                startGuidance();
                onPreviewState(finder.getPreviewStreamState().getValue());
            }
        } else if (!permissionAsked) {
            requestPermission();
        } else {
            showPermissionStatus();
        }
        consumeReview();
    }

    @Override protected void onPause() {
        foreground = false;
        stopGuidance();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        finder.removeCallbacks(previewTimeout);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        stateSaved = true;
        state.putBoolean(EXTRA_MULTIPLE, multiple);
        state.putBoolean("permissionAsked", permissionAsked);
        state.putStringArrayList(EXTRA_FILES, new ArrayList<>(files));
        if (review.file != null) state.putString("reviewPhoto", review.file.getName());
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        stopGuidance();
        // Drain already delivered analyzer callbacks so their finally blocks close ImageProxy.
        // Old generations return immediately and cannot update a destroyed Activity.
        analysisExecutor.shutdown();
        finder.removeCallbacks(previewTimeout);
        if (qualityDialog != null) qualityDialog.dismiss();
        if (discardDialog != null) {
            discardDialog.setOnDismissListener(null);
            discardDialog.dismiss();
        }
        if (isFinishing() && !transferred) deleteStagedFiles();
        super.onDestroy();
    }

    private void restore(Bundle saved) {
        multiple = limit > 1 && saved.getBoolean(EXTRA_MULTIPLE);
        permissionAsked = saved.getBoolean("permissionAsked");
        ArrayList<String> paths = saved.getStringArrayList(EXTRA_FILES);
        if (paths == null) return;
        for (String name : paths) {
            if (files.size() == limit) break;
            if (validPhotoName(name)
                    && new File(directory, name).length() > 0) {
                files.add(name);
            }
        }
    }

    private static boolean validPhotoName(String name) {
        return name != null && name.endsWith(".jpg") && ScanStore.validId(name.substring(0, name.length() - 4));
    }

    private void buildUi() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(false);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightNavigationBars(false);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        LinearLayout root = ui.vertical();
        root.setBackgroundColor(CAMERA_SURFACE);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
        root.addView(header(), new LinearLayout.LayoutParams(ui.match(), ui.wrap()));
        FrameLayout preview = viewfinder();
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (landscape) {
            LinearLayout body = ui.horizontal();
            body.setGravity(Gravity.CENTER_VERTICAL);
            body.setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(10));
            body.addView(preview, new LinearLayout.LayoutParams(0, ui.match(), 1));
            LinearLayout controls = ui.vertical();
            controls.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams column = new LinearLayout.LayoutParams(ui.dp(224), ui.wrap());
            column.setMarginStart(ui.dp(12));
            controls.addView(modeControls());
            controls.addView(reviewControls());
            controls.addView(captureControls());
            body.addView(controls, column);
            root.addView(body, new LinearLayout.LayoutParams(ui.match(), 0, 1));
        } else {
            root.addView(modeControls());
            LinearLayout.LayoutParams window = new LinearLayout.LayoutParams(ui.match(), 0, 1);
            window.topMargin = ui.dp(4);
            root.addView(preview, window);
            root.addView(reviewControls());
            root.addView(captureControls());
        }
        setContentView(root);
        finder.getPreviewStreamState().observe(this, this::onPreviewState);
        finder.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (imageCapture == null && hasCameraPermission()) connectCamera();
        });
    }

    private View header() {
        LinearLayout row = ui.horizontal();
        row.setPadding(ui.dp(8), ui.dp(4), ui.dp(14), ui.dp(4));
        ImageButton close = ui.icon(R.drawable.ic_close, "关闭拍摄", () -> leave(null));
        close.setImageTintList(ColorStateList.valueOf(ScanUi.PAPER));
        row.addView(close, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        TextView title = ui.heading("拍照扫描", 20);
        title.setTextColor(ScanUi.PAPER);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setAccessibilityHeading(true);
        LinearLayout.LayoutParams titleSize = new LinearLayout.LayoutParams(0, ui.wrap(), 1);
        titleSize.setMarginStart(ui.dp(6));
        titleSize.setMarginEnd(ui.dp(8));
        row.addView(title, titleSize);
        tools = ui.quietButton("全部工具", CAMERA_SECONDARY, () -> leave(DEST_TOOLS));
        tools.setTextSize(13);
        row.addView(tools);
        return row;
    }

    private View modeControls() {
        FrameLayout outer = new FrameLayout(this);
        outer.setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(6));
        outer.setLayoutParams(new LinearLayout.LayoutParams(ui.match(), ui.wrap()));
        LinearLayout modes = ui.horizontal();
        modes.setBackground(ui.panel(CAMERA_SURFACE, 8, 0xff625e56));
        modes.setPadding(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2));
        singleMode = ui.button("单页", true, () -> setMode(false));
        multiMode = ui.button("多页", false, () -> setMode(true));
        modes.addView(singleMode, new LinearLayout.LayoutParams(ui.dp(86), ui.wrap()));
        modes.addView(multiMode, new LinearLayout.LayoutParams(ui.dp(86), ui.wrap()));
        guidanceToggle = ui.quietButton("引导", CAMERA_SECONDARY, this::toggleGuidance);
        guidanceToggle.setTextSize(13);
        guidanceToggle.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6));
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
                || getResources().getConfiguration().screenWidthDp < 340) {
            LinearLayout stacked = ui.vertical();
            stacked.setGravity(Gravity.CENTER_HORIZONTAL);
            stacked.addView(modes);
            stacked.addView(guidanceToggle);
            outer.addView(stacked, new FrameLayout.LayoutParams(ui.wrap(), ui.wrap(), Gravity.CENTER));
        } else {
            outer.addView(modes, new FrameLayout.LayoutParams(ui.wrap(), ui.wrap(), Gravity.START | Gravity.CENTER_VERTICAL));
            outer.addView(guidanceToggle, new FrameLayout.LayoutParams(ui.wrap(), ui.wrap(), Gravity.END | Gravity.CENTER_VERTICAL));
        }
        updateGuidanceToggle();
        return outer;
    }

    private FrameLayout viewfinder() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(FINDER_COLOR);
        finder = new PreviewView(this);
        finder.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        finder.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        finder.setContentDescription("相机取景区");
        frame.addView(finder, new FrameLayout.LayoutParams(ui.match(), ui.match()));
        grid = new Grid();
        grid.setVisibility(View.GONE);
        frame.addView(grid, new FrameLayout.LayoutParams(ui.match(), ui.match()));
        guidanceOutline = new CaptureGuidanceOverlay(this);
        frame.addView(guidanceOutline, new FrameLayout.LayoutParams(ui.match(), ui.match()));
        guidanceMessage = ui.text("", 13, ScanUi.PAPER);
        guidanceMessage.setGravity(Gravity.CENTER);
        guidanceMessage.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
        guidanceMessage.setBackground(ui.panel(0xb322211e, 8, 0));
        guidanceMessage.setMaxLines(2);
        guidanceMessage.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        FrameLayout.LayoutParams hint = new FrameLayout.LayoutParams(ui.wrap(), ui.wrap(), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hint.setMargins(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(14));
        frame.addView(guidanceMessage, hint);
        guidanceMessage.setVisibility(View.GONE);
        configureFocus();

        status = ui.vertical();
        status.setGravity(Gravity.CENTER);
        status.setPadding(ui.dp(18), ui.dp(8), ui.dp(18), ui.dp(8));
        statusText = ui.text("正在启动相机", 15, Color.WHITE);
        statusText.setGravity(Gravity.CENTER);
        statusText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.addView(statusText);
        statusAction = ui.button("重试", false, () -> {});
        statusAction.setVisibility(View.GONE);
        LinearLayout.LayoutParams action = new LinearLayout.LayoutParams(ui.wrap(), ui.wrap());
        action.topMargin = ui.dp(12);
        status.addView(statusAction, action);
        frame.addView(status, new FrameLayout.LayoutParams(ui.match(), ui.wrap(), Gravity.CENTER));
        return frame;
    }

    private void configureFocus() {
        finder.setOnClickListener(view -> {
            float x = Float.isNaN(focusX) ? finder.getWidth() / 2f : focusX;
            float y = Float.isNaN(focusY) ? finder.getHeight() / 2f : focusY;
            focusX = focusY = Float.NaN;
            if (camera == null || !streaming || review.file != null || !insidePreview(x, y)) return;
            MeteringPoint point = finder.getMeteringPointFactory().createPoint(x, y);
            FocusMeteringAction action = metering(point);
            if (camera.getCameraInfo().isFocusMeteringSupported(action)) {
                camera.getCameraControl().startFocusAndMetering(action);
                grid.showMeteringPoint(x, y);
            }
        });
        finder.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private boolean tap;
            private final int slop = ViewConfiguration.get(CaptureActivity.this).getScaledTouchSlop();

            @Override public boolean onTouch(View view, MotionEvent event) {
                if (!view.isClickable()) return false;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getX();
                        downY = event.getY();
                        tap = true;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(event.getX() - downX) > slop || Math.abs(event.getY() - downY) > slop)
                            tap = false;
                        break;
                    case MotionEvent.ACTION_UP:
                        if (tap) {
                            focusX = event.getX();
                            focusY = event.getY();
                            view.performClick();
                        }
                        tap = false;
                        break;
                    default:
                        tap = false;
                }
                return true;
            }
        });
        finder.setClickable(false);
    }

    private FocusMeteringAction metering(MeteringPoint point) {
        return new FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(3, TimeUnit.SECONDS).build();
    }

    private boolean insidePreview(float x, float y) {
        ResolutionInfo info = cameraPreview == null ? null : cameraPreview.getResolutionInfo();
        if (info == null) return false;
        Rect crop = info.getCropRect();
        boolean quarterTurn = info.getRotationDegrees() % 180 != 0;
        int w = quarterTurn ? crop.height() : crop.width();
        int h = quarterTurn ? crop.width() : crop.height();
        if (w <= 0 || h <= 0) return false;
        float scale = Math.min(finder.getWidth() / (float) w, finder.getHeight() / (float) h);
        return Math.abs(x - finder.getWidth() / 2f) <= w * scale / 2
                && Math.abs(y - finder.getHeight() / 2f) <= h * scale / 2;
    }

    private View reviewControls() {
        LinearLayout row = ui.horizontal();
        row.setMinimumHeight(ui.dp(47));
        row.setPadding(ui.dp(14), ui.dp(3), ui.dp(14), ui.dp(3));
        row.setLayoutParams(new LinearLayout.LayoutParams(ui.match(), ui.wrap()));
        reviewText = ui.text("", 13, CAMERA_SECONDARY);
        reviewText.setGravity(Gravity.CENTER);
        reviewText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        row.addView(reviewText, new LinearLayout.LayoutParams(0, ui.wrap(), 1));
        reviewAction = ui.button("编辑", true, this::deliverFiles);
        reviewAction.setTextColor(ScanUi.INK);
        reviewAction.setBackground(ui.ripple(ScanUi.WHITE, 8, 0));
        row.addView(reviewAction, new LinearLayout.LayoutParams(ui.wrap(), ui.wrap()));
        return row;
    }

    private View captureControls() {
        LinearLayout dock = ui.horizontal();
        dock.setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(12));
        dock.setLayoutParams(new LinearLayout.LayoutParams(ui.match(), ui.wrap()));
        album = ui.tool(R.drawable.ic_gallery, "相册", false, ScanUi.PAPER, () -> leave(DEST_ALBUM));
        album.setContentDescription("相册导入");
        album.setMinimumHeight(ui.dp(52));
        dock.addView(album, new LinearLayout.LayoutParams(0, ui.wrap(), 1));

        shutter = ui.icon(R.drawable.ic_camera, "拍照", this::takePhoto);
        shutter.setImageDrawable(null);
        GradientDrawable rim = new GradientDrawable();
        rim.setShape(GradientDrawable.OVAL);
        rim.setColor(CAMERA_SURFACE);
        rim.setStroke(ui.dp(2), ScanUi.WHITE);
        GradientDrawable fill = new GradientDrawable();
        fill.setShape(GradientDrawable.OVAL);
        fill.setColor(ScanUi.WHITE);
        LayerDrawable circle = new LayerDrawable(new GradientDrawable[]{rim, fill});
        int inset = ui.dp(5);
        circle.setLayerInset(1, inset, inset, inset, inset);
        shutter.setBackground(new RippleDrawable(ColorStateList.valueOf(0x26000000), circle, null));
        dock.addView(shutter, new LinearLayout.LayoutParams(ui.dp(68), ui.dp(68)));

        pdf = ui.tool(R.drawable.ic_pdf, "PDF", false, ScanUi.PAPER, () -> leave(DEST_PDF));
        pdf.setContentDescription("PDF 导入");
        pdf.setMinimumHeight(ui.dp(52));
        dock.addView(pdf, new LinearLayout.LayoutParams(0, ui.wrap(), 1));
        return dock;
    }

    private void setMode(boolean value) {
        if (!files.isEmpty() || review.file != null || value && limit < 2) return;
        multiple = value;
        updateControls();
    }

    private void updateControls() {
        boolean pending = review.file != null;
        applyMode(singleMode, !multiple, !pending && files.isEmpty());
        applyMode(multiMode, multiple, !pending && files.isEmpty() && limit > 1);
        boolean ready = streaming && imageCapture != null && !pending && files.size() < limit;
        shutter.setEnabled(ready);
        shutter.setAlpha(ready ? 1f : .48f);
        shutter.setContentDescription(pending ? "正在保存照片" : "拍照");
        for (View shortcut : new View[]{tools, album, pdf}) {
            shortcut.setEnabled(!pending);
            shortcut.setAlpha(pending ? .48f : 1f);
        }
        reviewAction.setVisibility(files.isEmpty() ? View.GONE : View.VISIBLE);
        reviewAction.setEnabled(!pending);
        reviewAction.setAlpha(pending ? .6f : 1f);
        reviewAction.setContentDescription("编辑 " + files.size() + " 页");
        reviewText.setGravity(files.isEmpty() ? Gravity.CENTER : Gravity.START | Gravity.CENTER_VERTICAL);
        if (pending) reviewText.setText(review.stage.getValue() == CaptureReview.Stage.WRITING
                ? "正在保存照片" : "正在检查照片");
        else if (!files.isEmpty()) reviewText.setText("已拍 " + files.size() + " 页");
        else if (limit == 0) reviewText.setText("扫描件页数已满");
        else reviewText.setText(streaming && (!guidanceEnabled || guidanceUnavailable)
                ? "让纸张四角留在取景区内" : "");
        updateGuidanceVisibility();
    }

    private void applyMode(Button button, boolean selected, boolean enabled) {
        button.setSelected(selected);
        button.setEnabled(enabled);
        button.setTextColor(selected ? ScanUi.INK : CAMERA_SECONDARY);
        button.setAlpha(enabled || selected ? 1f : .52f);
        button.setBackground(ui.ripple(selected ? ScanUi.WHITE : 0, 6, 0));
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermission() {
        permissionAsked = true;
        permission.launch(Manifest.permission.CAMERA);
    }

    private void showPermissionStatus() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            showStatus("允许相机权限后即可拍照", "授权相机", this::requestPermission);
        } else {
            showStatus("允许相机权限后即可拍照", "设置权限", () -> {
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", getPackageName(), null)));
                } catch (ActivityNotFoundException unavailable) {
                    toast("请在系统设置中开启相机权限");
                }
            });
        }
    }

    private void connectCamera() {
        if (connecting || imageCapture != null || isFinishing() || !hasCameraPermission()
                || !getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
                || finder.getViewPort() == null) return;
        connecting = true;
        showStatus("正在启动相机", null, null);
        ListenableFuture<ProcessCameraProvider> future;
        try {
            future = ProcessCameraProvider.getInstance(this);
        } catch (RuntimeException failure) {
            connecting = false;
            showStatus("相机暂不可用", "重试", this::retryCamera);
            return;
        }
        future.addListener(() -> {
            connecting = false;
            if (isFinishing() || isDestroyed() || !hasCameraPermission()) return;
            try {
                bindCamera(future.get());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                showStatus("相机启动中断", "重试", this::retryCamera);
            } catch (ExecutionException | CameraInfoUnavailableException | RuntimeException failure) {
                showStatus("相机暂不可用", "重试", this::retryCamera);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCamera(ProcessCameraProvider available) throws CameraInfoUnavailableException {
        CameraSelector selector = CameraSelector.DEFAULT_BACK_CAMERA;
        if (!available.hasCamera(selector)) selector = CameraSelector.DEFAULT_FRONT_CAMERA;
        if (!available.hasCamera(selector)) {
            showStatus("此设备没有可用的相机", null, null);
            return;
        }
        ViewPort viewport = finder.getViewPort();
        if (viewport == null || finder.getDisplay() == null) return;
        int rotation = finder.getDisplay().getRotation();
        Preview preview = new Preview.Builder().setTargetRotation(rotation).build();
        ImageCapture capture = new ImageCapture.Builder().setTargetRotation(rotation)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).setJpegQuality(94).build();
        preview.setSurfaceProvider(finder.getSurfaceProvider());
        ImageAnalysis analysis = new ImageAnalysis.Builder().setTargetRotation(rotation)
                .setTargetResolution(new android.util.Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
        guidanceUnavailable = false;
        try {
            camera = available.bindToLifecycle(this, selector, new UseCaseGroup.Builder().setViewPort(viewport)
                    .addUseCase(preview).addUseCase(capture).addUseCase(analysis).build());
            imageAnalysis = analysis;
        } catch (IllegalArgumentException unsupportedCombination) {
            analysis.clearAnalyzer();
            available.unbind(preview, capture, analysis);
            camera = available.bindToLifecycle(this, selector, new UseCaseGroup.Builder().setViewPort(viewport)
                    .addUseCase(preview).addUseCase(capture).build());
            imageAnalysis = null;
            guidanceUnavailable = true;
        }
        provider = available;
        cameraPreview = preview;
        imageCapture = capture;
        startGuidance();
        onPreviewState(finder.getPreviewStreamState().getValue());
    }

    private void retryCamera() {
        if (connecting) return;
        stopGuidance();
        if (provider != null && imageCapture != null) {
            if (imageAnalysis != null) provider.unbind(cameraPreview, imageCapture, imageAnalysis);
            else provider.unbind(cameraPreview, imageCapture);
        }
        provider = null;
        camera = null;
        cameraPreview = null;
        imageCapture = null;
        imageAnalysis = null;
        streaming = false;
        connectCamera();
        updateControls();
    }

    private void toggleGuidance() {
        guidanceEnabled = !guidanceEnabled;
        getSharedPreferences("camera", MODE_PRIVATE).edit().putBoolean("guidance", guidanceEnabled).apply();
        if (guidanceEnabled) startGuidance(); else stopGuidance();
        updateGuidanceToggle();
        updateControls();
    }

    private void updateGuidanceToggle() {
        if (guidanceToggle == null) return;
        guidanceToggle.setSelected(guidanceEnabled);
        guidanceToggle.setText(guidanceEnabled ? "引导 开" : "引导 关");
        guidanceToggle.setContentDescription(guidanceEnabled ? "关闭拍前引导" : "开启拍前引导");
        guidanceToggle.setTextColor(guidanceEnabled ? ScanUi.PAPER : CAMERA_SECONDARY);
    }

    private void updateGuidanceVisibility() {
        if (guidanceMessage == null) return;
        boolean visible = foreground && streaming && guidanceEnabled && review.file == null;
        guidanceOutline.setVisibility(visible ? View.VISIBLE : View.GONE);
        guidanceMessage.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) guidanceOutline.show(null, false);
        else if (guidanceUnavailable) guidanceMessage.setText("此相机暂不支持实时引导，可手动拍摄");
        else if (guidanceMessage.length() == 0) guidanceMessage.setText("让纸张四角留在画面内");
    }

    private void startGuidance() {
        if (imageAnalysis == null || !guidanceEnabled || !foreground || analysisExecutor.isShutdown()) return;
        finder.removeCallbacks(guidanceExpired);
        guidanceOutline.show(null, false);
        guidanceMessage.setText(CaptureGuidance.Issue.SEARCHING.message);
        int generation = ++guidanceGeneration;
        analysisExecutor.execute(() -> { guidance.reset(); lastAnalysis = 0; });
        imageAnalysis.setAnalyzer(analysisExecutor, proxy -> analyzePreview(proxy, generation));
    }

    private void stopGuidance() {
        guidanceGeneration++;
        if (finder != null) finder.removeCallbacks(guidanceExpired);
        if (imageAnalysis != null) imageAnalysis.clearAnalyzer();
        if (guidanceOutline != null) guidanceOutline.show(null, false);
        if (guidanceMessage != null) {
            guidanceMessage.setText("");
            guidanceMessage.setVisibility(View.GONE);
        }
    }

    private void analyzePreview(ImageProxy proxy, int generation) {
        Bitmap frame = null;
        try {
            long now = SystemClock.elapsedRealtime();
            if (generation != guidanceGeneration || !guidanceEnabled || !foreground
                    || now - lastAnalysis < 350 || Thread.currentThread().isInterrupted()) return;
            lastAnalysis = now;
            ImageProxy.PlaneProxy y = proxy.getPlanes()[0];
            Rect crop = new Rect(proxy.getCropRect());
            frame = CaptureGuidance.lumaFrame(y.getBuffer(), proxy.getWidth(), proxy.getHeight(),
                    y.getRowStride(), y.getPixelStride(), crop, 640);
            CaptureGuidance.Result result = guidance.analyze(frame, now);
            OutputTransform source = analysisTransform.getOutputTransform(proxy);
            finder.post(() -> {
                if (generation != guidanceGeneration || !guidanceEnabled || !foreground || isDestroyed()
                        || SystemClock.elapsedRealtime() - now > 1500) return;
                OutputTransform target = finder.getOutputTransform();
                if (target == null) return;
                try {
                    Matrix mapping = new Matrix();
                    new CoordinateTransform(source, target).transform(mapping);
                    float[] points = CaptureGuidance.previewPoints(result.paper, crop, mapping);
                    guidanceOutline.show(points, result.issue == CaptureGuidance.Issue.READY);
                    if (!result.issue.message.contentEquals(guidanceMessage.getText()))
                        guidanceMessage.setText(result.issue.message);
                    finder.removeCallbacks(guidanceExpired);
                    finder.postDelayed(guidanceExpired, 1500);
                    updateGuidanceVisibility();
                } catch (IllegalArgumentException | IllegalStateException staleTransform) {
                    guidanceOutline.show(null, false);
                }
            });
        } catch (IOException | RuntimeException unavailable) {
            finder.post(() -> {
                if (generation != guidanceGeneration || isDestroyed()) return;
                guidanceOutline.show(null, false);
                guidanceMessage.setText("对照取景画面，保留纸张四角");
            });
        } finally {
            if (frame != null) frame.recycle();
            proxy.close();
        }
    }

    private void onPreviewState(PreviewView.StreamState current) {
        if (imageCapture == null) return;
        streaming = current == PreviewView.StreamState.STREAMING;
        boolean meteringAvailable = streaming && camera != null && camera.getCameraInfo().isFocusMeteringSupported(
                metering(finder.getMeteringPointFactory().createPoint(finder.getWidth() / 2f, finder.getHeight() / 2f)));
        finder.setClickable(meteringAvailable);
        finder.setFocusable(meteringAvailable);
        finder.setContentDescription(meteringAvailable ? "相机取景区，点按设置对焦或测光位置" : "相机取景区");
        finder.removeCallbacks(previewTimeout);
        grid.setVisibility(streaming ? View.VISIBLE : View.GONE);
        if (streaming) status.setVisibility(View.GONE);
        else {
            showStatus("正在启动相机", null, null);
            finder.postDelayed(previewTimeout, PREVIEW_TIMEOUT);
        }
        updateControls();
    }

    private void showStatus(String text, String action, Runnable task) {
        status.setVisibility(View.VISIBLE);
        statusText.setText(text);
        statusAction.setVisibility(task == null ? View.GONE : View.VISIBLE);
        statusAction.setText(action);
        statusAction.setOnClickListener(task == null ? null : view -> task.run());
    }

    private void takePhoto() {
        if (!streaming || imageCapture == null || review.file != null || files.size() >= limit) return;
        try {
            ScanStore.ensureDirectory(directory);
        } catch (IOException failure) {
            toast("无法保存照片，请检查本机存储空间");
            return;
        }
        File file = new File(directory, ScanStore.newId() + ".jpg");
        review.begin(file);
        try {
            imageCapture.takePicture(new ImageCapture.OutputFileOptions.Builder(file).build(),
                    ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
                        @Override public void onImageSaved(ImageCapture.OutputFileResults result) {
                            review.inspect(file);
                        }

                        @Override public void onError(ImageCaptureException failure) { review.fail(file); }
                    });
        } catch (RuntimeException failure) {
            review.fail(file);
        }
    }

    private void consumeReview() {
        if (!foreground || stateSaved || isFinishing() || isDestroyed() || discardDialog != null) return;
        if (review.stage.getValue() == CaptureReview.Stage.FAILED) {
            review.clear(false);
            toast("照片保存失败，请重拍");
            return;
        }
        if (review.stage.getValue() != CaptureReview.Stage.READY || qualityDialog != null) return;
        if (review.issue == CaptureQuality.Issue.NONE) {
            acceptShot();
            return;
        }
        boolean dark = review.issue == CaptureQuality.Issue.DARK;
        ImageView sample = new ImageView(this);
        sample.setAdjustViewBounds(true);
        sample.setMaxHeight(Math.min(ui.dp(220), getResources().getDisplayMetrics().heightPixels / 4));
        sample.setScaleType(ImageView.ScaleType.FIT_CENTER);
        sample.setImageBitmap(review.preview);
        sample.setContentDescription("刚拍摄的第 " + (files.size() + 1) + " 页");
        qualityDialog = new AlertDialog.Builder(this)
                .setTitle(dark ? "这页光线较暗" : "这页文字可能模糊")
                .setMessage(dark ? "可以移到光线均匀的位置再拍。" : "请拿稳手机，轻点文字后再拍。")
                .setView(sample)
                .setNegativeButton("重拍这页", (dialog, which) -> review.clear(true))
                .setPositiveButton("使用这张", (dialog, which) -> acceptShot())
                .setCancelable(false)
                .create();
        qualityDialog.setOnDismissListener(dialog -> qualityDialog = null);
        qualityDialog.show();
    }

    private void acceptShot() {
        if (review.file == null || review.stage.getValue() != CaptureReview.Stage.READY) return;
        files.add(review.file.getName());
        review.clear(false);
        if (!multiple) deliverFiles();
    }

    private void deliverFiles() {
        if (files.isEmpty() || review.file != null || isFinishing()) return;
        transferred = true;
        setResult(RESULT_OK, new Intent().putStringArrayListExtra(EXTRA_FILES, new ArrayList<>(files)));
        finish();
    }

    private void leave(String destination) {
        if (isFinishing() || discardDialog != null) return;
        if (files.isEmpty() && review.file == null) {
            finishTo(destination);
            return;
        }
        int count = files.size() + (review.file == null ? 0 : 1);
        discardDialog = new AlertDialog.Builder(this).setTitle("放弃本次拍摄？")
                .setMessage("本次拍摄的 " + count + " 页将被清除。")
                .setNegativeButton("保留照片", null)
                .setPositiveButton("放弃", (dialog, which) -> finishTo(destination))
                .create();
        discardDialog.setOnDismissListener(dialog -> {
            discardDialog = null;
            consumeReview();
        });
        discardDialog.show();
    }

    private void finishTo(String destination) {
        deleteStagedFiles();
        if (destination != null) setResult(RESULT_OK, new Intent().putExtra(EXTRA_DESTINATION, destination));
        finish();
    }

    private void deleteStagedFiles() {
        for (String name : files) new File(directory, name).delete();
        files.clear();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private final class Grid extends View {
        private final Paint paint = new Paint();
        private final RectF focusRect = new RectF();
        private final Runnable hidePoint = () -> { focusRect.setEmpty(); invalidate(); };

        Grid() {
            super(CaptureActivity.this);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            paint.setColor(0x48ffffff);
            paint.setStrokeWidth(Math.max(1, ui.dp(1)));
        }

        void showMeteringPoint(float x, float y) {
            float half = ui.dp(19);
            focusRect.set(x - half, y - half, x + half, y + half);
            removeCallbacks(hidePoint);
            postDelayed(hidePoint, 1050);
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float width = getWidth();
            float height = getHeight();
            paint.setColor(0x48ffffff);
            for (int mark = 1; mark < 3; mark++) {
                float x = width * mark / 3;
                float y = height * mark / 3;
                canvas.drawLine(x, 0, x, height, paint);
                canvas.drawLine(0, y, width, y, paint);
            }
            if (!focusRect.isEmpty()) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(ScanUi.PAPER);
                canvas.drawRoundRect(focusRect, ui.dp(4), ui.dp(4), paint);
            }
        }
    }
}
