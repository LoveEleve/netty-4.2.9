package io.netty.md.ch13;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.CharsetUtil;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 【Ch13 可运行 Demo + Verify】Native Transport io_uring 验证
 *
 * 验证目标（对应文档中的关键断言）：
 *   1. IoUring.isAvailable() 可用性与特性探测
 *   2. io_uring 核心架构：SQ（提交队列）+ CQ（完成队列）
 *   3. IoUringIoHandler 内部字段（RingBuffer、registrations）
 *   4. io_uring vs Epoll vs NIO 架构对比
 *   5. IoUringServerSocketChannel / IoUringSocketChannel 继承体系
 *   6. io_uring 高级特性探测（multishot、buffer ring 等）
 *   7. Echo 往返测试（可用时用 io_uring，否则 fallback）
 *
 * 运行环境：Linux 5.9+（io_uring 不可用时自动 fallback 到 NIO）
 */
public class Ch13_IoUringDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("===== Ch13 Native Transport io_uring 验证 =====\n");

        verifyIoUringAvailability();
        verifySqCqArchitecture();
        verifyIoUringHandlerInternals();
        verifyIoUringChannelHierarchy();
        verifyFeatureProbe();
        verifyArchitectureComparison();
        verifyIoUringEchoRoundtrip();

        System.out.println("\n✅ Ch13 所有验证完成");
    }

    /**
     * 验证 1：io_uring 可用性
     * 文档断言：IoUring 需要 Linux 5.9+ 且 Java 9+，通过真实创建 ring 来检测
     */
    static void verifyIoUringAvailability() {
        System.out.println("--- 验证 1：io_uring 可用性检测 ---");
        try {
            Class<?> ioUringClass = Class.forName("io.netty.channel.uring.IoUring");
            boolean available = (boolean) ioUringClass.getMethod("isAvailable").invoke(null);
            System.out.println("  IoUring.isAvailable() = " + available);

            if (!available) {
                Throwable cause = (Throwable) ioUringClass.getMethod("unavailabilityCause").invoke(null);
                System.out.println("  不可用原因: " + cause.getMessage());
                System.out.println("  ⚠ 需要 Linux 5.9+ 且 Java 9+");
            } else {
                System.out.println("  ✅ io_uring 可用");

                // 探测内核版本
                try {
                    Class<?> nativeClass = Class.forName("io.netty.channel.uring.Native");
                    String kernelVersion = (String) nativeClass.getMethod("kernelVersion").invoke(null);
                    System.out.println("  内核版本: " + kernelVersion);
                } catch (Exception e) {
                    System.out.println("  内核版本: 无法获取");
                }
            }
        } catch (ClassNotFoundException e) {
            System.out.println("  ⚠ IoUring 类未找到（可能缺少 transport-native-io_uring 依赖）");
        } catch (Exception e) {
            System.out.println("  ⚠ 检测异常: " + e.getMessage());
        }
    }

    /**
     * 验证 2：SQ/CQ 双队列架构
     * 文档断言：io_uring 核心 = SQ（提交 IO 请求）+ CQ（接收 IO 完成结果），共享内存 mmap
     */
    static void verifySqCqArchitecture() {
        System.out.println("\n--- 验证 2：SQ/CQ 双队列架构 ---");
        System.out.println("  io_uring 核心组件:");
        System.out.println("    ┌─────────────────────────────────────┐");
        System.out.println("    │  用户态                              │");
        System.out.println("    │  SQ Ring → 写入 SQE（IO 请求）      │");
        System.out.println("    │  CQ Ring ← 读取 CQE（IO 结果）      │");
        System.out.println("    └──────────────┬──────────────────────┘");
        System.out.println("                   │ mmap 共享内存");
        System.out.println("    ┌──────────────┴──────────────────────┐");
        System.out.println("    │  内核态                              │");
        System.out.println("    │  读取 SQE → 执行 IO → 写入 CQE      │");
        System.out.println("    └─────────────────────────────────────┘");

        // 验证 SubmissionQueue 和 CompletionQueue 类存在
        try {
            Class.forName("io.netty.channel.uring.SubmissionQueue");
            Class.forName("io.netty.channel.uring.CompletionQueue");
            Class.forName("io.netty.channel.uring.RingBuffer");
            System.out.println("  SubmissionQueue/CompletionQueue/RingBuffer: ✅ 类存在");
        } catch (ClassNotFoundException e) {
            System.out.println("  ⚠ io_uring 类未找到");
        }
    }

    /**
     * 验证 3：IoUringIoHandler 内部字段
     */
    static void verifyIoUringHandlerInternals() {
        System.out.println("\n--- 验证 3：IoUringIoHandler 内部字段 ---");
        try {
            Class<?> handlerClass = Class.forName("io.netty.channel.uring.IoUringIoHandler");
            String[] expectedFields = {"ringBuffer", "eventfd", "registrations"};
            for (String fieldName : expectedFields) {
                Field f = findField(handlerClass, fieldName);
                if (f != null) {
                    System.out.println("  字段 '" + fieldName + "': ✅ 存在 (类型=" + f.getType().getSimpleName() + ")");
                } else {
                    System.out.println("  字段 '" + fieldName + "': ⚠ 未找到（可能重命名）");
                }
            }
        } catch (ClassNotFoundException e) {
            System.out.println("  ⚠ IoUringIoHandler 类未找到，跳过");
        }
    }

    /**
     * 验证 4：io_uring Channel 继承体系
     */
    static void verifyIoUringChannelHierarchy() {
        System.out.println("\n--- 验证 4：io_uring Channel 继承体系 ---");
        try {
            Class<?> serverClass = Class.forName("io.netty.channel.uring.IoUringServerSocketChannel");
            Class<?> socketClass = Class.forName("io.netty.channel.uring.IoUringSocketChannel");

            System.out.println("  IoUringServerSocketChannel 继承链:");
            printClassChain(serverClass);
            System.out.println("  IoUringSocketChannel 继承链:");
            printClassChain(socketClass);

            // 验证不继承 NIO/Epoll 类
            boolean extendsNioOrEpoll = false;
            Class<?> c = serverClass;
            while (c != null) {
                String name = c.getSimpleName();
                if (name.contains("Nio") || name.contains("Epoll")) {
                    extendsNioOrEpoll = true;
                    break;
                }
                c = c.getSuperclass();
            }
            System.out.println("  继承 NIO/Epoll 类: " + (extendsNioOrEpoll
                ? "❌ 不应继承" : "✅ 独立继承链"));
        } catch (ClassNotFoundException e) {
            System.out.println("  ⚠ io_uring Channel 类未找到，跳过");
        }
    }

    /**
     * 验证 5：高级特性探测
     * 文档断言：不同内核版本支持不同特性（multishot accept、recv_multishot、buffer ring 等）
     */
    static void verifyFeatureProbe() {
        System.out.println("\n--- 验证 5：io_uring 高级特性 ---");
        try {
            Class<?> ioUringClass = Class.forName("io.netty.channel.uring.IoUring");
            boolean available = (boolean) ioUringClass.getMethod("isAvailable").invoke(null);
            if (!available) {
                System.out.println("  ⚠ io_uring 不可用，跳过特性探测");
                return;
            }

            String[] features = {
                "isSpliceSupported",
                "isSendZcSupported",
                "isAcceptMultishotSupported",
                "isRecvMultishotSupported",
                "isRegisterBufferRingSupported"
            };
            for (String feature : features) {
                try {
                    boolean supported = (boolean) ioUringClass.getMethod(feature).invoke(null);
                    System.out.println("  " + feature + ": " + (supported ? "✅ 支持" : "⬜ 不支持"));
                } catch (NoSuchMethodException e) {
                    System.out.println("  " + feature + ": ⚠ 方法未找到");
                }
            }
        } catch (Exception e) {
            System.out.println("  ⚠ 特性探测异常: " + e.getMessage());
        }
    }

    /**
     * 验证 6：三种 Transport 架构对比
     */
    static void verifyArchitectureComparison() {
        System.out.println("\n--- 验证 6：三种 Transport 架构对比 ---");
        System.out.println("  | 维度         | NIO                 | Epoll              | io_uring           |");
        System.out.println("  |-------------|---------------------|--------------------|--------------------|");
        System.out.println("  | IO 模型      | 就绪通知+同步IO      | 就绪通知+同步IO     | 异步提交+完成通知   |");
        System.out.println("  | 系统调用      | select→read/write   | epoll_wait→read    | io_uring_enter     |");
        System.out.println("  | 批量 IO      | ❌ 不支持            | sendmmsg           | SQ 批量提交        |");
        System.out.println("  | 零拷贝       | transferTo(sendfile) | splice             | splice             |");
        System.out.println("  | 触发模式      | LT                  | LT(Channel)/ET(fd) | N/A (异步模型)     |");
        System.out.println("  | 平台         | 跨平台               | Linux only         | Linux 5.9+ only    |");
    }

    /**
     * 验证 7：io_uring Echo 往返测试（可用时用 io_uring，否则 NIO）
     */
    @SuppressWarnings("unchecked")
    static void verifyIoUringEchoRoundtrip() throws Exception {
        System.out.println("\n--- 验证 7：Echo 往返测试 ---");

        boolean ioUringAvailable = false;
        try {
            Class<?> ioUringClass = Class.forName("io.netty.channel.uring.IoUring");
            ioUringAvailable = (boolean) ioUringClass.getMethod("isAvailable").invoke(null);
        } catch (Exception e) { /* ignore */ }

        IoHandlerFactory ioFactory;
        Class<? extends ServerChannel> serverChannelClass;
        Class<? extends Channel> clientChannelClass;
        String transport;

        if (ioUringAvailable) {
            Class<?> handlerClass = Class.forName("io.netty.channel.uring.IoUringIoHandler");
            ioFactory = (IoHandlerFactory) handlerClass.getMethod("newFactory").invoke(null);
            serverChannelClass = (Class<? extends ServerChannel>)
                Class.forName("io.netty.channel.uring.IoUringServerSocketChannel");
            clientChannelClass = (Class<? extends Channel>)
                Class.forName("io.netty.channel.uring.IoUringSocketChannel");
            transport = "io_uring";
        } else {
            ioFactory = NioIoHandler.newFactory();
            serverChannelClass = NioServerSocketChannel.class;
            clientChannelClass = NioSocketChannel.class;
            transport = "NIO (io_uring 不可用, fallback)";
        }

        System.out.println("  使用 Transport: " + transport);

        EventLoopGroup bossGroup = new MultiThreadIoEventLoopGroup(1, ioFactory);
        EventLoopGroup workerGroup = new MultiThreadIoEventLoopGroup(1, ioFactory);
        EventLoopGroup clientGroup = new MultiThreadIoEventLoopGroup(1, ioFactory);
        final CountDownLatch echoLatch = new CountDownLatch(1);
        final String[] received = {""};

        try {
            Channel server = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(serverChannelClass)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                ctx.writeAndFlush(msg); // echo
                            }
                        });
                    }
                })
                .bind(0).sync().channel();

            InetSocketAddress addr = (InetSocketAddress) server.localAddress();

            new Bootstrap()
                .group(clientGroup)
                .channel(clientChannelClass)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                received[0] = ((ByteBuf) msg).toString(CharsetUtil.UTF_8);
                                ((ByteBuf) msg).release();
                                echoLatch.countDown();
                            }
                        });
                    }
                })
                .connect(addr).sync().channel()
                .writeAndFlush(Unpooled.copiedBuffer("IoUringTest", CharsetUtil.UTF_8));

            echoLatch.await(5, TimeUnit.SECONDS);
            System.out.println("  发送: \"IoUringTest\" → 收到: \"" + received[0] + "\"");
            System.out.println("  验证: " + ("IoUringTest".equals(received[0]) ? "✅ Echo 成功" : "❌ 不匹配"));

            server.close().sync();
        } finally {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
            workerGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
            bossGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    static void printClassChain(Class<?> clazz) {
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            System.out.println("    " + c.getSimpleName());
            c = c.getSuperclass();
        }
    }

    private static Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
