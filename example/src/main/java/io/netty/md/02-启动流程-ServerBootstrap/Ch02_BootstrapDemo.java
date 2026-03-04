package io.netty.md.ch02;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

/**
 * 【Ch02 可运行 Demo】ServerBootstrap 启动流程验证
 *
 * 验证目标：
 *   1. 观察 ServerBootstrap 的 group/channel/option/handler/childHandler 配置链
 *   2. 观察 bind() 的完整流程：Channel 创建 → init → register → bind
 *   3. 通过 LoggingHandler 观察 Boss Pipeline 的事件传播
 *
 * 运行方式：
 *   直接运行 main 方法，服务器监听 8020 端口
 *   使用 telnet localhost 8020 连接，观察控制台日志
 */
public class Ch02_BootstrapDemo {

    public static void main(String[] args) throws Exception {
        // 1. 创建 EventLoopGroup（Reactor 线程池）
        EventLoopGroup bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoopGroup workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());

        try {
            ServerBootstrap b = new ServerBootstrap();

            // 2. 配置链：每一步都只是往 ServerBootstrap 字段里存值
            b.group(bossGroup, workerGroup)                   // → group + childGroup
             .channel(NioServerSocketChannel.class)           // → channelFactory
             .option(ChannelOption.SO_BACKLOG, 128)           // → options Map
             .childOption(ChannelOption.TCP_NODELAY, true)    // → childOptions Map
             .handler(new LoggingHandler(LogLevel.INFO))      // → Boss Pipeline 的 Handler
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 protected void initChannel(SocketChannel ch) {
                     // 3. 每个新连接到来时，构建 Worker Pipeline
                     ch.pipeline().addLast(new LoggingHandler(LogLevel.INFO));
                     ch.pipeline().addLast(new SimpleChannelInboundHandler<Object>() {
                         @Override
                         protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
                             System.out.println("[Worker] 收到消息: " + msg);
                             ctx.writeAndFlush(msg); // Echo 回去
                         }
                     });
                 }
             });

            // 4. bind() —— 触发完整的启动流程
            System.out.println(">>> 即将调用 bind()，观察控制台的 LoggingHandler 日志 <<<");
            ChannelFuture f = b.bind(8020).sync();
            System.out.println(">>> 服务器已绑定到 0.0.0.0:8020 <<<");
            System.out.println(">>> 使用 telnet localhost 8020 连接测试 <<<");

            f.channel().closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
