package com.scandesk.app;

import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.*;

/** Verifies the real task dialog's cancellation and the reusable worker executor. */
@RunWith(AndroidJUnit4.class)
public final class JobCancellationFlowTest {
    private final Instrumentation device = InstrumentationRegistry.getInstrumentation();

    @Test public void cancelInterruptsAnAwaitingTaskAndTheSameExecutorCanRunTheNextTask() throws Exception {
        try (Selection selection = new Selection();
                ActivityScenario<MainActivity> app = ActivityScenario.launch(MainActivity.class)) {
            BlockingOperation blocking = new BlockingOperation();
            AtomicBoolean cancelledSuccess = new AtomicBoolean();
            AtomicReference<Object> cancelledState = new AtomicReference<>();
            AtomicReference<ExecutorService> executor = new AtomicReference<>();
            app.onActivity(activity -> {
                cancelledState.set(start(activity, "取消识别测试", blocking::run, value -> cancelledSuccess.set(true)));
                executor.set((ExecutorService) field(activity, "work"));
            });
            try {
                assertTrue("首个任务已经进入等待", blocking.started.await(3, TimeUnit.SECONDS));
                assertSame(blocking.worker.get(), field(cancelledState.get(), "worker"));
                clickCancel();
                assertTrue("取消应中断等待而非等超时", blocking.exited.await(3, TimeUnit.SECONDS));
                assertTrue("operation 收到了 InterruptedException", blocking.interrupted.get());
                awaitNoJob(app);
                awaitWorkerReleased(cancelledState.get());
                assertFalse("已取消任务不能交给成功回调", cancelledSuccess.get());
                assertFalse("取消单个任务后 executor 仍可用", executor.get().isShutdown());

                CountDownLatch nextSucceeded = new CountDownLatch(1);
                AtomicBoolean inheritedInterrupt = new AtomicBoolean(true);
                AtomicReference<Thread> nextWorker = new AtomicReference<>();
                AtomicReference<Object> nextState = new AtomicReference<>();
                app.onActivity(activity -> nextState.set(start(activity, "后续识别测试", state -> {
                    nextWorker.set(Thread.currentThread());
                    inheritedInterrupt.set(Thread.currentThread().isInterrupted());
                    // A leaked interrupt would also make an otherwise available short wait fail.
                    assertTrue(new CountDownLatch(0).await(1, TimeUnit.SECONDS));
                    return "正常完成";
                }, value -> {
                    assertEquals("正常完成", value);
                    nextSucceeded.countDown();
                })));
                assertTrue("同一 executor 的下个任务能够完成", nextSucceeded.await(3, TimeUnit.SECONDS));
                awaitNoJob(app);
                awaitWorkerReleased(nextState.get());
                assertSame("继续复用同一个 worker，而非靠重建线程清状态", blocking.worker.get(), nextWorker.get());
                assertFalse("取消留下的中断不能传给后续任务", inheritedInterrupt.get());
            } finally { blocking.release.countDown(); }
        }
    }

    @Test public void destroyingActivityInterruptsItsOperationAndTerminatesTheExecutor() throws Exception {
        try (Selection selection = new Selection()) {
            BlockingOperation blocking = new BlockingOperation();
            AtomicBoolean success = new AtomicBoolean();
            AtomicReference<Object> state = new AtomicReference<>();
            AtomicReference<ExecutorService> executor = new AtomicReference<>();
            AtomicReference<AlertDialog> progress = new AtomicReference<>();
            ActivityScenario<MainActivity> app = ActivityScenario.launch(MainActivity.class);
            try {
                app.onActivity(activity -> {
                    state.set(start(activity, "销毁中断测试", blocking::run, value -> success.set(true)));
                    executor.set((ExecutorService) field(activity, "work"));
                    progress.set((AlertDialog) field(state.get(), "dialog"));
                    assertTrue(progress.get().isShowing());
                });
                assertTrue("任务已经开始", blocking.started.await(3, TimeUnit.SECONDS));
                app.close();
                assertTrue("Activity 销毁应中断后台等待", blocking.exited.await(3, TimeUnit.SECONDS));
                assertTrue(blocking.interrupted.get());
                awaitWorkerReleased(state.get());
                assertTrue("所属 executor 已关闭", executor.get().isShutdown());
                assertTrue("后台线程应及时结束", executor.get().awaitTermination(3, TimeUnit.SECONDS));
                device.waitForIdleSync();
                assertFalse("销毁后不交付成功结果", success.get());
                device.runOnMainSync(() -> assertFalse("进度对话框已释放", progress.get().isShowing()));
            } finally {
                blocking.release.countDown();
                app.close();
            }
        }
    }

    @Test public void cancellationAfterAWordDraftIsQueuedDiscardsItBeforeSuccessDelivery() throws Exception {
        try (Selection selection = new Selection();
                ActivityScenario<MainActivity> app = ActivityScenario.launch(MainActivity.class)) {
            CountDownLatch produced = new CountDownLatch(1);
            AtomicBoolean success = new AtomicBoolean();
            AtomicReference<Object> state = new AtomicReference<>();
            app.onActivity(activity -> {
                // Hold this one main-thread turn so the worker's result cannot be delivered yet.
                state.set(start(activity, "迟到结果取消测试", current -> {
                    ScanRecognition.WordDraft draft = new ScanRecognition.WordDraft(List.of("迟到的识别文字"));
                    produced.countDown();
                    return draft;
                }, result -> success.set(true)));
                try {
                    assertTrue("worker 已生成 WordDraft", produced.await(3, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                // worker is cleared only AFTER main.post(result) in task's finally. Waiting for
                // that volatile write makes the race deterministic without executing the callback.
                awaitWorkerReleased(state.get());
                assertFalse(success.get());
                try {
                    Method cancel = state.get().getClass().getDeclaredMethod("cancel");
                    cancel.setAccessible(true);
                    cancel.invoke(state.get());
                } catch (Exception failure) { throw new AssertionError("取消迟到结果", failure); }
                assertTrue(((AtomicBoolean) field(state.get(), "cancelled")).get());
            });
            // The queued callback can now run, and must clear the job without delivering the draft.
            awaitNoJob(app);
            device.waitForIdleSync();
            assertFalse("已取消的迟到 WordDraft 不能进入 success", success.get());
            assertNull(field(state.get(), "worker"));
        }
    }

    private static final class BlockingOperation {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch exited = new CountDownLatch(1);
        final AtomicBoolean interrupted = new AtomicBoolean();
        final AtomicReference<Thread> worker = new AtomicReference<>();

        Object run(Object state) throws IOException {
            worker.set(Thread.currentThread());
            started.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) throw new IOException("等待取消操作超时");
                return "未取消";
            } catch (InterruptedException cancellation) {
                interrupted.set(true);
                // Match Reader.read's interrupted Tasks.await path, including restored interrupt.
                Thread.currentThread().interrupt();
                throw new IOException("已取消", cancellation);
            } finally { exited.countDown(); }
        }
    }

    private interface Operation { Object run(Object state) throws Exception; }

    private static Object start(MainActivity activity, String title, Operation operation, Consumer<Object> success) {
        try {
            Class<?> jobType = Class.forName(MainActivity.class.getName() + "$Job");
            Class<?> resultType = Class.forName(MainActivity.class.getName() + "$Result");
            Object job = Proxy.newProxyInstance(jobType.getClassLoader(), new Class<?>[]{jobType},
                    (proxy, method, args) -> {
                        if (method.getName().equals("run")) return operation.run(args[0]);
                        return objectMethod(proxy, method, args);
                    });
            Object result = Proxy.newProxyInstance(resultType.getClassLoader(), new Class<?>[]{resultType},
                    (proxy, method, args) -> {
                        if (method.getName().equals("show")) { success.accept(args[0]); return null; }
                        return objectMethod(proxy, method, args);
                    });
            Method task = MainActivity.class.getDeclaredMethod("task", String.class, boolean.class,
                    jobType, resultType, Runnable.class);
            task.setAccessible(true);
            task.invoke(activity, title, true, job, result, null);
            Object state = field(activity, "job");
            assertNotNull("测试任务已注册", state);
            return state;
        } catch (Exception failure) { throw new AssertionError("无法创建测试任务", failure); }
    }

    private static Object objectMethod(Object proxy, Method method, Object[] arguments) {
        switch (method.getName()) {
            case "toString": return "CancellationTestProxy";
            case "hashCode": return System.identityHashCode(proxy);
            case "equals": return arguments != null && arguments.length == 1 && proxy == arguments[0];
            default: throw new AssertionError(method.getName());
        }
    }

    private void clickCancel() {
        long until = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < until) {
            AccessibilityNodeInfo root = device.getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText("取消")) {
                    if (node.getText() != null && "取消".contentEquals(node.getText()) && node.isClickable()
                            && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        device.waitForIdleSync();
                        return;
                    }
                }
            }
            SystemClock.sleep(35);
        }
        fail("找不到任务的取消按钮");
    }

    private void awaitNoJob(ActivityScenario<MainActivity> app) {
        long until = SystemClock.uptimeMillis() + 5000;
        AtomicBoolean cleared = new AtomicBoolean();
        do {
            device.waitForIdleSync();
            app.onActivity(activity -> cleared.set(field(activity, "job") == null));
            if (cleared.get()) return;
            SystemClock.sleep(35);
        } while (SystemClock.uptimeMillis() < until);
        fail("取消后 job 未清除");
    }

    private static void awaitWorkerReleased(Object state) {
        long until = SystemClock.uptimeMillis() + 3000;
        do {
            if (field(state, "worker") == null) return;
            SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < until);
        fail("任务结束后仍持有 worker 引用");
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Exception failure) { throw new AssertionError(name, failure); }
    }

    private static final class Selection implements AutoCloseable {
        final Context context = ApplicationProvider.getApplicationContext();
        final SharedPreferences preferences = context.getSharedPreferences("workspace", Context.MODE_PRIVATE);
        final Map<String, ?> prior = preferences.getAll();

        Selection() {
            assertTrue(preferences.edit().remove("document").remove("page").remove("crop").commit());
        }

        @Override public void close() {
            SharedPreferences.Editor restore = preferences.edit().putString("document", (String)prior.get("document"))
                    .putString("page", (String)prior.get("page"));
            if (prior.containsKey("crop")) restore.putBoolean("crop", (Boolean)prior.get("crop"));
            else restore.remove("crop");
            assertTrue(restore.commit());
        }
    }
}
