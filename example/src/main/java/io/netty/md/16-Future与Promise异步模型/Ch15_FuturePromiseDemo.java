package io.netty.md.ch15;

import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.util.concurrent.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 【Ch15 可运行 Demo】Future 与 Promise 异步模型验证
 *
 * 验证目标：
 *   1. Promise 的 setSuccess/setFailure 状态流转
 *   2. Future.addListener 异步回调
 *   3. Future.sync() 阻塞等待
 *   4. Promise 链式编排（A完成后触发B）
 *
 * 运行方式：直接运行 main 方法
 */
public class Ch15_FuturePromiseDemo {

    public static void main(String[] args) throws Exception {
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoop eventLoop = group.next();

        System.out.println("===== 1. Promise 基本使用 =====");
        demoBasicPromise(eventLoop);

        System.out.println("\n===== 2. Listener 异步回调 =====");
        demoListener(eventLoop);

        System.out.println("\n===== 3. Promise 链式编排 =====");
        demoChain(eventLoop);

        Thread.sleep(1000);
        group.shutdownGracefully().sync();
        System.out.println("\n✅ Demo 结束");
    }

    static void demoBasicPromise(EventLoop eventLoop) throws Exception {
        // 创建 Promise（可写的 Future）
        Promise<String> promise = eventLoop.newPromise();
        System.out.println("创建后: isDone=" + promise.isDone() + " isSuccess=" + promise.isSuccess());

        // 在 EventLoop 线程中异步设置结果
        eventLoop.execute(() -> {
            try {
                Thread.sleep(200); // 模拟异步操作
            } catch (InterruptedException e) { /* ignore */ }
            promise.setSuccess("Hello from Promise!");
        });

        // 阻塞等待
        promise.sync();
        System.out.println("完成后: isDone=" + promise.isDone()
                + " isSuccess=" + promise.isSuccess()
                + " result=" + promise.getNow());
    }

    static void demoListener(EventLoop eventLoop) throws Exception {
        Promise<Integer> promise = eventLoop.newPromise();
        CountDownLatch latch = new CountDownLatch(1);

        // 添加监听器（异步回调）
        promise.addListener((FutureListener<Integer>) future -> {
            if (future.isSuccess()) {
                System.out.println("[Listener] 成功! 结果=" + future.getNow()
                        + " 回调线程=" + Thread.currentThread().getName());
            } else {
                System.out.println("[Listener] 失败! 原因=" + future.cause().getMessage());
            }
            latch.countDown();
        });

        // 设置结果
        eventLoop.execute(() -> promise.setSuccess(42));
        latch.await(5, TimeUnit.SECONDS);
    }

    static void demoChain(EventLoop eventLoop) throws Exception {
        // 模拟：步骤A完成后，触发步骤B
        Promise<String> stepA = eventLoop.newPromise();
        Promise<String> stepB = eventLoop.newPromise();
        CountDownLatch latch = new CountDownLatch(1);

        stepA.addListener((FutureListener<String>) futureA -> {
            System.out.println("[链式] 步骤A完成: " + futureA.getNow());
            // A 完成后启动 B
            eventLoop.execute(() -> {
                stepB.setSuccess("B的结果（基于A: " + futureA.getNow() + "）");
            });
        });

        stepB.addListener((FutureListener<String>) futureB -> {
            System.out.println("[链式] 步骤B完成: " + futureB.getNow());
            latch.countDown();
        });

        // 启动链
        eventLoop.execute(() -> stepA.setSuccess("A的结果"));
        latch.await(5, TimeUnit.SECONDS);
    }
}
