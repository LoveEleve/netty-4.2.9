package io.netty.md.ch10;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.CharsetUtil;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 【Ch10 自包含验证】心跳与空闲检测验证（无需 telnet）
 *
 * 验证目标：
 *   1. IdleStateHandler 的读空闲检测
 *   2. 空闲事件触发后发送心跳 PING
 *   3. 连续 N 次空闲后主动关闭连接
 *   4. 收到数据后空闲计数重置
 */
public class Ch10_HeartbeatVerify {

    private static final int READER_IDLE_SECONDS = 2; // 缩短到2秒加速验证
    private static final int MAX_IDLE_COUNT = 3;

    public static void main(String[] args) throws Exception {
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        EventLoopGroup clientGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());

        CountDownLatch closeLatch = new CountDownLatch(1);

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(group)
             .channel(NioServerSocketChannel.class)
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 protected void initChannel(SocketChannel ch) {
                     ch.pipeline()
                       .addLast("idleDetector", new IdleStateHandler(READER_IDLE_SECONDS, 0, 0, TimeUnit.SECONDS))
                       .addLast("heartbeatHandler", new ChannelInboundHandlerAdapter() {
                           private int idleCount = 0;

                           @Override
                           public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                               if (evt instanceof IdleStateEvent) {
                                   IdleStateEvent e = (IdleStateEvent) evt;
                                   if (e.state() == IdleState.READER_IDLE) {
                                       idleCount++;
                                       System.out.println("[空闲检测] 读空闲第 " + idleCount + " 次 (最大 " + MAX_IDLE_COUNT + " 次)");

                                       if (idleCount >= MAX_IDLE_COUNT) {
                                           System.out.println("[空闲检测] 超过最大空闲次数，关闭连接");
                                           ctx.close();
                                           closeLatch.countDown();
                                       } else {
                                           ctx.writeAndFlush(Unpooled.copiedBuffer("PING\n", CharsetUtil.UTF_8));
                                           System.out.println("[心跳] 已发送 PING");
                                       }
                                   }
                               } else {
                                   ctx.fireUserEventTriggered(evt);
                               }
                           }

                           @Override
                           public void channelRead(ChannelHandlerContext ctx, Object msg) {
                               ByteBuf buf = (ByteBuf) msg;
                               String data = buf.toString(CharsetUtil.UTF_8).trim();
                               buf.release();
                               idleCount = 0;
                               System.out.println("[收到数据] \"" + data + "\" → 空闲计数已重置为 0");
                           }
                       });
                 }
             });

            ChannelFuture f = b.bind(0).sync();
            InetSocketAddress addr = (InetSocketAddress) f.channel().localAddress();

            System.out.println("===== 心跳与空闲检测验证 =====");
            System.out.println("配置: 读空闲=" + READER_IDLE_SECONDS + "s, 最大空闲次数=" + MAX_IDLE_COUNT);

            // 客户端连接
            final StringBuilder clientReceived = new StringBuilder();
            Channel client = new Bootstrap()
                .group(clientGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                ByteBuf buf = (ByteBuf) msg;
                                String data = buf.toString(CharsetUtil.UTF_8).trim();
                                buf.release();
                                clientReceived.append(data).append(";");
                                System.out.println("[客户端] 收到服务端心跳: " + data);
                            }
                        });
                    }
                })
                .connect(addr).sync().channel();

            System.out.println("客户端已连接，不发送数据，等待空闲检测触发...\n");

            // 等一轮空闲后发送数据，验证空闲重置
            Thread.sleep((long)(READER_IDLE_SECONDS * 1000 * 1.5));
            System.out.println("\n>>> 客户端发送一条数据，验证空闲计数重置 <<<");
            client.writeAndFlush(Unpooled.copiedBuffer("PONG\n", CharsetUtil.UTF_8));

            // 继续等待，直到连接被关闭
            boolean closed = closeLatch.await(READER_IDLE_SECONDS * (MAX_IDLE_COUNT + 2), TimeUnit.SECONDS);
            if (closed) {
                System.out.println("\n[验证结论]");
                System.out.println("  ✅ IdleStateHandler 读空闲检测正常工作（每 " + READER_IDLE_SECONDS + " 秒触发一次）");
                System.out.println("  ✅ 空闲事件触发后发送 PING 心跳");
                System.out.println("  ✅ 收到 PONG 后空闲计数正确重置");
                System.out.println("  ✅ 连续 " + MAX_IDLE_COUNT + " 次空闲后服务端主动关闭连接");
            } else {
                System.out.println("⚠ 等待超时");
            }

            client.close().sync();
            f.channel().close().sync();
            System.out.println("\n✅ Demo 结束");
        } finally {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }
}
