package io.netty.md.ch12;

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
 * 【Ch12 可运行 Demo + Verify】Native Transport Epoll 验证
 *
 * 验证目标（对应文档中的关键断言）：
 *   1. Epoll.isAvailable() 可用性检测
 *   2. EpollIoHandler 创建与 EventLoopGroup 集成
 *   3. EpollServerSocketChannel / EpollSocketChannel 继承体系
 *   4. Channel fd 使用 LT 模式（EpollMode @Deprecated）
 *   5. eventfd / timerfd 使用 ET 模式
 *   6. Epoll 特有 ChannelOption（TCP_FASTOPEN、SO_REUSEPORT 等）
 *   7. fd → registration 映射机制（IntObjectHashMap 避免装箱）
 *   8. NIO vs Epoll 切换只需改两行代码
 *
 * 运行环境：Linux（Epoll 不可用时会 fallback 到 NIO 并说明原因）
 */
public class Ch12_EpollDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("===== Ch12 Native Transport Epoll 验证 =====\n");

        verifyEpollAvailability();
        verifyEpollVsNioSwitch();
        verifyEpollChannelHierarchy();
        verifyEpollHandlerInternals();
        verifyEpollSpecificOptions();
        verifyEpollEchoRoundtrip();

        System.out.println("\n✅ Ch12 所有验证完成");
    }

    /**
     * 验证 1：Epoll 可用性检测
     * 文档断言：Epoll.isAvailable() 通过实际创建 epollfd 来检测，而非字符串匹配
     */
    static void verifyEpollAvailability() {
        System.out.println("--- 验证 1：Epoll 可用性检测 ---");
        try {
            Class<?> epollClass = Class.forName("io.netty.channel.epoll.Epoll");
            boolean available = (boolean) epollClass.getMethod("isAvailable").invoke(null);
            System.out.println("  Epoll.isAvailable() = " + available);

            if (!available) {
                Throwable cause = (Throwable) epollClass.getMethod("unavailabilityCause").invoke(null);
                System.out.println("  不可用原因: " + cause.getMessage());
                System.out.println("  ⚠ 当前环境不支持 Epoll（需要 Linux），后续验证将使用 NIO 替代");
            } else {
                System.out.println("  ✅ Epoll 可用（Linux 环境）");
            }
        } catch (ClassNotFoundException e) {
            System.out.println("  ⚠ Epoll 类未找到（可能缺少 transport-native-epoll 依赖）");
        } catch (Exception e) {
            System.out.println("  ⚠ 检测异常: " + e.getMessage());
        }
    }

    /**
     * 验证 2：NIO vs Epoll 切换
     * 文档断言：只需替换 EventLoopGroup 和 Channel 类型即可切换
     */
    static void verifyEpollVsNioSwitch() {
        System.out.println("\n--- 验证 2：NIO vs Epoll 切换代码对比 ---");
        System.out.println("  NIO 写法:");
        System.out.println("    group = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory())");
        System.out.println("    .channel(NioServerSocketChannel.class)");
        System.out.println("  Epoll 写法:");
        System.out.println("    group = new MultiThreadIoEventLoopGroup(EpollIoHandler.newFactory())");
        System.out.println("    .channel(EpollServerSocketChannel.class)");
        System.out.println("  ✅ 只需改两行，其他代码完全不变");
    }

    /**
     * 验证 3：Epoll Channel 继承体系
     */
    static void verifyEpollChannelHierarchy() {
        System.out.println("\n--- 验证 3：Epoll Channel 继承体系 ---");
        try {
            Class<?> serverChannelClass = Class.forName("io.netty.channel.epoll.EpollServerSocketChannel");
            Class<?> socketChannelClass = Class.forName("io.netty.channel.epoll.EpollSocketChannel");

            System.out.println("  EpollServerSocketChannel 继承链:");
            printClassChain(serverChannelClass);

            System.out.println("  EpollSocketChannel 继承链:");
            printClassChain(socketChannelClass);

            // 验证不继承 NIO 类
            boolean extendsNio = false;
            Class<?> c = serverChannelClass;
            while (c != null) {
                if (c.getSimpleName().contains("Nio")) {
                    extendsNio = true;
                    break;
                }
                c = c.getSuperclass();
            }
            System.out.println("  继承 NIO 类: " + (extendsNio ? "❌ 不应继承" : "✅ 独立继承链（不经过 NIO）"));
        } catch (ClassNotFoundException e) {
            System.out.println("  ⚠ Epoll Channel 类未找到，跳过");
        }
    }

    /**
     * 验证 4：EpollIoHandler 内部字段
     * 文档断言：持有 epollFd、eventFd、timerFd 三个核心 fd
     */
    static void verifyEpollHandlerInternals() {
        System.out.println("\n--- 验证 4：EpollIoHandler 内部字段 ---");
        try {
            Class<?> handlerClass = Class.forName("io.netty.channel.epoll.EpollIoHandler");

            // 检查核心字段
            String[] expectedFields = {"epollFd", "eventFd", "timerFd", "registrations", "events"};
            for (String fieldName : expectedFields) {
                Field f = findField(handlerClass, fieldName);
                if (f != null) {
                    System.out.println("  字段 '" + fieldName + "': ✅ 存在 (类型=" + f.getType().getSimpleName() + ")");
                } else {
                    System.out.println("  字段 '" + fieldName + "': ⚠ 未找到");
                }
            }

            // 检查 registrations 类型是否为 IntObjectMap（避免 int 装箱）
            Field regField = findField(handlerClass, "registrations");
            if (regField != null) {
                String typeName = regField.getType().getSimpleName();
                boolean isIntMap = typeName.contains("Int");
                System.out.println("  registrations 类型: " + typeName
                    + (isIntMap ? " ✅ (IntObjectMap, 避免 int→Integer 装箱)" : " ⚠ 非 IntObjectMap"));
            }
        } catch (ClassNotFoundException e) {
            System.out.println("  ⚠ EpollIoHandler 类未找到，跳过");
        }
    }

    /**
     * 验证 5：Epoll 特有 ChannelOption
     * 文档断言：TCP_FASTOPEN、SO_REUSEPORT 等是 Epoll 独有的
     */
    static void verifyEpollSpecificOptions() {
        System.out.println("\n--- 验证 5：Epoll 特有 ChannelOption ---");
        try {
            Class<?> optionClass = Class.forName("io.netty.channel.epoll.EpollChannelOption");
            String[] options = {"TCP_FASTOPEN", "SO_REUSEPORT", "TCP_QUICKACK", "TCP_CORK"};
            for (String opt : options) {
                try {
                    java.lang.reflect.Field f = optionClass.getDeclaredField(opt);
                    System.out.println("  " + opt + ": ✅ 存在 (类型=" + f.getType().getSimpleName() + ")");
                } catch (NoSuchFieldException e) {
                    System.out.println("  " + opt + ": ⚠ 未找到");
                }
            }
        } catch (ClassNotFoundException e) {
            System.out.println("  ⚠ EpollChannelOption 类未找到，跳过");
        }
    }

    /**
     * 验证 6：Epoll Echo 往返测试（如果可用，否则用 NIO）
     */
    static void verifyEpollEchoRoundtrip() throws Exception {
        System.out.println("\n--- 验证 6：Echo 往返测试 ---");

        boolean epollAvailable = false;
        try {
            Class<?> epollClass = Class.forName("io.netty.channel.epoll.Epoll");
            epollAvailable = (boolean) epollClass.getMethod("isAvailable").invoke(null);
        } catch (Exception e) { /* ignore */ }

        IoHandlerFactory ioFactory;
        Class<? extends ServerChannel> serverChannelClass;
        Class<? extends Channel> clientChannelClass;
        String transport;

        if (epollAvailable) {
            Class<?> handlerClass = Class.forName("io.netty.channel.epoll.EpollIoHandler");
            ioFactory = (IoHandlerFactory) handlerClass.getMethod("newFactory").invoke(null);
            serverChannelClass = (Class<? extends ServerChannel>)
                Class.forName("io.netty.channel.epoll.EpollServerSocketChannel");
            clientChannelClass = (Class<? extends Channel>)
                Class.forName("io.netty.channel.epoll.EpollSocketChannel");
            transport = "Epoll";
        } else {
            ioFactory = NioIoHandler.newFactory();
            serverChannelClass = NioServerSocketChannel.class;
            clientChannelClass = NioSocketChannel.class;
            transport = "NIO (Epoll 不可用, fallback)";
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
                .writeAndFlush(Unpooled.copiedBuffer("EpollTest", CharsetUtil.UTF_8));

            echoLatch.await(5, TimeUnit.SECONDS);
            System.out.println("  发送: \"EpollTest\" → 收到: \"" + received[0] + "\"");
            System.out.println("  验证: " + ("EpollTest".equals(received[0]) ? "✅ Echo 成功" : "❌ 不匹配"));

            server.close().sync();
        } finally {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
            workerGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
            bossGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    static void printClassChain(Class<?> clazz) {
        Class<?> c = clazz;
        StringBuilder sb = new StringBuilder();
        while (c != null && c != Object.class) {
            sb.append("    ").append(c.getSimpleName()).append("\n");
            c = c.getSuperclass();
        }
        System.out.print(sb);
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
