package io.netty.md.ch03;

import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.util.concurrent.Future;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 【Ch03 可运行 Demo】EventLoop 线程模型验证
 *
 * 验证目标：
 *   1. EventLoop 是单线程的——所有提交的任务都在同一个线程上执行
 *   2. EventLoopGroup 包含多个 EventLoop，通过 next() 轮询选取
 *   3. 定时任务 schedule() 和 scheduleAtFixedRate() 的使用
 *   4. 懒启动：线程在第一次 execute() 时才真正启动
 *
 * 运行方式：直接运行 main 方法，观察控制台输出的线程名
 */
public class Ch03_EventLoopDemo {

    public static void main(String[] args) throws Exception {
        // 创建包含 2 个 EventLoop 的 Group
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());

        System.out.println("===== 验证 1：EventLoop 单线程特性 =====");
        EventLoop loop1 = group.next();
        EventLoop loop2 = group.next();

        CountDownLatch latch = new CountDownLatch(4);

        // 向同一个 EventLoop 提交多个任务，验证线程名一致
        for (int i = 0; i < 2; i++) {
            final int taskId = i;
            loop1.execute(() -> {
                System.out.println("[loop1] 任务" + taskId + " 执行线程: " + Thread.currentThread().getName());
                latch.countDown();
            });
        }
        for (int i = 0; i < 2; i++) {
            final int taskId = i;
            loop2.execute(() -> {
                System.out.println("[loop2] 任务" + taskId + " 执行线程: " + Thread.currentThread().getName());
                latch.countDown();
            });
        }
        latch.await(5, TimeUnit.SECONDS);

        System.out.println("\n===== 验证 2：next() 轮询分配 =====");
        for (int i = 0; i < 6; i++) {
            EventLoop picked = group.next();
            System.out.println("第" + i + "次 next() 选中: " + picked);
        }

        System.out.println("\n===== 验证 3：定时任务 =====");
        CountDownLatch scheduleLatch = new CountDownLatch(3);
        loop1.scheduleAtFixedRate(() -> {
            System.out.println("[定时任务] 执行时间: " + System.currentTimeMillis()
                    + " 线程: " + Thread.currentThread().getName());
            scheduleLatch.countDown();
        }, 0, 500, TimeUnit.MILLISECONDS);

        scheduleLatch.await(5, TimeUnit.SECONDS);

        System.out.println("\n===== 验证 4：inEventLoop() 判断 =====");
        System.out.println("当前线程是 loop1 的线程吗? " + loop1.inEventLoop()); // false
        loop1.execute(() -> {
            System.out.println("在 loop1 内部判断: " + loop1.inEventLoop()); // true
        });

        Thread.sleep(500);
        group.shutdownGracefully().sync();
        System.out.println("\n✅ Demo 结束");
    }
}
