package io.netty.md.ch05;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.CharsetUtil;

/**
 * 【Ch05 自包含验证】Pipeline 机制真实运行输出（使用 EmbeddedChannel，无需网络）
 */
public class Ch05_PipelineVerify {

    public static void main(String[] args) {
        System.out.println("===== 1. Pipeline 事件传播方向验证 =====");
        testEventPropagation();

        System.out.println("\n===== 2. 动态添加/移除 Handler =====");
        testDynamicHandler();

        System.out.println("\n===== 3. @Sharable 保护验证 =====");
        testShareableProtection();

        System.out.println("\n===== 4. executionMask 跳过验证 =====");
        testExecutionMaskSkip();

        System.out.println("\n✅ Pipeline 验证完成");
    }

    static void testEventPropagation() {
        EmbeddedChannel ch = new EmbeddedChannel(
            // Outbound Handler（write 方向：Tail → Head）
            new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                    System.out.println("[Outbound] write 事件经过: " + ctx.name());
                    ctx.write(msg, promise);
                }
            },
            // Inbound Handler 1: 日志
            new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    ByteBuf buf = (ByteBuf) msg;
                    System.out.println("[Inbound-1] channelRead: " + buf.toString(CharsetUtil.UTF_8));
                    ctx.fireChannelRead(msg);
                }
            },
            // Inbound Handler 2: 业务处理 + 触发 write
            new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    ByteBuf buf = (ByteBuf) msg;
                    System.out.println("[Inbound-2] channelRead: " + buf.toString(CharsetUtil.UTF_8));
                    ctx.writeAndFlush(Unpooled.copiedBuffer("Echo!", CharsetUtil.UTF_8));
                    buf.release();
                }
            }
        );

        System.out.println("Pipeline 结构: " + ch.pipeline().names());
        ch.writeInbound(Unpooled.copiedBuffer("Hello", CharsetUtil.UTF_8));
        // 读取 outbound 写出的数据
        ByteBuf out = ch.readOutbound();
        if (out != null) {
            System.out.println("Outbound 输出: " + out.toString(CharsetUtil.UTF_8));
            out.release();
        }
        ch.finish();
    }

    static void testDynamicHandler() {
        final int[] count = {0};

        @ChannelHandler.Sharable
        class LoggerHandler extends ChannelInboundHandlerAdapter {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                System.out.println("[Logger] 消息经过 logger handler");
                ctx.fireChannelRead(msg);
            }
        }

        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast("logger", new LoggerHandler());
        ch.pipeline().addLast("biz", new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                count[0]++;
                System.out.println("[Biz] 第" + count[0] + "次处理消息");
                if (count[0] == 2) {
                    ctx.pipeline().remove("logger");
                    System.out.println("[Pipeline] 已移除 logger, 当前 handler: " + ctx.pipeline().names());
                }
                ((ByteBuf) msg).release();
            }
        });

        ch.writeInbound(Unpooled.copiedBuffer("msg1", CharsetUtil.UTF_8));
        ch.writeInbound(Unpooled.copiedBuffer("msg2", CharsetUtil.UTF_8));
        ch.writeInbound(Unpooled.copiedBuffer("msg3", CharsetUtil.UTF_8)); // logger 已移除
        ch.finish();
    }

    static void testShareableProtection() {
        // 非 @Sharable Handler
        ChannelInboundHandlerAdapter nonShareable = new ChannelInboundHandlerAdapter();

        EmbeddedChannel ch1 = new EmbeddedChannel();
        ch1.pipeline().addLast(nonShareable);
        try {
            EmbeddedChannel ch2 = new EmbeddedChannel();
            ch2.pipeline().addLast(nonShareable); // 第二次添加，应抛异常
            System.out.println("❌ 未抛异常（不应到达此处）");
            ch2.finish();
        } catch (Exception e) {
            System.out.println("✅ 正确抛出异常: " + e.getClass().getSimpleName());
            System.out.println("   消息: " + e.getMessage());
        }
        ch1.finish();
    }

    static void testExecutionMaskSkip() {
        // 纯 Outbound Handler 应被 Inbound 事件跳过
        ChannelOutboundHandlerAdapter pureOutbound = new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                System.out.println("[PureOutbound] write 经过");
                ctx.write(msg, promise);
            }
        };

        ChannelInboundHandlerAdapter inbound = new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                System.out.println("[Inbound] channelRead 收到消息 (PureOutbound 被跳过)");
                ((ByteBuf) msg).release();
            }
        };

        EmbeddedChannel ch = new EmbeddedChannel(pureOutbound, inbound);
        System.out.println("Pipeline: " + ch.pipeline().names());
        ch.writeInbound(Unpooled.copiedBuffer("test", CharsetUtil.UTF_8));
        System.out.println("→ Inbound 事件正确跳过了 PureOutbound handler");
        ch.finish();
    }
}
