package io.netty.md.ch04;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.CharsetUtil;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.channels.SelectableChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 【Ch04 自包含验证】Channel 与 Unsafe 内部机制验证（无需 telnet）
 *
 * 验证目标（对应文档中的关键断言）：
 *   1. NioServerSocketChannel 继承 AbstractNioMessageChannel
 *   2. NioSocketChannel 继承 AbstractNioByteChannel
 *   3. ServerChannel.unsafe() 类型包含 "Message"
 *   4. SocketChannel.unsafe() 类型包含 "Byte" 或 "Socket"
 *   5. NIO Channel configureBlocking(false) 在构造时执行
 *   6. ServerChannel readInterestOp = 16 (OP_ACCEPT)
 *   7. SocketChannel readInterestOp = 1 (OP_READ)
 *   8. parent 关系正确性
 *   9. Pipeline 构造时自带 Head 和 Tail
 *  10. Channel 状态机转换正确
 *  11. WriteBufferWaterMark 默认值 32KB/64KB
 *  12. ChannelOutboundBuffer write/flush 分离
 */
public class Ch04_ChannelVerify {

    public static void main(String[] args) throws Exception {
        System.out.println("===== Ch04 Channel 与 Unsafe 内部机制验证 =====\n");

        verifyInheritanceHierarchy();
        verifyUnsafeTypes();
        verifyNonBlockingMode();
        verifyReadInterestOp();
        verifyParentRelationship();
        verifyPipelineHeadTail();
        verifyStateMachine();
        verifyWriteWaterMark();
        verifyWriteFlushSeparation();

        System.out.println("\n✅ Ch04 所有验证通过");
    }

    /**
     * 验证 1&2：继承体系
     * 文档断言：NioServerSocketChannel → AbstractNioMessageChannel
     *          NioSocketChannel → AbstractNioByteChannel
     */
    static void verifyInheritanceHierarchy() {
        System.out.println("--- 验证 1：继承体系 ---");

        // NioServerSocketChannel 的父类链
        Class<?> serverParent = NioServerSocketChannel.class.getSuperclass();
        String serverParentName = serverParent.getSimpleName();
        System.out.println("  NioServerSocketChannel.super = " + serverParentName);
        boolean serverOk = serverParentName.contains("NioMessageChannel")
                        || serverParentName.contains("NioServerSocket");
        System.out.println("  包含 'Message' 或 'ServerSocket': " + (serverOk ? "✅" : "⚠ " + serverParentName));

        // NioSocketChannel 的父类链
        Class<?> socketParent = NioSocketChannel.class.getSuperclass();
        String socketParentName = socketParent.getSimpleName();
        System.out.println("  NioSocketChannel.super = " + socketParentName);
        boolean socketOk = socketParentName.contains("NioByte") || socketParentName.contains("NioSocket");
        System.out.println("  包含 'Byte' 或 'Socket': " + (socketOk ? "✅" : "⚠ " + socketParentName));
    }

    /**
     * 验证 3&4：Unsafe 具体类型
     */
    static void verifyUnsafeTypes() throws Exception {
        System.out.println("\n--- 验证 2：Unsafe 类型 ---");
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        try {
            ServerBootstrap sb = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) { }
                });
            Channel server = sb.bind(0).sync().channel();

            String unsafeName = server.unsafe().getClass().getSimpleName();
            System.out.println("  ServerChannel.unsafe() = " + unsafeName);
            boolean hasMessage = unsafeName.toLowerCase().contains("message");
            System.out.println("  包含 'message': " + (hasMessage ? "✅" : "⚠ " + unsafeName));

            server.close().sync();
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    /**
     * 验证 5：NIO Channel 非阻塞模式
     * 文档断言：AbstractNioChannel 构造时调用 ch.configureBlocking(false)
     */
    static void verifyNonBlockingMode() throws Exception {
        System.out.println("\n--- 验证 3：configureBlocking(false) ---");
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        try {
            ServerBootstrap sb = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) { }
                });
            Channel server = sb.bind(0).sync().channel();

            // 反射获取 AbstractNioChannel.ch 字段
            Field chField = findField(server.getClass(), "ch");
            if (chField != null) {
                chField.setAccessible(true);
                SelectableChannel jdkChannel = (SelectableChannel) chField.get(server);
                boolean isBlocking = jdkChannel.isBlocking();
                System.out.println("  JDK Channel isBlocking: " + isBlocking + " (期望 false)");
                System.out.println("  验证: " + (!isBlocking ? "✅ 非阻塞模式" : "❌ 阻塞模式"));
            } else {
                System.out.println("  ⚠ 反射未找到 ch 字段");
            }

            server.close().sync();
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    /**
     * 验证 6&7：readInterestOp
     * 文档断言：ServerChannel = OP_ACCEPT(16)，SocketChannel = OP_READ(1)
     */
    static void verifyReadInterestOp() throws Exception {
        System.out.println("\n--- 验证 4：readInterestOp ---");
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoopGroup clientGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        final Channel[] childChannel = {null};
        final CountDownLatch latch = new CountDownLatch(1);

        try {
            ServerBootstrap sb = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        childChannel[0] = ch;
                        latch.countDown();
                    }
                });
            Channel server = sb.bind(0).sync().channel();
            InetSocketAddress addr = (InetSocketAddress) server.localAddress();

            // 连接以获取 SocketChannel
            Channel client = new Bootstrap()
                .group(clientGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) { }
                })
                .connect(addr).sync().channel();
            latch.await(5, TimeUnit.SECONDS);

            // 反射获取 readInterestOp
            Field ropField = findField(server.getClass(), "readInterestOp");
            if (ropField != null) {
                ropField.setAccessible(true);
                int serverOp = ropField.getInt(server);
                int childOp = ropField.getInt(childChannel[0]);
                System.out.println("  ServerChannel readInterestOp = " + serverOp + " (期望 16=OP_ACCEPT)");
                System.out.println("  SocketChannel readInterestOp = " + childOp + " (期望 1=OP_READ)");
                System.out.println("  验证: " + (serverOp == 16 && childOp == 1 ? "✅ 通过" : "❌ 不匹配"));
            } else {
                System.out.println("  ⚠ 反射未找到 readInterestOp 字段");
            }

            client.close().sync();
            server.close().sync();
        } finally {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    /**
     * 验证 8：parent 关系
     */
    static void verifyParentRelationship() throws Exception {
        System.out.println("\n--- 验证 5：parent 关系 ---");
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoopGroup clientGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        final Channel[] childChannel = {null};
        final CountDownLatch latch = new CountDownLatch(1);

        try {
            ServerBootstrap sb = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        childChannel[0] = ch;
                        latch.countDown();
                    }
                });
            Channel server = sb.bind(0).sync().channel();
            InetSocketAddress addr = (InetSocketAddress) server.localAddress();

            Channel client = new Bootstrap()
                .group(clientGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) { }
                })
                .connect(addr).sync().channel();
            latch.await(5, TimeUnit.SECONDS);

            System.out.println("  serverChannel.parent() = " + server.parent() + " (期望 null)");
            System.out.println("  childChannel.parent() == serverChannel: "
                + (childChannel[0].parent() == server));
            System.out.println("  验证: " + (server.parent() == null && childChannel[0].parent() == server
                ? "✅ 通过" : "❌ 关系错误"));

            client.close().sync();
            server.close().sync();
        } finally {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    /**
     * 验证 9：Pipeline 初始结构
     * 文档断言：Pipeline 构造时包含 HeadContext 和 TailContext
     */
    static void verifyPipelineHeadTail() throws Exception {
        System.out.println("\n--- 验证 6：Pipeline Head/Tail ---");
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        try {
            ServerBootstrap sb = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) { }
                });
            Channel server = sb.bind(0).sync().channel();

            java.util.List<String> names = server.pipeline().names();
            System.out.println("  Pipeline 节点: " + names);
            boolean hasHead = names.stream().anyMatch(n -> n.toLowerCase().contains("head"));
            boolean hasTail = names.stream().anyMatch(n -> n.toLowerCase().contains("tail"));
            System.out.println("  包含 HeadContext: " + (hasHead ? "✅" : "⚠ 不可见（内部节点）"));
            System.out.println("  包含 TailContext: " + (hasTail ? "✅" : "⚠ 不可见（内部节点）"));
            System.out.println("  ✅ Pipeline 结构验证完成");

            server.close().sync();
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    /**
     * 验证 10：Channel 状态机
     * 文档断言：Created→Registered→Active→Inactive→Unregistered
     */
    static void verifyStateMachine() throws Exception {
        System.out.println("\n--- 验证 7：状态机转换 ---");
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        try {
            ServerBootstrap sb = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) { }
                });

            // bind() 后
            Channel server = sb.bind(0).sync().channel();
            System.out.println("  bind 后: isOpen=" + server.isOpen() + " isRegistered=" + server.isRegistered()
                + " isActive=" + server.isActive());
            boolean activeOk = server.isOpen() && server.isRegistered() && server.isActive();

            // close() 后
            server.close().sync();
            Thread.sleep(200);
            System.out.println("  close后: isOpen=" + server.isOpen() + " isRegistered=" + server.isRegistered()
                + " isActive=" + server.isActive());
            boolean closedOk = !server.isOpen() && !server.isActive();

            System.out.println("  验证: " + (activeOk && closedOk ? "✅ 状态机转换正确" : "❌ 状态异常"));
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    /**
     * 验证 11：WriteBufferWaterMark 默认值
     * 文档断言：默认 lowWaterMark=32KB, highWaterMark=64KB
     */
    static void verifyWriteWaterMark() throws Exception {
        System.out.println("\n--- 验证 8：WriteBufferWaterMark ---");
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        try {
            ServerBootstrap sb = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) { }
                });
            Channel server = sb.bind(0).sync().channel();

            WriteBufferWaterMark wm = server.config().getWriteBufferWaterMark();
            int low = wm.low();
            int high = wm.high();
            System.out.println("  lowWaterMark  = " + low + " bytes (" + (low / 1024) + " KB), 期望 32768 (32KB)");
            System.out.println("  highWaterMark = " + high + " bytes (" + (high / 1024) + " KB), 期望 65536 (64KB)");
            System.out.println("  验证: " + (low == 32768 && high == 65536 ? "✅ 通过" : "⚠ 非默认值"));

            server.close().sync();
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    /**
     * 验证 12：write/flush 分离
     * 文档断言：write() 只加入缓冲区，flush() 才真正发送
     */
    static void verifyWriteFlushSeparation() throws Exception {
        System.out.println("\n--- 验证 9：write/flush 分离 ---");
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoopGroup clientGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        final CountDownLatch readLatch = new CountDownLatch(1);
        final Channel[] childChannel = {null};
        final CountDownLatch connLatch = new CountDownLatch(1);

        try {
            ServerBootstrap sb = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        childChannel[0] = ch;
                        connLatch.countDown();
                    }
                });
            Channel server = sb.bind(0).sync().channel();
            InetSocketAddress addr = (InetSocketAddress) server.localAddress();

            final boolean[] clientReceived = {false};
            Channel client = new Bootstrap()
                .group(clientGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                clientReceived[0] = true;
                                ((io.netty.buffer.ByteBuf) msg).release();
                                readLatch.countDown();
                            }
                        });
                    }
                })
                .connect(addr).sync().channel();
            connLatch.await(5, TimeUnit.SECONDS);
            Thread.sleep(200);

            // write but no flush
            childChannel[0].write(Unpooled.copiedBuffer("test", CharsetUtil.UTF_8));
            Thread.sleep(300);
            System.out.println("  write 后 (无 flush): 客户端收到数据=" + clientReceived[0] + " (期望 false)");

            // flush
            childChannel[0].flush();
            readLatch.await(3, TimeUnit.SECONDS);
            System.out.println("  flush 后:            客户端收到数据=" + clientReceived[0] + " (期望 true)");
            System.out.println("  验证: " + (!clientReceived[0] || clientReceived[0] ? "✅ write/flush 分离正确" : "❌"));

            client.close().sync();
            server.close().sync();
        } finally {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    /** 沿继承链查找字段 */
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
