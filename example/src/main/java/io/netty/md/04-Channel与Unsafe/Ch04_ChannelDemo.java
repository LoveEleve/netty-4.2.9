package io.netty.md.ch04;

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

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 【Ch04 可运行 Demo】Channel 与 Unsafe 机制演示
 *
 * 演示目标：
 *   1. Channel 继承体系（打印类名验证 NioServerSocketChannel → AbstractNioMessageChannel）
 *   2. Unsafe 具体类型（NioMessageUnsafe vs NioByteUnsafe/NioSocketChannelUnsafe）
 *   3. Channel 状态机：isOpen / isRegistered / isActive 的变化
 *   4. parent 关系：SocketChannel.parent() == ServerSocketChannel
 *   5. Pipeline 结构：Head ↔ 用户Handler ↔ Tail
 *   6. write/flush 分离：write 不触发 IO，flush 才真正发送
 *
 * 运行方式：直接运行 main 方法，自包含（自动建立连接并验证）
 */
public class Ch04_ChannelDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("===== Ch04 Channel 与 Unsafe 演示 =====\n");

        EventLoopGroup bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoopGroup workerGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoopGroup clientGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());

        final CountDownLatch connectedLatch = new CountDownLatch(1);
        final Channel[] serverChildChannel = {null};

        try {
            ServerBootstrap sb = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        serverChildChannel[0] = ch;
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelActive(ChannelHandlerContext ctx) {
                                connectedLatch.countDown();
                                ctx.fireChannelActive();
                            }

                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                ByteBuf buf = (ByteBuf) msg;
                                System.out.println("[服务端] 收到: " + buf.toString(CharsetUtil.UTF_8));
                                ctx.writeAndFlush(Unpooled.copiedBuffer("Echo!", CharsetUtil.UTF_8));
                                buf.release();
                            }
                        });
                    }
                });

            Channel serverChannel = sb.bind(0).sync().channel();
            InetSocketAddress addr = (InetSocketAddress) serverChannel.localAddress();

            // === 1. ServerSocketChannel 继承体系 ===
            System.out.println("--- 1. Channel 继承体系 ---");
            printClassHierarchy("ServerChannel", serverChannel);

            // === 2. ServerChannel 的 Unsafe 类型 ===
            System.out.println("\n--- 2. Unsafe 类型 ---");
            System.out.println("  ServerChannel.unsafe(): " + serverChannel.unsafe().getClass().getSimpleName());

            // === 3. ServerChannel 状态 ===
            System.out.println("\n--- 3. ServerChannel 状态机 ---");
            printChannelState("ServerChannel", serverChannel);

            // 客户端连接
            Channel clientChannel = new Bootstrap()
                .group(clientGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                ByteBuf buf = (ByteBuf) msg;
                                System.out.println("[客户端] 收到回复: " + buf.toString(CharsetUtil.UTF_8));
                                buf.release();
                            }
                        });
                    }
                })
                .connect(addr).sync().channel();

            connectedLatch.await(5, TimeUnit.SECONDS);
            Thread.sleep(200); // 等连接完全建立

            // === 4. SocketChannel 继承体系 ===
            System.out.println("\n--- 4. SocketChannel 继承体系 ---");
            printClassHierarchy("ChildChannel", serverChildChannel[0]);

            // === 5. SocketChannel 的 Unsafe 类型 ===
            System.out.println("\n  ChildChannel.unsafe(): " + serverChildChannel[0].unsafe().getClass().getSimpleName());

            // === 6. parent 关系 ===
            System.out.println("\n--- 5. parent 关系 ---");
            Channel parent = serverChildChannel[0].parent();
            System.out.println("  childChannel.parent() == serverChannel: " + (parent == serverChannel));
            System.out.println("  serverChannel.parent(): " + serverChannel.parent() + " (期望 null)");

            // === 7. Pipeline 结构 ===
            System.out.println("\n--- 6. Pipeline 结构 ---");
            System.out.println("  ServerChannel Pipeline: " + serverChannel.pipeline().names());
            System.out.println("  ChildChannel  Pipeline: " + serverChildChannel[0].pipeline().names());

            // === 8. ChildChannel 状态 ===
            System.out.println("\n--- 7. ChildChannel 状态机 ---");
            printChannelState("ChildChannel", serverChildChannel[0]);

            // === 9. write/flush 分离演示 ===
            System.out.println("\n--- 8. write/flush 分离 ---");
            System.out.println("  isWritable 写入前: " + serverChildChannel[0].isWritable());

            // write 但不 flush —— 数据只进入 ChannelOutboundBuffer
            ChannelFuture wf = serverChildChannel[0].write(
                Unpooled.copiedBuffer("WriteOnly", CharsetUtil.UTF_8));
            System.out.println("  write() 后 (未flush): writeFuture.isDone=" + wf.isDone());

            // flush 触发真正发送
            serverChildChannel[0].flush();
            Thread.sleep(200);
            System.out.println("  flush() 后: writeFuture.isDone=" + wf.isDone()
                + " isSuccess=" + wf.isSuccess());

            // === 10. 客户端发送触发 channelRead ===
            System.out.println("\n--- 9. 客户端发送 ---");
            clientChannel.writeAndFlush(Unpooled.copiedBuffer("Hello!", CharsetUtil.UTF_8)).sync();
            Thread.sleep(300);

            // 关闭
            clientChannel.close().sync();
            Thread.sleep(200);

            // === 11. 关闭后状态 ===
            System.out.println("\n--- 10. 关闭后状态 ---");
            printChannelState("ChildChannel(关闭后)", serverChildChannel[0]);

            serverChannel.close().sync();
        } finally {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
            workerGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
            bossGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }

        System.out.println("\n✅ Ch04 演示完成");
    }

    /** 打印 Channel 的继承体系 */
    static void printClassHierarchy(String label, Channel ch) {
        System.out.println("  " + label + " 类型: " + ch.getClass().getSimpleName());
        Class<?> c = ch.getClass().getSuperclass();
        StringBuilder sb = new StringBuilder();
        while (c != null && c != Object.class) {
            sb.append("    → ").append(c.getSimpleName()).append("\n");
            c = c.getSuperclass();
        }
        System.out.print(sb);
    }

    /** 打印 Channel 状态 */
    static void printChannelState(String label, Channel ch) {
        System.out.println("  " + label + ": isOpen=" + ch.isOpen()
            + " isRegistered=" + ch.isRegistered()
            + " isActive=" + ch.isActive()
            + " isWritable=" + ch.isWritable());
    }
}
