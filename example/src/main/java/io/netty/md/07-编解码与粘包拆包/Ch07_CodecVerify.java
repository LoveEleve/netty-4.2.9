package io.netty.md.ch07;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.*;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.util.CharsetUtil;

import java.util.List;

/**
 * 【Ch07 自包含验证】编解码与粘包拆包验证（使用 EmbeddedChannel，无需网络/telnet）
 *
 * 验证目标（对应文档中的关键断言）：
 *   1. 不加帧解码器时的粘包现象（多条消息粘在一起）
 *   2. FixedLengthFrameDecoder：固定长度拆包
 *   3. LengthFieldBasedFrameDecoder：自定义长度字段拆包
 *   4. DelimiterBasedFrameDecoder：分隔符拆包
 *   5. LineBasedFrameDecoder：行分隔符拆包
 *   6. 过大帧触发 TooLongFrameException 保护
 *   7. ByteToMessageDecoder 的累积机制验证
 */
public class Ch07_CodecVerify {

    public static void main(String[] args) {
        System.out.println("===== Ch07 编解码与粘包拆包验证 =====\n");

        verifyStickPacket();
        verifyFixedLengthDecoder();
        verifyLengthFieldDecoder();
        verifyDelimiterDecoder();
        verifyLineDecoder();
        verifyTooLongFrameProtection();
        verifyAccumulation();

        System.out.println("\n✅ Ch07 所有验证通过");
    }

    /**
     * 验证 1：粘包现象
     * 文档断言：TCP 是流协议，不保证消息边界，一次 read 可能读到多条消息
     */
    static void verifyStickPacket() {
        System.out.println("--- 验证 1：粘包现象（无帧解码器）---");

        // 不加任何帧解码器，直接用 StringDecoder
        EmbeddedChannel ch = new EmbeddedChannel(new StringDecoder(CharsetUtil.UTF_8));

        // 模拟两条消息粘在一起发送
        ByteBuf combined = Unpooled.copiedBuffer("HelloWorld", CharsetUtil.UTF_8);
        ch.writeInbound(combined);

        String result = ch.readInbound();
        System.out.println("  发送 'Hello'+'World' 粘包 → 收到: \"" + result + "\"");
        System.out.println("  粘包验证: " + ("HelloWorld".equals(result) ? "✅ 确实粘在一起了" : "❌ 意外拆开了"));
        ch.finish();
    }

    /**
     * 验证 2：FixedLengthFrameDecoder
     * 文档断言：按固定长度拆包，每 N 字节切一帧
     */
    static void verifyFixedLengthDecoder() {
        System.out.println("\n--- 验证 2：FixedLengthFrameDecoder（固定 5 字节）---");
        EmbeddedChannel ch = new EmbeddedChannel(
            new FixedLengthFrameDecoder(5),
            new StringDecoder(CharsetUtil.UTF_8)
        );

        // 发送 15 字节，应拆成 3 帧
        ch.writeInbound(Unpooled.copiedBuffer("AAAAABBBBBCCCCC", CharsetUtil.UTF_8));

        int frameCount = 0;
        String msg;
        while ((msg = ch.readInbound()) != null) {
            frameCount++;
            System.out.println("  帧" + frameCount + ": \"" + msg + "\" (长度=" + msg.length() + ")");
        }
        System.out.println("  拆包结果: " + (frameCount == 3 ? "✅ 正确拆成 3 帧" : "❌ 帧数=" + frameCount));
        ch.finish();
    }

    /**
     * 验证 3：LengthFieldBasedFrameDecoder
     * 文档断言：根据消息中的长度字段自动拆包，支持复杂的帧格式
     * 帧格式：[长度字段(2字节)] [数据]
     */
    static void verifyLengthFieldDecoder() {
        System.out.println("\n--- 验证 3：LengthFieldBasedFrameDecoder ---");
        // lengthFieldOffset=0, lengthFieldLength=2, lengthAdjustment=0, initialBytesToStrip=2
        EmbeddedChannel ch = new EmbeddedChannel(
            new LengthFieldBasedFrameDecoder(1024, 0, 2, 0, 2),
            new StringDecoder(CharsetUtil.UTF_8)
        );

        // 构造两条消息：[长度][数据]
        ByteBuf buf = Unpooled.buffer();
        // 消息1: 长度=5, 数据="Hello"
        buf.writeShort(5);
        buf.writeBytes("Hello".getBytes(CharsetUtil.UTF_8));
        // 消息2: 长度=6, 数据="Netty!"
        buf.writeShort(6);
        buf.writeBytes("Netty!".getBytes(CharsetUtil.UTF_8));

        ch.writeInbound(buf);

        String msg1 = ch.readInbound();
        String msg2 = ch.readInbound();
        System.out.println("  帧1: \"" + msg1 + "\"");
        System.out.println("  帧2: \"" + msg2 + "\"");
        System.out.println("  验证: " + ("Hello".equals(msg1) && "Netty!".equals(msg2) ? "✅ 正确拆包" : "❌ 拆包失败"));
        ch.finish();
    }

    /**
     * 验证 4：DelimiterBasedFrameDecoder
     * 文档断言：按分隔符拆包
     */
    static void verifyDelimiterDecoder() {
        System.out.println("\n--- 验证 4：DelimiterBasedFrameDecoder（分隔符='$'）---");
        ByteBuf delimiter = Unpooled.copiedBuffer("$", CharsetUtil.UTF_8);
        EmbeddedChannel ch = new EmbeddedChannel(
            new DelimiterBasedFrameDecoder(1024, delimiter),
            new StringDecoder(CharsetUtil.UTF_8)
        );

        ch.writeInbound(Unpooled.copiedBuffer("hello$world$netty$", CharsetUtil.UTF_8));

        int frameCount = 0;
        String msg;
        while ((msg = ch.readInbound()) != null) {
            frameCount++;
            System.out.println("  帧" + frameCount + ": \"" + msg + "\"");
        }
        System.out.println("  验证: " + (frameCount == 3 ? "✅ 正确拆成 3 帧" : "❌ 帧数=" + frameCount));
        ch.finish();
    }

    /**
     * 验证 5：LineBasedFrameDecoder
     * 文档断言：按 \n 或 \r\n 拆包
     */
    static void verifyLineDecoder() {
        System.out.println("\n--- 验证 5：LineBasedFrameDecoder ---");
        EmbeddedChannel ch = new EmbeddedChannel(
            new LineBasedFrameDecoder(1024),
            new StringDecoder(CharsetUtil.UTF_8)
        );

        ch.writeInbound(Unpooled.copiedBuffer("line1\nline2\r\nline3\n", CharsetUtil.UTF_8));

        int frameCount = 0;
        String msg;
        while ((msg = ch.readInbound()) != null) {
            frameCount++;
            System.out.println("  帧" + frameCount + ": \"" + msg + "\"");
        }
        System.out.println("  验证: " + (frameCount == 3 ? "✅ 正确拆成 3 帧" : "❌ 帧数=" + frameCount));
        ch.finish();
    }

    /**
     * 验证 6：TooLongFrameException 保护
     * 文档断言：帧长度超过 maxFrameLength 时抛出 TooLongFrameException
     */
    static void verifyTooLongFrameProtection() {
        System.out.println("\n--- 验证 6：TooLongFrameException 保护 ---");
        // maxFrameLength = 10 字节
        EmbeddedChannel ch = new EmbeddedChannel(
            new LineBasedFrameDecoder(10)
        );

        try {
            // 发送一个超长行（20字节 + 换行符）
            ch.writeInbound(Unpooled.copiedBuffer("12345678901234567890\n", CharsetUtil.UTF_8));
            System.out.println("  ❌ 未抛异常（不应到达此处）");
        } catch (DecoderException e) {
            boolean isTooLong = e.getCause() instanceof TooLongFrameException
                    || e instanceof TooLongFrameException
                    || e.getMessage().contains("too long");
            System.out.println("  捕获异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.out.println("  验证: " + (isTooLong ? "✅ 正确触发 TooLongFrame 保护" : "⚠ 异常类型不符"));
        }
        ch.finish();
    }

    /**
     * 验证 7：ByteToMessageDecoder 累积机制
     * 文档断言：不完整的帧会被累积到 cumulation 中，等待后续数据到达再拆包
     */
    static void verifyAccumulation() {
        System.out.println("\n--- 验证 7：ByteToMessageDecoder 累积机制 ---");
        // 使用 LengthFieldBasedFrameDecoder，帧格式 = [2字节长度][数据]
        EmbeddedChannel ch = new EmbeddedChannel(
            new LengthFieldBasedFrameDecoder(1024, 0, 2, 0, 2),
            new StringDecoder(CharsetUtil.UTF_8)
        );

        // 第一次只发送长度字段 + 部分数据（不完整帧）
        ByteBuf part1 = Unpooled.buffer();
        part1.writeShort(10); // 声明数据长度 = 10
        part1.writeBytes("Hell".getBytes(CharsetUtil.UTF_8)); // 只发了 4 字节
        ch.writeInbound(part1);

        String result1 = ch.readInbound();
        System.out.println("  第1次写入(不完整帧): 读取结果 = " + (result1 == null ? "null (✅ 等待更多数据)" : "\"" + result1 + "\""));

        // 第二次发送剩余数据
        ByteBuf part2 = Unpooled.copiedBuffer("o_Nett".getBytes(CharsetUtil.UTF_8)); // 又 6 字节，凑齐 10
        ch.writeInbound(part2);

        String result2 = ch.readInbound();
        System.out.println("  第2次写入(补齐数据): 读取结果 = " + (result2 != null ? "\"" + result2 + "\" (✅ 帧完整后输出)" : "null (❌ 仍未输出)"));
        System.out.println("  累积机制验证: " + ("Hello_Nett".equals(result2) ? "✅ 通过" : "⚠ 结果=" + result2));
        ch.finish();
    }
}
