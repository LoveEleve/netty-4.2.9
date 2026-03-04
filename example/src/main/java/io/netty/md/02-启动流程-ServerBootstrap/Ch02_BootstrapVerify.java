package io.netty.md.ch02;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.CharsetUtil;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 【Ch02 自包含验证】ServerBootstrap 启动流程验证（无需 telnet）
 *
 * 验证目标：
 *   1. 观察 bind() 完整流程：Channel 创建 → init → register → bind
 *   2. LoggingHandler 打印 Boss Pipeline 事件
 *   3. 客户端自动连接、发送数据、验证 Echo
 */
public class Ch02_BootstrapVerify {

    public static void main(String[] args) throws Exception {
        EventLoopGroup bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoopGroup workerGroup = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        EventLoopGroup clientGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());

        try {
            System.out.println("===== 1. ServerBootstrap 配置链 =====");
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
             .channel(NioServerSocketChannel.class)
             .option(ChannelOption.SO_BACKLOG, 128)
             .childOption(ChannelOption.TCP_NODELAY, true)
             .handler(new LoggingHandler(LogLevel.INFO))
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 protected void initChannel(SocketChannel ch) {
                     ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                         @Override
                         public void channelRead(ChannelHandlerContext ctx, Object msg) {
                             ctx.writeAndFlush(msg); // Echo（msg 的释放交给 writeAndFlush）
                         }
                     });
                 }
             });

            System.out.println("配置完成: group=Boss(1)+Worker(2), channel=NioServerSocketChannel");
            System.out.println("  option: SO_BACKLOG=128, childOption: TCP_NODELAY=true");

            System.out.println("\n===== 2. bind() 启动流程 =====");
            System.out.println(">>> 即将调用 bind(0)，观察 LoggingHandler 日志 <<<");
            ChannelFuture f = b.bind(0).sync();
            InetSocketAddress addr = (InetSocketAddress) f.channel().localAddress();
            System.out.println(">>> 服务器已绑定到端口: " + addr.getPort() + " <<<");

            Channel serverChannel = f.channel();
            System.out.println("ServerChannel 类型: " + serverChannel.getClass().getSimpleName());
            System.out.println("ServerChannel Pipeline: " + serverChannel.pipeline().names());

            System.out.println("\n===== 3. 客户端连接 + Echo 验证 =====");
            CountDownLatch echoLatch = new CountDownLatch(1);
            final String[] echoResult = {""};

            Channel client = new Bootstrap()
                .group(clientGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<Object>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
                                echoResult[0] = ((io.netty.buffer.ByteBuf) msg).toString(CharsetUtil.UTF_8);
                                echoLatch.countDown();
                            }
                        });
                    }
                })
                .connect(addr).sync().channel();

            System.out.println("客户端已连接: " + client.localAddress() + " → " + client.remoteAddress());

            // 发送数据
            client.writeAndFlush(Unpooled.copiedBuffer("Hello Netty!", CharsetUtil.UTF_8));
            echoLatch.await(5, TimeUnit.SECONDS);
            System.out.println("发送: \"Hello Netty!\"  Echo 回: \"" + echoResult[0] + "\"");
            System.out.println("Echo 验证: " + ("Hello Netty!".equals(echoResult[0]) ? "✅ 成功" : "❌ 失败"));

            client.close().sync();
            serverChannel.close().sync();
            System.out.println("\n✅ Demo 结束");
        } finally {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
            workerGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
            bossGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }
}
