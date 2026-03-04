package io.netty.md.ch08;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.CharsetUtil;

/**
 * 【Ch08 可运行 Demo】写路径与背压机制验证
 *
 * 验证目标：
 *   1. 观察 Channel.isWritable() 状态变化
 *   2. 观察 channelWritabilityChanged 事件触发
 *   3. 配置 writeBufferWaterMark 高低水位线
 *   4. 当写缓冲区超过高水位时暂停写入，低于低水位时恢复
 *
 * 运行方式：
 *   运行 main 方法，用 telnet localhost 8080 连接
 *   连接后服务端会持续高速写入，观察背压触发
 */
public class Ch08_BackpressureDemo {

    public static void main(String[] args) throws Exception {
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(group)
             .channel(NioServerSocketChannel.class)
             // 设置较小的水位线以便观察背压现象
             .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                     new WriteBufferWaterMark(32 * 1024, 64 * 1024)) // 32KB低水位, 64KB高水位
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 protected void initChannel(SocketChannel ch) {
                     ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                         @Override
                         public void channelActive(ChannelHandlerContext ctx) {
                             System.out.println("[连接建立] 开始高速写入，观察背压...");
                             // 持续写入直到背压触发
                             writeIfPossible(ctx);
                         }

                         @Override
                         public void channelWritabilityChanged(ChannelHandlerContext ctx) {
                             boolean writable = ctx.channel().isWritable();
                             System.out.println("[水位变化] isWritable=" + writable
                                     + (writable ? " → 恢复写入" : " → 暂停写入"));
                             if (writable) {
                                 writeIfPossible(ctx);
                             }
                         }

                         private void writeIfPossible(ChannelHandlerContext ctx) {
                             int count = 0;
                             while (ctx.channel().isWritable()) {
                                 ctx.writeAndFlush(Unpooled.copiedBuffer(
                                         "DATA-" + count++ + "\n", CharsetUtil.UTF_8));
                             }
                             System.out.println("[写入暂停] 已写入 " + count + " 条消息后触发背压");
                         }
                     });
                 }
             });

            ChannelFuture f = b.bind(8080).sync();
            System.out.println(">>> 背压 Demo 服务器已启动，端口 8080 <<<");
            System.out.println(">>> telnet localhost 8080 连接后观察控制台 <<<");
            f.channel().closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }
}
