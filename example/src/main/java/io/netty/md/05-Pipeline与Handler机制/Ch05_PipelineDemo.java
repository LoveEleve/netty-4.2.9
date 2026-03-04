package io.netty.md.ch05;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.CharsetUtil;

/**
 * 【Ch05 可运行 Demo】Pipeline 与 Handler 机制验证
 *
 * 验证目标：
 *   1. Inbound 事件从 Head → Tail 传播（channelRead）
 *   2. Outbound 事件从 Tail → Head 传播（write）
 *   3. 动态添加/移除 Handler（热插拔）
 *   4. executionMask 跳过不关心事件的 Handler
 *
 * 运行方式：运行 main 方法后，用 telnet localhost 8050 发送消息，观察控制台
 */
public class Ch05_PipelineDemo {

    public static void main(String[] args) throws Exception {
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(group)
             .channel(NioServerSocketChannel.class)
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 protected void initChannel(SocketChannel ch) {
                     ChannelPipeline p = ch.pipeline();

                     // Inbound Handler 1: 日志记录
                     p.addLast("inbound-logger", new ChannelInboundHandlerAdapter() {
                         @Override
                         public void channelRead(ChannelHandlerContext ctx, Object msg) {
                             ByteBuf buf = (ByteBuf) msg;
                             System.out.println("[Inbound-1 日志] 收到: " + buf.toString(CharsetUtil.UTF_8).trim());
                             ctx.fireChannelRead(msg); // 传递给下一个 Inbound Handler
                         }
                     });

                     // Inbound Handler 2: 业务处理 + 动态移除
                     p.addLast("inbound-biz", new ChannelInboundHandlerAdapter() {
                         private int count = 0;

                         @Override
                         public void channelRead(ChannelHandlerContext ctx, Object msg) {
                             count++;
                             ByteBuf buf = (ByteBuf) msg;
                             String text = buf.toString(CharsetUtil.UTF_8).trim();
                             System.out.println("[Inbound-2 业务] 第" + count + "次处理: " + text);

                             // 演示动态移除 Handler：第 3 次消息后移除日志 Handler
                             if (count == 3) {
                                 ctx.pipeline().remove("inbound-logger");
                                 System.out.println("[Pipeline] 已移除 inbound-logger，后续消息不再打印日志");
                             }

                             // 触发 Outbound write（从 Tail → Head 传播）
                             ctx.writeAndFlush(Unpooled.copiedBuffer("Echo: " + text + "\n", CharsetUtil.UTF_8));
                             buf.release();
                         }
                     });

                     // Outbound Handler: 观察 write 事件传播
                     p.addFirst("outbound-tracer", new ChannelOutboundHandlerAdapter() {
                         @Override
                         public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                             System.out.println("[Outbound 追踪] write 事件经过此 Handler");
                             ctx.write(msg, promise); // 传递给下一个 Outbound Handler (Head)
                         }
                     });

                     System.out.println("[Pipeline 结构] " + ch.pipeline().names());
                 }
             });

            ChannelFuture f = b.bind(8050).sync();
            System.out.println(">>> Pipeline Demo 服务器已启动，端口 8050 <<<");
            System.out.println(">>> telnet localhost 8050 发送消息测试 <<<");
            System.out.println(">>> 发送 3 条消息后，inbound-logger 将被动态移除 <<<");
            f.channel().closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }
}
