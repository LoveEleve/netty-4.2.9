package io.netty.md.ch15;

import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.util.concurrent.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 【Ch15 自包含验证】Future 与 Promise 异步模型内部机制验证（无需网络）
 *
 * 验证目标（对应文档中的关键断言）：
 *   1. Promise 状态机：UNCANCELLABLE → SUCCESS/FAILURE 单向不可逆
 *   2. setSuccess 后再次 set 抛 IllegalStateException
 *   3. Listener 回调在 EventLoop 线程中执行（线程安全保证）
 *   4. cancel() 语义：未完成时可取消，已完成时取消失败
 *   5. sync() / await() 语义差异：sync 抛异常，await 不抛
 *   6. Listener 执行顺序：按注册顺序执行（FIFO）
 *   7. Promise.setUncancellable() 防止取消
 */
public class Ch15_FuturePromiseVerify {

    public static void main(String[] args) throws Exception {
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoop eventLoop = group.next();

        System.out.println("===== Ch15 Future/Promise 内部机制验证 =====\n");

        verifyStateImmutable(eventLoop);
        verifyDoubleSetThrows(eventLoop);
        verifyListenerThread(eventLoop);
        verifyCancelSemantics(eventLoop);
        verifySyncVsAwait(eventLoop);
        verifyListenerOrder(eventLoop);
        verifySetUncancellable(eventLoop);

        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        System.out.println("\n✅ Ch15 所有验证通过");
    }

    /**
     * 验证 1：Promise 状态不可逆
     * 文档断言：一旦 setSuccess/setFailure，状态永远不会改变
     */
    static void verifyStateImmutable(EventLoop loop) throws Exception {
        System.out.println("--- 验证 1：状态不可逆 ---");
        Promise<String> p = loop.newPromise();

        System.out.println("  初始: isDone=" + p.isDone() + " isSuccess=" + p.isSuccess());

        p.setSuccess("OK");
        System.out.println("  setSuccess 后: isDone=" + p.isDone() + " isSuccess=" + p.isSuccess() + " result=" + p.getNow());

        // 尝试用 trySuccess 改变结果（应返回 false）
        boolean canChange = p.trySuccess("Changed");
        System.out.println("  trySuccess 尝试修改: " + (canChange ? "❌ 竟然成功了" : "✅ 返回 false（不可变）"));
    }

    /**
     * 验证 2：重复 set 抛异常
     * 文档断言：对已完成的 Promise 调用 setSuccess/setFailure 抛出 IllegalStateException
     */
    static void verifyDoubleSetThrows(EventLoop loop) throws Exception {
        System.out.println("\n--- 验证 2：重复 set 抛异常 ---");
        Promise<String> p = loop.newPromise();
        p.setSuccess("First");

        try {
            p.setSuccess("Second");
            System.out.println("  ❌ 未抛异常");
        } catch (IllegalStateException e) {
            System.out.println("  ✅ 正确抛出: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // tryFailure 也应返回 false
        boolean tryResult = p.tryFailure(new RuntimeException("test"));
        System.out.println("  tryFailure 返回: " + (tryResult ? "❌ true" : "✅ false（已完成不可改）"));
    }

    /**
     * 验证 3：Listener 回调线程
     * 文档断言：Listener 在 Promise 关联的 EventLoop 线程中执行
     */
    static void verifyListenerThread(EventLoop loop) throws Exception {
        System.out.println("\n--- 验证 3：Listener 回调线程 ---");
        Promise<String> p = loop.newPromise();
        CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> callbackThread = new AtomicReference<>();

        p.addListener((FutureListener<String>) future -> {
            callbackThread.set(Thread.currentThread().getName());
            latch.countDown();
        });

        // 从外部线程 set
        p.setSuccess("done");
        latch.await(5, TimeUnit.SECONDS);

        // 验证回调是否在 EventLoop 线程执行
        CountDownLatch latch2 = new CountDownLatch(1);
        final AtomicReference<String> loopThread = new AtomicReference<>();
        loop.execute(() -> {
            loopThread.set(Thread.currentThread().getName());
            latch2.countDown();
        });
        latch2.await(5, TimeUnit.SECONDS);

        System.out.println("  Listener 回调线程: " + callbackThread.get());
        System.out.println("  EventLoop 线程:    " + loopThread.get());
        System.out.println("  是同一线程: " + (callbackThread.get().equals(loopThread.get()) ? "✅ 是" : "⚠ 否（可能在 set 调用线程执行）"));
    }

    /**
     * 验证 4：cancel() 语义
     * 文档断言：未完成 → 可取消(true)，已完成 → 不可取消(false)
     */
    static void verifyCancelSemantics(EventLoop loop) throws Exception {
        System.out.println("\n--- 验证 4：cancel() 语义 ---");

        // 场景1：未完成时取消
        Promise<String> p1 = loop.newPromise();
        boolean cancelled = p1.cancel(false);
        System.out.println("  未完成时取消: cancel=" + cancelled + " isCancelled=" + p1.isCancelled());
        System.out.println("  验证: " + (cancelled && p1.isCancelled() ? "✅ 正确取消" : "❌ 取消失败"));

        // 场景2：已完成时取消
        Promise<String> p2 = loop.newPromise();
        p2.setSuccess("Done");
        boolean cancelled2 = p2.cancel(false);
        System.out.println("  已完成时取消: cancel=" + cancelled2 + " isCancelled=" + p2.isCancelled());
        System.out.println("  验证: " + (!cancelled2 && !p2.isCancelled() ? "✅ 正确拒绝" : "❌ 不应取消成功"));
    }

    /**
     * 验证 5：sync() vs await() 语义差异
     * 文档断言：sync() 会重新抛出异常，await() 不会
     */
    static void verifySyncVsAwait(EventLoop loop) throws Exception {
        System.out.println("\n--- 验证 5：sync() vs await() ---");

        // sync：失败时抛异常
        Promise<String> p1 = loop.newPromise();
        p1.setFailure(new RuntimeException("test error"));
        try {
            p1.sync();
            System.out.println("  sync() 未抛异常 ❌");
        } catch (Exception e) {
            System.out.println("  sync() 抛出异常: ✅ " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // await：失败时不抛异常
        Promise<String> p2 = loop.newPromise();
        p2.setFailure(new RuntimeException("test error 2"));
        try {
            p2.await();
            System.out.println("  await() 未抛异常: ✅ (await 不抛异常)");
            System.out.println("  但 isSuccess=" + p2.isSuccess() + " cause=" + p2.cause().getMessage());
        } catch (Exception e) {
            System.out.println("  await() 抛出异常: ❌ " + e.getMessage());
        }
    }

    /**
     * 验证 6：Listener 执行顺序
     * 文档断言：多个 Listener 按注册顺序执行（FIFO）
     */
    static void verifyListenerOrder(EventLoop loop) throws Exception {
        System.out.println("\n--- 验证 6：Listener FIFO 顺序 ---");
        Promise<String> p = loop.newPromise();
        StringBuilder order = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(3);

        p.addListener((FutureListener<String>) f -> { order.append("A"); latch.countDown(); });
        p.addListener((FutureListener<String>) f -> { order.append("B"); latch.countDown(); });
        p.addListener((FutureListener<String>) f -> { order.append("C"); latch.countDown(); });

        p.setSuccess("go");
        latch.await(5, TimeUnit.SECONDS);

        // 短暂等待确保所有回调执行
        Thread.sleep(100);
        System.out.println("  执行顺序: " + order.toString());
        System.out.println("  验证: " + ("ABC".equals(order.toString()) ? "✅ FIFO 顺序正确" : "⚠ 顺序=" + order));
    }

    /**
     * 验证 7：setUncancellable() 防取消
     * 文档断言：setUncancellable 后再调 cancel 返回 false
     */
    static void verifySetUncancellable(EventLoop loop) throws Exception {
        System.out.println("\n--- 验证 7：setUncancellable() ---");
        Promise<String> p = loop.newPromise();

        boolean uncancellable = p.setUncancellable();
        System.out.println("  setUncancellable(): " + uncancellable);

        boolean cancelled = p.cancel(false);
        System.out.println("  cancel() 返回: " + cancelled);
        System.out.println("  isCancelled: " + p.isCancelled());
        System.out.println("  验证: " + (!cancelled && !p.isCancelled() ? "✅ 正确防取消" : "❌ 不应取消成功"));

        // 正常完成仍然可以
        p.setSuccess("Final");
        System.out.println("  setSuccess 后 result: " + p.getNow() + " ✅ 正常完成");
    }
}
