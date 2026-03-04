package io.netty.md.ch14;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.DefaultFileRegion;
import io.netty.util.CharsetUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;

/**
 * 【Ch14 可运行 Demo】零拷贝机制验证
 *
 * 验证目标：
 *   1. CompositeByteBuf：多个 ByteBuf 逻辑合并，不复制底层数据
 *   2. slice/duplicate：共享底层内存的视图
 *   3. Unpooled.wrappedBuffer：包装 byte[] 不复制
 *   4. FileRegion：基于 transferTo 的文件零拷贝（概念演示）
 *
 * 运行方式：直接运行 main 方法
 */
public class Ch14_ZeroCopyDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("===== 1. CompositeByteBuf 零拷贝合并 =====");
        demoComposite();

        System.out.println("\n===== 2. slice 零拷贝切片 =====");
        demoSlice();

        System.out.println("\n===== 3. wrappedBuffer 零拷贝包装 =====");
        demoWrapped();

        System.out.println("\n===== 4. FileRegion 文件零拷贝 =====");
        demoFileRegion();

        System.out.println("\n✅ Demo 结束");
    }

    static void demoComposite() {
        ByteBuf header = Unpooled.copiedBuffer("Header|", CharsetUtil.UTF_8);
        ByteBuf body = Unpooled.copiedBuffer("Body-Content", CharsetUtil.UTF_8);

        // 传统方式：需要复制
        ByteBuf merged = Unpooled.buffer(header.readableBytes() + body.readableBytes());
        merged.writeBytes(header.duplicate());
        merged.writeBytes(body.duplicate());
        System.out.println("传统合并（有复制）: " + merged.toString(CharsetUtil.UTF_8));
        merged.release();

        // 零拷贝方式：CompositeByteBuf
        CompositeByteBuf composite = Unpooled.compositeBuffer();
        composite.addComponents(true, header, body);
        System.out.println("CompositeByteBuf（零拷贝）: " + composite.toString(CharsetUtil.UTF_8));
        System.out.println("组件数: " + composite.numComponents());
        composite.release();
    }

    static void demoSlice() {
        ByteBuf buf = Unpooled.copiedBuffer("ABCDEFGHIJ", CharsetUtil.UTF_8);
        ByteBuf slice = buf.slice(2, 5); // CDEFG
        System.out.println("原始: " + buf.toString(CharsetUtil.UTF_8));
        System.out.println("slice(2,5): " + slice.toString(CharsetUtil.UTF_8));

        // 验证共享内存
        slice.setByte(0, 'X');
        System.out.println("修改 slice[0]='X' 后原始: " + buf.toString(CharsetUtil.UTF_8));
        buf.release();
    }

    static void demoWrapped() {
        byte[] bytes = "Hello-Wrapped".getBytes(CharsetUtil.UTF_8);
        ByteBuf wrapped = Unpooled.wrappedBuffer(bytes);
        System.out.println("wrappedBuffer: " + wrapped.toString(CharsetUtil.UTF_8));

        // 修改原始数组会影响 ByteBuf（共享内存）
        bytes[0] = 'X';
        System.out.println("修改原始 byte[0]='X' 后: " + wrapped.toString(CharsetUtil.UTF_8));
        wrapped.release();
    }

    static void demoFileRegion() throws Exception {
        // 创建临时文件
        File tmpFile = File.createTempFile("netty-zero-copy-", ".txt");
        tmpFile.deleteOnExit();
        try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
            fos.write("This is a test file for FileRegion zero-copy demo.\n".getBytes());
        }

        // FileRegion 演示（实际使用需要在 Channel.write 中传输）
        try (RandomAccessFile raf = new RandomAccessFile(tmpFile, "r");
             FileChannel fc = raf.getChannel()) {
            DefaultFileRegion region = new DefaultFileRegion(fc, 0, tmpFile.length());
            System.out.println("FileRegion 创建成功: count=" + region.count() + " bytes");
            System.out.println("实际场景中通过 ctx.write(fileRegion) 发送，内核直接 sendfile 零拷贝");
            region.release();
        }
    }
}
