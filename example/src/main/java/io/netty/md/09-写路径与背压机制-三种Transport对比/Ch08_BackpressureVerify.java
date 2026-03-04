package io.netty.md.ch08;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 【Ch08 自包含验证】写路径与背压机制验证（无需 telnet）
 *
 * 验证目标：
 *   1. Channel.isWritable() 状态变化
 *   2. channelWritabilityChanged 事件触发
 *   3. writeBufferWaterMark 高低水位线
 */
public class Ch08_BackpressureVerify {

    public static void main(String[] args) throws Exception {
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        EventLoopGroup clientGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());

        try {
            CountDownLatch doneLatch = new CountDownLatch(1);

            ServerBootstrap b = new ServerBootstrap();
            b.group(group)
             .channel(NioServerSocketChannel.class)
             .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                     new WriteBufferWaterMark(8 * 1024, 16 * 1024)) // 8KB低水位, 16KB高水位
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 protected void initChannel(SocketChannel ch) {
                     ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                         private int writabilityChanges = 0;
                         private int totalWritten = 0;

                         @Override
                         public void channelActive(ChannelHandlerContext ctx) {
                             System.out.println("===== 背压机制验证 =====");
                             System.out.println("水位配置: low=8KB, high=16KB");
                             System.out.println("[连接建立] isWritable=" + ctx.channel().isWritable());
                             writeIfPossible(ctx);
                         }

                         @Override
                         public void channelWritabilityChanged(ChannelHandlerContext ctx) {
                             writabilityChanges++;
                             boolean writable = ctx.channel().isWritable();
                             System.out.println("[水位变化 #" + writabilityChanges + "] isWritable=" + writable
                                     + (writable ? " → 恢复写入" : " → 暂停写入"));
                             if (writable && writabilityChanges <= 6) {
                                 writeIfPossible(ctx);
                             }
                             if (writabilityChanges >= 4) {
                                 System.out.println("[结束] 共触发 " + writabilityChanges
                                         + " 次水位变化，共写入 " + totalWritten + " 条消息");
                                 doneLatch.countDown();
                             }
                         }

                         private void writeIfPossible(ChannelHandlerContext ctx) {
                             int count = 0;
                             while (ctx.channel().isWritable()) {
                                 ctx.writeAndFlush(Unpooled.copiedBuffer(
                                         "DATA-" + (totalWritten + count) + "\n", CharsetUtil.UTF_8));
                                 count++;
                             }
                             totalWritten += count;
                             System.out.println("[写入暂停] 本轮写入 " + count + " 条消息后触发背压 (累计: " + totalWritten + ")");
                         }
                     });
                 }
             });

            ChannelFuture f = b.bind(0).sync();
            InetSocketAddress addr = (InetSocketAddress) f.channel().localAddress();

            // 客户端连接但不读取数据（制造背压）
            Channel client = new Bootstrap()
                .group(clientGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_RCVBUF, 4 * 1024) // 小接收缓冲区
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        // 故意设置 autoRead=false，不主动读数据，制造 TCP 背压
                        ch.config().setAutoRead(false);
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
                    }
                })
                .connect(addr).sync().channel();

            // 等待背压验证完成，或超时
            boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
            if (!completed) {
                System.out.println("[超时] 背压验证未在10秒内完成（可能TCP窗口没有收缩到触发足够水位变化）");
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
