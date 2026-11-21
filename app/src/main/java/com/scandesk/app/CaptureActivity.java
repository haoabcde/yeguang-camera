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
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
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
import android.widget.HorizontalScrollView;
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
import androidx.camera.core.TorchState;
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
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@OptIn(markerClass = TransformExperimental.class)
public final class CaptureActivity extends ComponentActivity {
    static final String EXTRA_LIMIT = "limit";
    static final String EXTRA_MULTIPLE = "multiple";
    static final String EXTRA_WORK_MODE = "work_mode";
    static final String EXTRA_HANDWRITING = "handwriting";
    static final String EXTRA_INITIAL_PAGE_COUNT = "initial_page_count";
    static final String EXTRA_EXISTING_THUMBNAIL = "existing_thumbnail";
    static final String EXTRA_FILES = "files";
    static final String EXTRA_DESTINATION = "destination";
    static final String DEST_ALBUM = "album";
    static final String DEST_PDF = "pdf";
    static final String DEST_TOOLS = "tools";

    private static final int CAMERA_SURFACE = 0xff211f1c;
    private static final int FINDER_COLOR = 0xff151410;
    private static final int CAMERA_TEXT = 0xfff7f2e9;
    private static final int CAMERA_SECONDARY = 0xffbbb2a6;
    private static final int CAMERA_ACCENT = 0xffe3b59c;
    private static final long PREVIEW_TIMEOUT = 6000L;
    private final ArrayList<String> files = new ArrayList<>();
    private boolean streaming;
    private final Runnable previewTimeout = () -> {
        if (!isFinishing() && !streaming) showStatus("相机暂不可用", "重试", this::retryCamera);
    };

    private ScanUi ui;
    private File directory;
    private int limit;
    private int initialPageCount;
    private File existingThumbnail;
    private CaptureWorkMode workMode;
    private boolean handwrittenText;
    private boolean handwrittenTable;
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
    private Button torchToggle;
    private ListenableFuture<Void> torchChange;
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
    private HorizontalScrollView modeScroller;
    private final Button[] modeButtons = new Button[CaptureWorkMode.values().length];
    private LinearLayout typeControls;
    private Button printedType;
    private Button handwrittenType;
    private TextView reviewText;
    private View reviewAction;
    private ImageView reviewThumbnail;
    private TextView reviewCount;
    private TextView reviewLabel;
    private File thumbnailFile;
    private ImageButton shutter;
    private Button tools;
    private View album;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ui = new ScanUi(this);
        directory = new File(getFilesDir(), "incoming");
        review = new ViewModelProvider(this).get(CaptureReview.class);
        initialPageCount = Math.max(0, Math.min(ScanStore.PAGE_LIMIT,
                getIntent().getIntExtra(EXTRA_INITIAL_PAGE_COUNT, 0)));
        limit = Math.max(0, Math.min(ScanStore.PAGE_LIMIT - initialPageCount,
                getIntent().getIntExtra(EXTRA_LIMIT, ScanStore.PAGE_LIMIT)));
        String thumbnailPath = getIntent().getStringExtra(EXTRA_EXISTING_THUMBNAIL);
        if (initialPageCount > 0 && thumbnailPath != null) existingThumbnail = new File(thumbnailPath);
        workMode = CaptureWorkMode.fromValue(getIntent().getStringExtra(EXTRA_WORK_MODE));
        if (getIntent().getBooleanExtra(EXTRA_HANDWRITING, false)) {
            handwrittenText = workMode == CaptureWorkMode.TEXT;
            handwrittenTable = workMode == CaptureWorkMode.TABLE;
        }
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
        releaseTorch();
        stopGuidance();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        finder.removeCallbacks(previewTimeout);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        stateSaved = true;
        state.putString(EXTRA_WORK_MODE, workMode.value);
        state.putBoolean("handwrittenText", handwrittenText);
        state.putBoolean("handwrittenTable", handwrittenTable);
        state.putBoolean("permissionAsked", permissionAsked);
        state.putStringArrayList(EXTRA_FILES, new ArrayList<>(files));
        if (review.file != null && review.stage.getValue() != CaptureReview.Stage.WRITING)
            state.putString("reviewPhoto", review.file.getName());
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
        reviewThumbnail.setImageDrawable(null);
        if (isFinishing() && !transferred) deleteStagedFiles();
        super.onDestroy();
    }

    private void restore(Bundle saved) {
        if (saved.containsKey(EXTRA_WORK_MODE))
            workMode = CaptureWorkMode.fromValue(saved.getString(EXTRA_WORK_MODE));
        handwrittenText = saved.getBoolean("handwrittenText", handwrittenText);
        handwrittenTable = saved.getBoolean("handwrittenTable", handwrittenTable);
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
            controls.addView(captureControls());
            body.addView(controls, column);
            root.addView(body, new LinearLayout.LayoutParams(ui.match(), 0, 1));
        } else {
            LinearLayout.LayoutParams window = new LinearLayout.LayoutParams(ui.match(), 0, 1);
            window.topMargin = ui.dp(1);
            root.addView(preview, window);
            root.addView(modeControls());
            root.addView(captureControls());
        }
        setContentView(root);
        revealSelectedMode();
        finder.getPreviewStreamState().observe(this, this::onPreviewState);
        finder.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (imageCapture == null && hasCameraPermission()) connectCamera();
        });
    }

    private View header() {
        LinearLayout row = ui.horizontal();
        row.setPadding(ui.dp(8), ui.dp(1), ui.dp(10), ui.dp(1));
        ImageButton close = ui.icon(R.drawable.ic_close, "关闭拍摄", () -> leave(null));
        close.setImageTintList(ColorStateList.valueOf(CAMERA_SECONDARY));
        close.setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14));
        row.addView(close, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        TextView title = ui.text("拍照扫描", 17, CAMERA_TEXT);
        title.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false));
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setAccessibilityHeading(true);
        LinearLayout.LayoutParams titleSize = new LinearLayout.LayoutParams(0, ui.wrap(), 1);
        titleSize.setMarginStart(ui.dp(3));
        titleSize.setMarginEnd(ui.dp(8));
        row.addView(title, titleSize);
        torchToggle = ui.quietButton("补光", CAMERA_SECONDARY, this::toggleTorch);
        styleHeaderAction(torchToggle);
        torchToggle.setVisibility(View.GONE);
        row.addView(torchToggle);
        guidanceToggle = ui.quietButton("引导", CAMERA_SECONDARY, this::toggleGuidance);
        styleHeaderAction(guidanceToggle);
        row.addView(guidanceToggle);
        updateGuidanceToggle();
        tools = ui.quietButton("更多工具", CAMERA_SECONDARY, () -> leave(DEST_TOOLS));
        styleHeaderAction(tools);
        row.addView(tools);
        return row;
    }

    private void styleHeaderAction(Button button) {
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT);
        button.setPadding(ui.dp(6), ui.dp(5), ui.dp(6), ui.dp(5));
    }

    private View modeControls() {
        LinearLayout section = ui.vertical();
        section.setPadding(ui.dp(8), 0, ui.dp(8), ui.dp(1));
        section.setLayoutParams(new LinearLayout.LayoutParams(ui.match(), ui.wrap()));

        modeScroller = new HorizontalScrollView(this);
        modeScroller.setFillViewport(true);
        modeScroller.setHorizontalScrollBarEnabled(false);
        modeScroller.setContentDescription("拍摄模式");
        LinearLayout modes = ui.horizontal();
        modes.setGravity(Gravity.CENTER_VERTICAL);
        CaptureWorkMode[] choices = CaptureWorkMode.values();
        for (int i = 0; i < choices.length; i++) {
            CaptureWorkMode choice = choices[i];
            Button button = ui.quietButton(choice.label, CAMERA_SECONDARY, () -> setMode(choice));
            button.setTextSize(15);
            button.setSingleLine();
            button.setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4));
            modeButtons[i] = button;
            modes.addView(button, new LinearLayout.LayoutParams(ui.wrap(), ui.wrap(), 1));
        }
        modeScroller.addView(modes);
        section.addView(modeScroller, new LinearLayout.LayoutParams(ui.match(), ui.wrap()));

        typeControls = ui.horizontal();
        typeControls.setGravity(Gravity.CENTER);
        printedType = ui.quietButton("印刷", CAMERA_SECONDARY, () -> setHandwriting(false));
        handwrittenType = ui.quietButton("手写", CAMERA_SECONDARY, () -> setHandwriting(true));
        for (Button button : new Button[]{printedType, handwrittenType}) {
            button.setTextSize(13);
            button.setPadding(ui.dp(13), ui.dp(4), ui.dp(13), ui.dp(4));
            typeControls.addView(button);
        }
        section.addView(typeControls, new LinearLayout.LayoutParams(ui.match(), ui.wrap()));
        return section;
    }

    private void revealSelectedMode() {
        modeScroller.post(() -> {
            View selected = modeButtons[workMode.ordinal()];
            int offset = selected.getLeft() + (selected.getWidth() - modeScroller.getWidth()) / 2;
            modeScroller.smoothScrollTo(Math.max(0, offset), 0);
        });
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
        guidanceMessage = ui.text("", 13, CAMERA_TEXT);
        guidanceMessage.setGravity(Gravity.CENTER);
        guidanceMessage.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6));
        guidanceMessage.setBackground(ui.panel(0xd91f1d1a, 10, 0));
        guidanceMessage.setMaxLines(2);
        guidanceMessage.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        FrameLayout.LayoutParams hint = new FrameLayout.LayoutParams(ui.wrap(), ui.wrap(), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hint.setMargins(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(14));
        frame.addView(guidanceMessage, hint);
        guidanceMessage.setVisibility(View.GONE);

        reviewText = ui.text("", 13, CAMERA_TEXT);
        reviewText.setGravity(Gravity.CENTER);
        reviewText.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6));
        reviewText.setBackground(ui.panel(0xd91f1d1a, 10, 0));
        reviewText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        FrameLayout.LayoutParams feedback = new FrameLayout.LayoutParams(ui.wrap(), ui.wrap(),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        feedback.setMargins(ui.dp(16), ui.dp(12), ui.dp(16), 0);
        frame.addView(reviewText, feedback);
        reviewText.setVisibility(View.GONE);
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
        RectF bounds = new RectF();
        previewBounds(bounds, finder.getWidth(), finder.getHeight());
        return bounds.contains(x, y);
    }

    private void previewBounds(RectF bounds, float width, float height) {
        ResolutionInfo info = cameraPreview == null ? null : cameraPreview.getResolutionInfo();
        if (info == null) {
            bounds.setEmpty();
            return;
        }
        Rect crop = info.getCropRect();
        boolean quarterTurn = info.getRotationDegrees() % 180 != 0;
        int w = quarterTurn ? crop.height() : crop.width();
        int h = quarterTurn ? crop.width() : crop.height();
        if (w <= 0 || h <= 0) {
            bounds.setEmpty();
            return;
        }
        float scale = Math.min(width / w, height / h);
        float halfWidth = w * scale / 2, halfHeight = h * scale / 2;
        bounds.set(width / 2 - halfWidth, height / 2 - halfHeight,
                width / 2 + halfWidth, height / 2 + halfHeight);
    }

    private View captureControls() {
        LinearLayout dock = ui.horizontal();
        dock.setPadding(ui.dp(19), ui.dp(5), ui.dp(19), ui.dp(10));
        dock.setLayoutParams(new LinearLayout.LayoutParams(ui.match(), ui.wrap()));
        album = ui.tool(R.drawable.ic_gallery, "相册", false, CAMERA_SECONDARY, () -> leave(DEST_ALBUM));
        album.setContentDescription("相册导入");
        album.setMinimumHeight(ui.dp(52));
        dock.addView(album, new LinearLayout.LayoutParams(0, ui.wrap(), 1));

        shutter = ui.icon(R.drawable.ic_camera, "拍照", this::takePhoto);
        shutter.setImageDrawable(null);
        GradientDrawable rim = new GradientDrawable();
        rim.setShape(GradientDrawable.OVAL);
        rim.setColor(CAMERA_SURFACE);
        rim.setStroke(Math.max(1, ui.dp(1.25f)), CAMERA_TEXT);
        GradientDrawable fill = new GradientDrawable();
        fill.setShape(GradientDrawable.OVAL);
        fill.setColor(CAMERA_TEXT);
        LayerDrawable circle = new LayerDrawable(new GradientDrawable[]{rim, fill});
        int inset = ui.dp(6);
        circle.setLayerInset(1, inset, inset, inset, inset);
        shutter.setBackground(new RippleDrawable(ColorStateList.valueOf(0x26000000), circle, null));
        dock.addView(shutter, new LinearLayout.LayoutParams(ui.dp(62), ui.dp(62)));

        LinearLayout batch = ui.vertical();
        batch.setGravity(Gravity.CENTER);
        batch.setMinimumHeight(ui.dp(60));
        batch.setBackground(ui.ripple(0, 8, 0));
        batch.setOnClickListener(view -> deliverFiles(null));
        batch.setFocusable(true);
        reviewAction = batch;

        FrameLayout cover = new FrameLayout(this);
        cover.setBackground(ui.panel(0xff26231f, 7, 0xff544d44));
        cover.setClipToOutline(true);
        reviewThumbnail = new ImageView(this);
        reviewThumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.addView(reviewThumbnail, new FrameLayout.LayoutParams(ui.match(), ui.match()));
        reviewCount = ui.text("0", 11, CAMERA_TEXT);
        reviewCount.setGravity(Gravity.CENTER);
        reviewCount.setPadding(ui.dp(4), ui.dp(1), ui.dp(4), ui.dp(1));
        reviewCount.setBackground(ui.panel(0xe3211f1c, 4, 0));
        FrameLayout.LayoutParams badge = new FrameLayout.LayoutParams(ui.wrap(), ui.wrap(),
                Gravity.BOTTOM | Gravity.END);
        badge.bottomMargin = ui.dp(2);
        badge.setMarginEnd(ui.dp(2));
        cover.addView(reviewCount, badge);
        batch.addView(cover, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(35)));
        reviewLabel = ui.text("编辑", 12, CAMERA_SECONDARY);
        reviewLabel.setGravity(Gravity.CENTER);
        reviewLabel.setPadding(0, ui.dp(3), 0, 0);
        batch.addView(reviewLabel);
        batch.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        reviewLabel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        dock.addView(batch, new LinearLayout.LayoutParams(0, ui.wrap(), 1));
        return dock;
    }

    private void setMode(CaptureWorkMode choice) {
        if (choice == workMode || review.file != null) return;
        if (pageCount() > choice.maximumPages) {
            toast(choice.label + "最多使用 " + choice.maximumPages + " 张，当前批次已保留");
            return;
        }
        stopGuidance();
        workMode = choice;
        startGuidance();
        updateControls();
        revealSelectedMode();
        if (pageCount() > 0) toast("将用当前 " + pageCount() + " 张执行" + workMode.label);
    }

    private void setHandwriting(boolean value) {
        if (review.file != null) return;
        if (workMode == CaptureWorkMode.TEXT) handwrittenText = value;
        else if (workMode == CaptureWorkMode.TABLE) handwrittenTable = value;
        updateControls();
    }

    private boolean handwriting() {
        return workMode == CaptureWorkMode.TEXT && handwrittenText
                || workMode == CaptureWorkMode.TABLE && handwrittenTable;
    }

    private int pageCount() {
        return initialPageCount + files.size();
    }

    private int pageLimit() {
        return Math.min(initialPageCount + limit, workMode.maximumPages);
    }

    private String framingHint() {
        if (workMode == CaptureWorkMode.ID) {
            if (pageCount() >= 2) return "正面、反面已拍";
            return pageCount() == 0 ? "拍摄正面 · 将证件对齐框线" : "正面已拍 · 将反面对齐框线";
        }
        if (workMode == CaptureWorkMode.QR)
            return pageCount() == 0 ? "将二维码放入框内，拍摄后读取" : "二维码照片已拍";
        return workMode.hint;
    }

    private void updateControls() {
        boolean pending = review.file != null;
        for (CaptureWorkMode mode : CaptureWorkMode.values())
            applyMode(modeButtons[mode.ordinal()], workMode == mode, !pending);
        boolean chooseType = workMode == CaptureWorkMode.TEXT || workMode == CaptureWorkMode.TABLE;
        typeControls.setVisibility(chooseType ? View.VISIBLE : View.GONE);
        handwrittenType.setText(workMode == CaptureWorkMode.TABLE ? "手写或混合" : "手写");
        applyMode(printedType, !handwriting(), !pending);
        applyMode(handwrittenType, handwriting(), !pending);
        boolean ready = streaming && imageCapture != null && !pending && pageCount() < pageLimit();
        shutter.setEnabled(ready);
        shutter.setAlpha(ready ? 1f : .48f);
        shutter.setContentDescription(pending ? "正在保存照片" : "拍照");
        tools.setEnabled(!pending);
        tools.setAlpha(pending ? .48f : 1f);
        album.setEnabled(!pending && pageCount() < pageLimit());
        album.setAlpha(album.isEnabled() ? 1f : .48f);
        int count = pageCount();
        boolean reviewReady = count > 0 && !pending;
        reviewAction.setEnabled(reviewReady);
        reviewAction.setAlpha(reviewReady ? 1f : .48f);
        String action = workMode == CaptureWorkMode.ID ? "预览复印件"
                : workMode == CaptureWorkMode.QR ? "读取二维码" : "编辑 " + count + " 页";
        reviewLabel.setText(count == 0 && workMode.usesPaperGuidance() ? "编辑" : action);
        reviewAction.setContentDescription(workMode.usesPaperGuidance() || count == 0
                ? action : action + "，已拍 " + count + " 张");
        reviewCount.setText(Integer.toString(count));
        reviewCount.setVisibility(count == 0 ? View.INVISIBLE : View.VISIBLE);
        updateThumbnail();
        if (pending) reviewText.setText(review.stage.getValue() == CaptureReview.Stage.WRITING
                ? "正在保存照片" : "正在检查照片");
        else if (limit == 0) reviewText.setText("扫描件页数已满");
        else if (workMode == CaptureWorkMode.ID || workMode == CaptureWorkMode.QR)
            reviewText.setText(framingHint());
        else if (count >= pageLimit()) reviewText.setText("本批次已达 " + count + " 页");
        else if (streaming && (!guidanceEnabled || guidanceUnavailable)) reviewText.setText(framingHint());
        else reviewText.setText("");
        reviewText.setVisibility(reviewText.length() == 0 ? View.GONE : View.VISIBLE);
        grid.invalidate();
        updateTorchControl();
        updateGuidanceVisibility();
    }

    private void updateThumbnail() {
        File latest = files.isEmpty() ? existingThumbnail
                : new File(directory, files.get(files.size() - 1));
        if (Objects.equals(thumbnailFile, latest)) return;
        thumbnailFile = latest;
        if (latest == null) return;
        int edge = ui.dp(136);
        analysisExecutor.execute(() -> {
            Bitmap image;
            try {
                image = ScanImages.source(latest, 0, edge);
            } catch (IOException | RuntimeException invalid) {
                return;
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || !latest.equals(thumbnailFile)) image.recycle();
                else reviewThumbnail.setImageBitmap(image);
            });
        });
    }

    private void applyMode(Button button, boolean selected, boolean enabled) {
        boolean type = button == printedType || button == handwrittenType;
        button.setSelected(selected);
        button.setEnabled(enabled);
        button.setTypeface(Typeface.create(Typeface.DEFAULT, selected ? 500 : 400, false));
        button.setTextColor(selected ? (type ? CAMERA_ACCENT : CAMERA_TEXT) : CAMERA_SECONDARY);
        button.setAlpha(enabled || selected ? 1f : .52f);

        Drawable indicator = null;
        if (selected && type) {
            LayerDrawable pill = new LayerDrawable(new GradientDrawable[]{ui.panel(0xff332a24, 7, 0)});
            pill.setLayerInset(0, 0, ui.dp(9), 0, ui.dp(9));
            indicator = pill;
        } else if (selected) {
            LayerDrawable underline = new LayerDrawable(new GradientDrawable[]{ui.panel(CAMERA_ACCENT, 1, 0)});
            underline.setLayerSize(0, ui.dp(19), ui.dp(2));
            underline.setLayerGravity(0, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            underline.setLayerInsetBottom(0, ui.dp(5));
            indicator = underline;
        }
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x26f7f2e9),
                indicator, ui.panel(Color.WHITE, 8, 0)));
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
        if (camera.getCameraInfo().hasFlashUnit())
            camera.getCameraInfo().getTorchState().observe(this, state -> updateTorchControl());
        startGuidance();
        onPreviewState(finder.getPreviewStreamState().getValue());
    }

    private void retryCamera() {
        if (connecting) return;
        releaseTorch();
        if (camera != null) camera.getCameraInfo().getTorchState().removeObservers(this);
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

    private void toggleTorch() {
        Camera current = camera;
        if (current == null || !torchToggle.isEnabled()) return;
        boolean turnOn = !torchOn(current);
        ListenableFuture<Void> change = current.getCameraControl().enableTorch(turnOn);
        torchChange = change;
        updateTorchControl();
        change.addListener(() -> {
            if (torchChange != change || camera != current || isDestroyed()) return;
            torchChange = null;
            try {
                change.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | CancellationException unavailable) {
                if (foreground && streaming) toast("补光灯暂不可用");
            }
            updateTorchControl();
        }, ContextCompat.getMainExecutor(this));
    }

    private void releaseTorch() {
        torchChange = null;
        if (camera != null && camera.getCameraInfo().hasFlashUnit())
            camera.getCameraControl().enableTorch(false);
        updateTorchControl();
    }

    private boolean torchOn(Camera current) {
        return Integer.valueOf(TorchState.ON).equals(current.getCameraInfo().getTorchState().getValue());
    }

    private void updateTorchControl() {
        boolean supported = camera != null && camera.getCameraInfo().hasFlashUnit();
        torchToggle.setVisibility(supported ? View.VISIBLE : View.GONE);
        if (!supported) return;
        boolean enabled = foreground && streaming && review.file == null && torchChange == null;
        boolean on = torchOn(camera);
        torchToggle.setSelected(on);
        torchToggle.setEnabled(enabled);
        torchToggle.setAlpha(enabled ? 1f : .48f);
        torchToggle.setText(on ? "补光开" : "补光");
        torchToggle.setTextColor(on ? CAMERA_ACCENT : CAMERA_SECONDARY);
        torchToggle.setContentDescription(on ? "关闭补光灯" : "开启补光灯");
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
        guidanceToggle.setTextColor(guidanceEnabled ? CAMERA_TEXT : CAMERA_SECONDARY);
    }

    private void updateGuidanceVisibility() {
        if (guidanceMessage == null) return;
        boolean visible = foreground && streaming && guidanceEnabled && review.file == null
                && workMode.usesPaperGuidance() && pageCount() < pageLimit();
        guidanceOutline.setVisibility(visible ? View.VISIBLE : View.GONE);
        guidanceMessage.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) guidanceOutline.show(null, false);
        else if (guidanceUnavailable) guidanceMessage.setText("此相机暂不支持实时引导，可手动拍摄");
        else if (guidanceMessage.length() == 0) guidanceMessage.setText(framingHint());
    }

    private void startGuidance() {
        if (imageAnalysis == null || !guidanceEnabled || !foreground || analysisExecutor.isShutdown()
                || !workMode.usesPaperGuidance()) return;
        finder.removeCallbacks(guidanceExpired);
        guidanceOutline.show(null, false);
        guidanceMessage.setText(framingHint());
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
                    String message = result.issue == CaptureGuidance.Issue.SEARCHING
                            ? framingHint() : result.issue.message;
                    if (!message.contentEquals(guidanceMessage.getText())) guidanceMessage.setText(message);
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
                guidanceMessage.setText(framingHint());
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
        if (!streaming || imageCapture == null || review.file != null || pageCount() >= pageLimit()) return;
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
        sample.setContentDescription("刚拍摄的第 " + (pageCount() + 1) + " 页");
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
    }

    private void deliverFiles(String destination) {
        if (review.file != null || isFinishing()) return;
        if (destination == null && pageCount() == 0) return;
        transferred = true;
        Intent result = new Intent().putExtra(EXTRA_WORK_MODE, workMode.value)
                .putExtra(EXTRA_HANDWRITING, handwriting());
        if (destination != null) result.putExtra(EXTRA_DESTINATION, destination);
        result.putStringArrayListExtra(EXTRA_FILES, new ArrayList<>(files));
        setResult(RESULT_OK, result);
        finish();
    }

    private void leave(String destination) {
        if (isFinishing() || discardDialog != null) return;
        if (destination != null) {
            deliverFiles(destination);
            return;
        }
        if (files.isEmpty() && review.file == null) {
            if (initialPageCount > 0) deliverFiles(null);
            else finish();
            return;
        }
        int count = files.size();
        discardDialog = new AlertDialog.Builder(this).setTitle("放弃本次拍摄？")
                .setMessage(count == 0 ? "正在处理的照片将被清除。"
                        : "本次新拍的 " + count + " 页将被清除。")
                .setNegativeButton("保留照片", null)
                .setPositiveButton("放弃", (dialog, which) -> finish())
                .create();
        discardDialog.setOnDismissListener(dialog -> {
            discardDialog = null;
            consumeReview();
        });
        discardDialog.show();
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
        private final RectF framingBounds = new RectF();
        private final Runnable hidePoint = () -> { focusRect.setEmpty(); invalidate(); };

        Grid() {
            super(CaptureActivity.this);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            paint.setColor(0x2efff6eb);
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
            paint.setColor(0x2efff6eb);
            if (workMode.usesPaperGuidance()) {
                for (int mark = 1; mark < 3; mark++) {
                    float x = width * mark / 3;
                    float y = height * mark / 3;
                    canvas.drawLine(x, 0, x, height, paint);
                    canvas.drawLine(0, y, width, y, paint);
                }
            } else {
                float ratio = workMode == CaptureWorkMode.ID ? 1.586f : 1f;
                previewBounds(framingBounds, width, height);
                if (!framingBounds.isEmpty()) {
                    float frameWidth = Math.min(framingBounds.width() * .76f,
                            framingBounds.height() * .72f * ratio);
                    float frameHeight = frameWidth / ratio;
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setColor(ScanUi.PAPER);
                    canvas.drawRoundRect((width - frameWidth) / 2, (height - frameHeight) / 2,
                            (width + frameWidth) / 2, (height + frameHeight) / 2,
                            ui.dp(8), ui.dp(8), paint);
                }
            }
            if (!focusRect.isEmpty()) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(ScanUi.PAPER);
                canvas.drawRoundRect(focusRect, ui.dp(4), ui.dp(4), paint);
            }
        }
    }
}
