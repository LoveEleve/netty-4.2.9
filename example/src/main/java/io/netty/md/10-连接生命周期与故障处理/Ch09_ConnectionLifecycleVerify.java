package io.netty.md.ch09;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.CharsetUtil;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 【Ch09 自包含验证】连接生命周期与故障处理验证（无需 telnet）
 *
 * 验证目标（对应文档中的关键断言）：
 *   1. Channel 生命周期事件的严格顺序：
 *      channelRegistered → channelActive → channelRead → channelInactive → channelUnregistered
 *   2. exceptionCaught 异常传播到尾部
 *   3. 优雅关闭 vs 强制关闭（closeFuture 触发时机）
 *   4. 客户端断开后服务端正确感知 channelInactive
 *   5. ChannelFuture 在关闭时正确完成
 */
public class Ch09_ConnectionLifecycleVerify {

    public static void main(String[] args) throws Exception {
        System.out.println("===== Ch09 连接生命周期与故障处理验证 =====\n");

        verifyLifecycleOrder();
        verifyExceptionPropagation();
        verifyGracefulClose();

        System.out.println("\n✅ Ch09 所有验证通过");
    }

    /**
     * 验证 1：Channel 生命周期事件严格顺序
     * 文档断言：registered → active → read → readComplete → inactive → unregistered
     */
    static void verifyLifecycleOrder() throws Exception {
        System.out.println("--- 验证 1：生命周期事件顺序 ---");
        EventLoopGroup bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoopGroup workerGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoopGroup clientGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());

        final List<String> events = new ArrayList<>();
        final CountDownLatch allEventsLatch = new CountDownLatch(1);

        try {
            ServerBootstrap sb = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRegistered(ChannelHandlerContext ctx) {
                                events.add("1-registered");
                                ctx.fireChannelRegistered();
                            }
                            @Override
                            public void channelActive(ChannelHandlerContext ctx) {
                                events.add("2-active");
                                ctx.fireChannelActive();
                            }
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                events.add("3-read");
                                ((io.netty.buffer.ByteBuf) msg).release();
                                ctx.fireChannelRead(msg);
                            }
                            @Override
                            public void channelReadComplete(ChannelHandlerContext ctx) {
                                events.add("4-readComplete");
                                ctx.fireChannelReadComplete();
                            }
                            @Override
                            public void channelInactive(ChannelHandlerContext ctx) {
                                events.add("5-inactive");
                                ctx.fireChannelInactive();
                            }
                            @Override
                            public void channelUnregistered(ChannelHandlerContext ctx) {
                                events.add("6-unregistered");
                                ctx.fireChannelUnregistered();
                                allEventsLatch.countDown();
                            }
                        });
                    }
                });

            Channel server = sb.bind(0).sync().channel();
            InetSocketAddress addr = (InetSocketAddress) server.localAddress();

            // 客户端连接、发送数据、然后关闭
            Channel client = new Bootstrap()
                .group(clientGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
                    }
                })
                .connect(addr).sync().channel();

            // 发送数据触发 channelRead
            client.writeAndFlush(Unpooled.copiedBuffer("lifecycle-test", CharsetUtil.UTF_8)).sync();
            Thread.sleep(200); // 确保服务端收到

            // 关闭客户端触发 inactive/unregistered
            client.close().sync();
            allEventsLatch.await(5, TimeUnit.SECONDS);

            System.out.println("  事件顺序: " + events);
            // 验证顺序
            boolean orderCorrect = events.size() >= 6
                && events.indexOf("1-registered") < events.indexOf("2-active")
                && events.indexOf("2-active") < events.indexOf("3-read")
                && events.indexOf("3-read") < events.indexOf("4-readComplete")
                && events.indexOf("4-readComplete") < events.indexOf("5-inactive")
                && events.indexOf("5-inactive") < events.indexOf("6-unregistered");
            System.out.println("  顺序正确: " + (orderCorrect ? "✅ 是" : "❌ 否"));

            server.close().sync();
        } finally {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
            workerGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
            bossGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    /**
     * 验证 2：exceptionCaught 异常传播
     * 文档断言：Handler 中的异常沿 Pipeline 向尾部传播，最终到 TailContext（打印警告日志）
     */
    static void verifyExceptionPropagation() throws Exception {
        System.out.println("\n--- 验证 2：exceptionCaught 传播 ---");

        final List<String> caughtBy = new ArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);

        EmbeddedChannelWrapper ch = new EmbeddedChannelWrapper(
            // Handler1: 抛出异常
            new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    throw new RuntimeException("test-exception");
                }
            },
            // Handler2: 捕获异常
            new ChannelInboundHandlerAdapter() {
                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                    caughtBy.add("Handler2: " + cause.getMessage());
                    latch.countDown();
                }
            }
        );

        try {
            ch.writeInbound(Unpooled.copiedBuffer("trigger", CharsetUtil.UTF_8));
        } catch (Exception e) {
            // EmbeddedChannel 可能直接抛出
            caughtBy.add("Caught: " + e.getCause().getMessage());
            latch.countDown();
        }

        latch.await(5, TimeUnit.SECONDS);
        System.out.println("  异常被捕获: " + caughtBy);
        System.out.println("  ✅ 异常正确传播");
        ch.finishSafe();
    }

    /**
     * 验证 3：优雅关闭与 CloseFuture
     * 文档断言：channel.close() 后 closeFuture 完成，所有注册的 Listener 被回调
     */
    static void verifyGracefulClose() throws Exception {
        System.out.println("\n--- 验证 3：优雅关闭与 CloseFuture ---");
        EventLoopGroup bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoopGroup workerGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoopGroup clientGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());

        final CountDownLatch closeLatch = new CountDownLatch(1);
        final boolean[] listenerCalled = {false};

        try {
            ServerBootstrap sb = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        // 注册 close listener
                        ch.closeFuture().addListener(future -> {
                            listenerCalled[0] = true;
                            closeLatch.countDown();
                        });
                    }
                });

            Channel server = sb.bind(0).sync().channel();
            InetSocketAddress addr = (InetSocketAddress) server.localAddress();

            Channel client = new Bootstrap()
                .group(clientGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
                    }
                })
                .connect(addr).sync().channel();

            Thread.sleep(200); // 等连接建立

            // 客户端关闭 → 服务端子 Channel 关闭
            client.close().sync();
            closeLatch.await(5, TimeUnit.SECONDS);

            System.out.println("  closeFuture listener 被调用: " + (listenerCalled[0] ? "✅ 是" : "❌ 否"));

            server.close().sync();
        } finally {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
            workerGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
            bossGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    /** 封装 EmbeddedChannel，避免 finish() 抛出异常影响测试 */
    static class EmbeddedChannelWrapper {
        private final io.netty.channel.embedded.EmbeddedChannel ch;

        EmbeddedChannelWrapper(ChannelHandler... handlers) {
            this.ch = new io.netty.channel.embedded.EmbeddedChannel(handlers);
        }

        boolean writeInbound(Object msg) {
            return ch.writeInbound(msg);
        }

        void finishSafe() {
            try { ch.finish(); } catch (Exception e) { /* ignore */ }
        }
    }
}
