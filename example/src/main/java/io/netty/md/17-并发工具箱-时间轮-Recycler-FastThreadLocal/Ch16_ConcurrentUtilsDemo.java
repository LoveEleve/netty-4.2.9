package io.netty.md.ch16;

import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.concurrent.FastThreadLocalThread;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 【Ch16 可运行 Demo】并发工具箱验证
 *
 * 验证目标：
 *   1. HashedWheelTimer（时间轮）定时任务调度
 *   2. FastThreadLocal 的使用方式和线程绑定
 *   3. FastThreadLocal vs ThreadLocal 的区别
 *
 * 运行方式：直接运行 main 方法
 */
public class Ch16_ConcurrentUtilsDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("===== 1. HashedWheelTimer 时间轮 =====");
        demoHashedWheelTimer();

        System.out.println("\n===== 2. FastThreadLocal =====");
        demoFastThreadLocal();

        System.out.println("\n✅ Demo 结束");
    }

    static void demoHashedWheelTimer() throws Exception {
        // 创建时间轮：tick间隔100ms，512个槽位
        HashedWheelTimer timer = new HashedWheelTimer(100, TimeUnit.MILLISECONDS, 512);
        CountDownLatch latch = new CountDownLatch(3);

        long start = System.currentTimeMillis();

        // 调度3个延迟任务
        timer.newTimeout(timeout -> {
            System.out.println("[时间轮] 任务1 执行，延迟: " + (System.currentTimeMillis() - start) + "ms (期望200ms)");
            latch.countDown();
        }, 200, TimeUnit.MILLISECONDS);

        timer.newTimeout(timeout -> {
            System.out.println("[时间轮] 任务2 执行，延迟: " + (System.currentTimeMillis() - start) + "ms (期望500ms)");
            latch.countDown();
        }, 500, TimeUnit.MILLISECONDS);

        // 可取消的任务
        Timeout t3 = timer.newTimeout(timeout -> {
            System.out.println("[时间轮] 任务3 不应该执行！");
            latch.countDown();
        }, 300, TimeUnit.MILLISECONDS);
        t3.cancel(); // 取消任务3
        System.out.println("[时间轮] 任务3 已取消, isCancelled=" + t3.isCancelled());
        latch.countDown(); // 手动计数

        latch.await(3, TimeUnit.SECONDS);
        timer.stop();
    }

    static void demoFastThreadLocal() throws Exception {
        FastThreadLocal<String> ftl = new FastThreadLocal<String>() {
            @Override
            protected String initialValue() {
                return "默认值-" + Thread.currentThread().getName();
            }
        };

        CountDownLatch latch = new CountDownLatch(2);

        // FastThreadLocalThread 才能发挥 FastThreadLocal 的性能优势
        Thread t1 = new FastThreadLocalThread(() -> {
            System.out.println("[FastThreadLocal] 线程1 初始值: " + ftl.get());
            ftl.set("线程1的值");
            System.out.println("[FastThreadLocal] 线程1 修改后: " + ftl.get());
            latch.countDown();
        }, "ftl-thread-1");

        Thread t2 = new FastThreadLocalThread(() -> {
            System.out.println("[FastThreadLocal] 线程2 初始值: " + ftl.get());
            ftl.set("线程2的值");
            System.out.println("[FastThreadLocal] 线程2 修改后: " + ftl.get());
            latch.countDown();
        }, "ftl-thread-2");

        t1.start();
        t2.start();
        latch.await(5, TimeUnit.SECONDS);

        System.out.println("[主线程] FastThreadLocal 值: " + ftl.get());
    }
}
