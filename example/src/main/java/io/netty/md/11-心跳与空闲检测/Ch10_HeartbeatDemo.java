package io.netty.md.ch10;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.CharsetUtil;

import java.util.concurrent.TimeUnit;

/**
 * 【Ch10 可运行 Demo】心跳与空闲检测验证
 *
 * 验证目标：
 *   1. IdleStateHandler 的读空闲/写空闲/全空闲检测
 *   2. 空闲事件触发后发送心跳 PING
 *   3. 连续 N 次空闲后主动关闭连接
 *
 * 运行方式：运行 main 方法后 telnet localhost 8100，保持不发送数据观察空闲检测
 */
public class Ch10_HeartbeatDemo {

    private static final int READER_IDLE_SECONDS = 5;
    private static final int MAX_IDLE_COUNT = 3;

    public static void main(String[] args) throws Exception {
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(group)
             .channel(NioServerSocketChannel.class)
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 protected void initChannel(SocketChannel ch) {
                     ch.pipeline()
                       // 读空闲5秒触发一次 IdleStateEvent
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
                                           System.out.println("[空闲检测] 超过最大空闲次数，关闭连接: " + ctx.channel().remoteAddress());
                                           ctx.close();
                                       } else {
                                           // 发送心跳 PING
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
                               // 收到任何数据都重置空闲计数
                               idleCount = 0;
                               System.out.println("[收到数据] 空闲计数已重置");
                               ctx.fireChannelRead(msg);
                           }
                       });
                 }
             });

            ChannelFuture f = b.bind(8100).sync();
            System.out.println(">>> 心跳 Demo 服务器已启动，端口 8100 <<<");
            System.out.println(">>> telnet localhost 8100，不发数据等待 " + READER_IDLE_SECONDS + " 秒观察空闲检测 <<<");
            System.out.println(">>> 连续空闲 " + MAX_IDLE_COUNT + " 次后连接将被关闭 <<<");
            f.channel().closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }
}
