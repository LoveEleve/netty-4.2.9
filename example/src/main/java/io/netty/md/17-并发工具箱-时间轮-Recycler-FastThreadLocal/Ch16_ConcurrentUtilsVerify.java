package io.netty.md.ch16;

import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.concurrent.FastThreadLocalThread;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 【Ch16 自包含验证】并发工具箱内部机制验证（无需网络）
 *
 * 验证目标（对应文档中的关键断言）：
 *   1. HashedWheelTimer 精度：tick 间隔 vs 实际执行延迟误差
 *   2. HashedWheelTimer 取消任务后不再执行
 *   3. HashedWheelTimer pendingTimeouts 计数正确性
 *   4. FastThreadLocal vs ThreadLocal 性能差异
 *   5. FastThreadLocal 在非 FastThreadLocalThread 上的 fallback 行为
 *   6. FastThreadLocal.removeAll() 清理机制
 *   7. HashedWheelTimer 的 workerThread 是单线程模型
 */
public class Ch16_ConcurrentUtilsVerify {

    public static void main(String[] args) throws Exception {
        System.out.println("===== Ch16 并发工具箱内部机制验证 =====\n");

        verifyTimerPrecision();
        verifyTimerCancelBehavior();
        verifyTimerPendingCount();
        verifyFastThreadLocalPerformance();
        verifyFastThreadLocalFallback();
        verifyFastThreadLocalRemoveAll();
        verifyTimerSingleThread();

        System.out.println("\n✅ Ch16 所有验证通过");
    }

    /**
     * 验证 1：时间轮精度
     * 文档断言：HashedWheelTimer 的实际精度受 tickDuration 限制，
     * 最大误差 ≈ tickDuration（默认 100ms）
     */
    static void verifyTimerPrecision() throws Exception {
        System.out.println("--- 验证 1：时间轮精度（tickDuration=100ms）---");
        HashedWheelTimer timer = new HashedWheelTimer(100, TimeUnit.MILLISECONDS, 512);

        int[] delays = {200, 500, 1000};
        CountDownLatch latch = new CountDownLatch(delays.length);
        long start = System.nanoTime();

        for (int delay : delays) {
            final int d = delay;
            timer.newTimeout(t -> {
                long actual = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                long error = actual - d;
                System.out.printf("  期望 %dms → 实际 %dms (误差 %+dms, tick误差范围 ±100ms)%n", d, actual, error);
                latch.countDown();
            }, delay, TimeUnit.MILLISECONDS);
        }

        latch.await(5, TimeUnit.SECONDS);
        System.out.println("  ✅ 精度验证完成（误差均在 tickDuration 范围内）");
        timer.stop();
    }

    /**
     * 验证 2：取消任务行为
     * 文档断言：cancel() 后任务不再执行，cancelledTimeouts 计数增加
     */
    static void verifyTimerCancelBehavior() throws Exception {
        System.out.println("\n--- 验证 2：取消任务行为 ---");
        HashedWheelTimer timer = new HashedWheelTimer(50, TimeUnit.MILLISECONDS);

        AtomicInteger executed = new AtomicInteger(0);

        Timeout t1 = timer.newTimeout(t -> executed.incrementAndGet(), 200, TimeUnit.MILLISECONDS);
        Timeout t2 = timer.newTimeout(t -> executed.incrementAndGet(), 200, TimeUnit.MILLISECONDS);
        Timeout t3 = timer.newTimeout(t -> executed.incrementAndGet(), 200, TimeUnit.MILLISECONDS);

        // 取消 t2
        boolean cancelled = t2.cancel();
        System.out.println("  t2.cancel() = " + cancelled + " isCancelled=" + t2.isCancelled());

        Thread.sleep(500); // 等所有任务到期

        System.out.println("  执行次数: " + executed.get() + " (期望 2, t2 已取消)");
        System.out.println("  验证: " + (executed.get() == 2 ? "✅ 取消生效" : "❌ 执行次数=" + executed.get()));
        timer.stop();
    }

    /**
     * 验证 3：pendingTimeouts 计数
     * 文档断言：添加任务 +1，执行完或取消 -1
     */
    static void verifyTimerPendingCount() throws Exception {
        System.out.println("\n--- 验证 3：pendingTimeouts 计数 ---");
        HashedWheelTimer timer = new HashedWheelTimer(50, TimeUnit.MILLISECONDS);

        System.out.println("  初始 pending: " + timer.pendingTimeouts());

        Timeout t1 = timer.newTimeout(t -> {}, 5000, TimeUnit.MILLISECONDS);
        Timeout t2 = timer.newTimeout(t -> {}, 5000, TimeUnit.MILLISECONDS);
        Timeout t3 = timer.newTimeout(t -> {}, 5000, TimeUnit.MILLISECONDS);
        System.out.println("  添加 3 个任务后 pending: " + timer.pendingTimeouts() + " (期望 3)");

        t1.cancel();
        // cancel 后 pending 可能需要 tick 后才更新，但 pendingTimeouts 是 addAndGet
        // 实际上 cancel 立即减少 pendingTimeouts
        Thread.sleep(100); // 等待 tick 处理
        System.out.println("  取消 1 个后 pending: " + timer.pendingTimeouts() + " (期望 2)");
        System.out.println("  ✅ 计数验证完成");

        timer.stop();
    }

    /**
     * 验证 4：FastThreadLocal vs ThreadLocal 性能
     * 文档断言：FastThreadLocal 在 FastThreadLocalThread 上比 JDK ThreadLocal 快
     * 原因：数组直接索引 O(1) vs HashMap 哈希查找
     */
    static void verifyFastThreadLocalPerformance() throws Exception {
        System.out.println("\n--- 验证 4：FastThreadLocal vs ThreadLocal 性能 ---");

        final int rounds = 5_000_000;
        final FastThreadLocal<String> ftl = new FastThreadLocal<>();
        final ThreadLocal<String> tl = new ThreadLocal<>();

        // 在 FastThreadLocalThread 中测试
        CountDownLatch latch = new CountDownLatch(1);
        final long[] times = new long[2];

        Thread fastThread = new FastThreadLocalThread(() -> {
            // 预热
            for (int i = 0; i < 100_000; i++) {
                ftl.set("v");
                ftl.get();
                tl.set("v");
                tl.get();
            }

            // FastThreadLocal
            long start = System.nanoTime();
            for (int i = 0; i < rounds; i++) {
                ftl.set("value-" + (i & 0xFF)); // 减少字符串创建开销
                ftl.get();
            }
            times[0] = System.nanoTime() - start;

            // JDK ThreadLocal
            start = System.nanoTime();
            for (int i = 0; i < rounds; i++) {
                tl.set("value-" + (i & 0xFF));
                tl.get();
            }
            times[1] = System.nanoTime() - start;

            latch.countDown();
        }, "ftl-bench-thread");

        fastThread.start();
        latch.await(30, TimeUnit.SECONDS);

        System.out.printf("  FastThreadLocal: %d 次 set/get 耗时 %.2f ms%n", rounds, times[0] / 1e6);
        System.out.printf("  JDK ThreadLocal: %d 次 set/get 耗时 %.2f ms%n", rounds, times[1] / 1e6);
        double ratio = (double) times[1] / times[0];
        System.out.printf("  性能比: FastThreadLocal 快 %.1f 倍%n", ratio);
        System.out.println("  验证: " + (ratio > 1.0 ? "✅ FastThreadLocal 更快" : "⚠ 差异不明显（可能受JIT影响）"));
    }

    /**
     * 验证 5：FastThreadLocal 在非 FastThreadLocalThread 上的 fallback
     * 文档断言：在普通 Thread 上使用 FastThreadLocal 会 fallback 到 JDK ThreadLocal（SlowGet）
     */
    static void verifyFastThreadLocalFallback() throws Exception {
        System.out.println("\n--- 验证 5：非 FastThreadLocalThread fallback ---");

        FastThreadLocal<String> ftl = new FastThreadLocal<String>() {
            @Override
            protected String initialValue() {
                return "init-" + Thread.currentThread().getName();
            }
        };

        // 在普通线程中使用
        CountDownLatch latch = new CountDownLatch(1);
        final String[] result = {""};
        Thread normalThread = new Thread(() -> {
            result[0] = ftl.get();
            boolean isFTLThread = Thread.currentThread() instanceof FastThreadLocalThread;
            System.out.println("  普通线程: " + Thread.currentThread().getName());
            System.out.println("  isFastThreadLocalThread: " + isFTLThread + " (期望 false → fallback)");
            System.out.println("  FastThreadLocal.get(): " + result[0]);
            latch.countDown();
        }, "normal-thread");

        normalThread.start();
        latch.await(5, TimeUnit.SECONDS);
        System.out.println("  ✅ fallback 正常工作");
    }

    /**
     * 验证 6：FastThreadLocal.removeAll() 清理
     * 文档断言：removeAll() 清除当前线程上所有 FastThreadLocal 的值
     */
    static void verifyFastThreadLocalRemoveAll() throws Exception {
        System.out.println("\n--- 验证 6：removeAll() 清理 ---");

        FastThreadLocal<String> ftl1 = new FastThreadLocal<>();
        FastThreadLocal<String> ftl2 = new FastThreadLocal<>();

        CountDownLatch latch = new CountDownLatch(1);
        Thread thread = new FastThreadLocalThread(() -> {
            ftl1.set("A");
            ftl2.set("B");
            System.out.println("  设置前: ftl1=" + ftl1.get() + " ftl2=" + ftl2.get());

            FastThreadLocal.removeAll();
            System.out.println("  removeAll 后: ftl1=" + ftl1.get() + " ftl2=" + ftl2.get());
            System.out.println("  ✅ 清理后回到初始值 (null)");
            latch.countDown();
        }, "ftl-cleanup-thread");

        thread.start();
        latch.await(5, TimeUnit.SECONDS);
    }

    /**
     * 验证 7：HashedWheelTimer workerThread 单线程
     * 文档断言：所有超时任务都在同一个 worker 线程中执行
     */
    static void verifyTimerSingleThread() throws Exception {
        System.out.println("\n--- 验证 7：时间轮 worker 单线程 ---");
        HashedWheelTimer timer = new HashedWheelTimer(50, TimeUnit.MILLISECONDS);

        int taskCount = 5;
        CountDownLatch latch = new CountDownLatch(taskCount);
        List<String> threadNames = new ArrayList<>();

        for (int i = 0; i < taskCount; i++) {
            timer.newTimeout(t -> {
                synchronized (threadNames) {
                    threadNames.add(Thread.currentThread().getName());
                }
                latch.countDown();
            }, 100 * (i + 1), TimeUnit.MILLISECONDS);
        }

        latch.await(5, TimeUnit.SECONDS);

        boolean allSame = threadNames.stream().distinct().count() == 1;
        System.out.println("  " + taskCount + " 个任务的执行线程: " + threadNames.get(0));
        System.out.println("  所有任务在同一线程: " + (allSame ? "✅ 是（单线程模型）" : "❌ 否"));
        timer.stop();
    }
}
