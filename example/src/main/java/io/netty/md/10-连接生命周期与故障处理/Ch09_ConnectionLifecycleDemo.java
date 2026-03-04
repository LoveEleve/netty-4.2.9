package io.netty.md.ch09;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

/**
 * 【Ch09 可运行 Demo】连接生命周期与故障处理验证
 *
 * 验证目标：
 *   1. 完整的 Channel 生命周期事件顺序：
 *      channelRegistered → channelActive → channelRead → channelInactive → channelUnregistered
 *   2. exceptionCaught 异常传播机制
 *   3. 优雅关闭 vs 强制关闭
 *
 * 运行方式：运行 main 方法，用 telnet localhost 8090 连接再断开，观察事件顺序
 */
public class Ch09_ConnectionLifecycleDemo {

    public static void main(String[] args) throws Exception {
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(group)
             .channel(NioServerSocketChannel.class)
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 protected void initChannel(SocketChannel ch) {
                     ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                         @Override
                         public void channelRegistered(ChannelHandlerContext ctx) {
                             System.out.println("① channelRegistered - Channel 注册到 EventLoop");
                             ctx.fireChannelRegistered();
                         }

                         @Override
                         public void channelActive(ChannelHandlerContext ctx) {
                             System.out.println("② channelActive - 连接就绪，可以读写: " + ctx.channel().remoteAddress());
                             ctx.fireChannelActive();
                         }

                         @Override
                         public void channelRead(ChannelHandlerContext ctx, Object msg) {
                             System.out.println("③ channelRead - 收到数据");
                             ctx.fireChannelRead(msg);
                         }

                         @Override
                         public void channelReadComplete(ChannelHandlerContext ctx) {
                             System.out.println("  channelReadComplete - 本次读取批次完成");
                             ctx.fireChannelReadComplete();
                         }

                         @Override
                         public void channelInactive(ChannelHandlerContext ctx) {
                             System.out.println("④ channelInactive - 连接断开");
                             ctx.fireChannelInactive();
                         }

                         @Override
                         public void channelUnregistered(ChannelHandlerContext ctx) {
                             System.out.println("⑤ channelUnregistered - Channel 从 EventLoop 注销");
                             ctx.fireChannelUnregistered();
                         }

                         @Override
                         public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                             System.out.println("⚠ exceptionCaught: " + cause.getMessage());
                             ctx.close();
                         }
                     });
                 }
             });

            ChannelFuture f = b.bind(8090).sync();
            System.out.println(">>> 连接生命周期 Demo 服务器已启动，端口 8090 <<<");
            System.out.println(">>> telnet localhost 8090 连接后再断开，观察事件顺序 <<<");
            f.channel().closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }
}
