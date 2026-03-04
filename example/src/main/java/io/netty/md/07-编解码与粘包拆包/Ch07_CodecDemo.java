package io.netty.md.ch07;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.CharsetUtil;

/**
 * 【Ch07 可运行 Demo】编解码与粘包拆包验证
 *
 * 验证目标：
 *   1. 不加帧解码器时的粘包现象
 *   2. 使用 DelimiterBasedFrameDecoder 解决粘包
 *   3. StringDecoder/StringEncoder 的编解码链
 *
 * 运行方式：
 *   运行 main 方法，用 telnet localhost 8070 连接
 *   输入 "hello$world$" 观察按 $ 分隔符正确拆包
 */
public class Ch07_CodecDemo {

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

                     // 帧解码器：以 $ 作为分隔符，最大帧长 1024
                     ByteBuf delimiter = Unpooled.copiedBuffer("$", CharsetUtil.UTF_8);
                     p.addLast("framer", new DelimiterBasedFrameDecoder(1024, delimiter));

                     // 字符串编解码
                     p.addLast("decoder", new StringDecoder(CharsetUtil.UTF_8));
                     p.addLast("encoder", new StringEncoder(CharsetUtil.UTF_8));

                     // 业务 Handler
                     p.addLast("handler", new SimpleChannelInboundHandler<String>() {
                         @Override
                         protected void channelRead0(ChannelHandlerContext ctx, String msg) {
                             System.out.println("[收到完整帧] \"" + msg + "\"");
                             ctx.writeAndFlush("[服务端回复] 收到: " + msg + "$");
                         }
                     });
                 }
             });

            ChannelFuture f = b.bind(8070).sync();
            System.out.println(">>> 编解码 Demo 服务器已启动，端口 8070 <<<");
            System.out.println(">>> telnet localhost 8070，输入 hello$world$ 测试 <<<");
            f.channel().closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }
}
