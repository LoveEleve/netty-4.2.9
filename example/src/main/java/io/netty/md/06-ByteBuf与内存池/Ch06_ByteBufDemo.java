package io.netty.md.ch06;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;
import io.netty.util.internal.PlatformDependent;

/**
 * 【Ch06 可运行 Demo】ByteBuf 基础操作与内存池验证
 *
 * 验证目标：
 *   1. ByteBuf 双指针（readerIndex / writerIndex）的读写分离
 *   2. 堆内 vs 直接内存 vs 复合 ByteBuf
 *   3. 引用计数机制 retain/release
 *   4. 池化分配器 vs 非池化分配器的性能差异
 *   5. slice/duplicate 的零拷贝特性
 *
 * 运行方式：直接运行 main 方法
 */
public class Ch06_ByteBufDemo {

    public static void main(String[] args) {
        System.out.println("===== 1. 双指针读写分离 =====");
        demoReadWrite();

        System.out.println("\n===== 2. 堆内 vs 直接内存 =====");
        demoHeapVsDirect();

        System.out.println("\n===== 3. 引用计数 =====");
        demoRefCount();

        System.out.println("\n===== 4. slice 零拷贝 =====");
        demoSlice();

        System.out.println("\n===== 5. 池化分配器分配/释放 =====");
        demoPooledAllocator();

        System.out.println("\n✅ Demo 结束");
    }

    /** 验证 ByteBuf 的 readerIndex/writerIndex 双指针 */
    static void demoReadWrite() {
        ByteBuf buf = Unpooled.buffer(256);
        System.out.println("初始状态: readerIndex=" + buf.readerIndex()
                + " writerIndex=" + buf.writerIndex()
                + " capacity=" + buf.capacity());

        buf.writeBytes("Hello, Netty!".getBytes(CharsetUtil.UTF_8));
        System.out.println("写入后:   readerIndex=" + buf.readerIndex()
                + " writerIndex=" + buf.writerIndex()
                + " readableBytes=" + buf.readableBytes());

        byte[] dst = new byte[5];
        buf.readBytes(dst);
        System.out.println("读5字节后: readerIndex=" + buf.readerIndex()
                + " 读到: " + new String(dst));

        // discardReadBytes 回收已读空间
        buf.discardReadBytes();
        System.out.println("discard后: readerIndex=" + buf.readerIndex()
                + " writerIndex=" + buf.writerIndex());
        buf.release();
    }

    /** 堆内内存 vs 直接内存的区别 */
    static void demoHeapVsDirect() {
        // 堆内 ByteBuf：有 backing array
        ByteBuf heap = Unpooled.buffer(64);
        heap.writeBytes("heap".getBytes());
        System.out.println("Heap ByteBuf: hasArray=" + heap.hasArray()
                + " class=" + heap.getClass().getSimpleName());
        heap.release();

        // 直接内存 ByteBuf：无 backing array
        ByteBuf direct = Unpooled.directBuffer(64);
        direct.writeBytes("direct".getBytes());
        System.out.println("Direct ByteBuf: hasArray=" + direct.hasArray()
                + " class=" + direct.getClass().getSimpleName());
        direct.release();
    }

    /** 引用计数 retain/release */
    static void demoRefCount() {
        ByteBuf buf = Unpooled.buffer(64);
        System.out.println("创建后 refCnt=" + buf.refCnt());

        buf.retain();
        System.out.println("retain后 refCnt=" + buf.refCnt());

        buf.release();
        System.out.println("release1次后 refCnt=" + buf.refCnt());

        buf.release();
        System.out.println("release2次后 refCnt=" + buf.refCnt());

        try {
            buf.writeByte(1); // 已释放，应抛异常
        } catch (Exception e) {
            System.out.println("已释放后写入抛异常: " + e.getClass().getSimpleName());
        }
    }

    /** slice 零拷贝：共享底层内存 */
    static void demoSlice() {
        ByteBuf origin = Unpooled.copiedBuffer("ABCDEF", CharsetUtil.UTF_8);
        ByteBuf slice = origin.slice(1, 3); // BCD
        System.out.println("原始: " + origin.toString(CharsetUtil.UTF_8));
        System.out.println("slice(1,3): " + slice.toString(CharsetUtil.UTF_8));

        // 修改 slice 会影响原始 ByteBuf（共享内存）
        slice.setByte(0, 'X');
        System.out.println("修改 slice 后原始: " + origin.toString(CharsetUtil.UTF_8));
        origin.release(); // slice 与 origin 共享引用计数
    }

    /**
     * 池化 vs 非池化直接内存分配性能对比
     * 说明：生产环境中 Netty 默认使用 direct buffer（零拷贝友好），
     * 直接内存的分配/释放需要调用 malloc/free，池化的复用优势在此场景下最为明显。
     */
    static void demoPooledAllocator() {
        ByteBufAllocator pooledAlloc = PooledByteBufAllocator.DEFAULT;
        int rounds = 100_000;

        // 预热：消除首次初始化开销（Arena 创建、PoolThreadCache 绑定等）
        for (int i = 0; i < 10_000; i++) {
            ByteBuf buf = pooledAlloc.directBuffer(256);
            buf.writeBytes(new byte[128]);
            buf.release();
        }
        for (int i = 0; i < 10_000; i++) {
            ByteBuf buf = Unpooled.directBuffer(256);
            buf.writeBytes(new byte[128]);
            buf.release();
        }

        // 池化直接内存分配
        long start = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            ByteBuf buf = pooledAlloc.directBuffer(256);
            buf.writeBytes(new byte[128]);
            buf.release();
        }
        long pooledTime = System.nanoTime() - start;

        // 非池化直接内存分配
        start = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            ByteBuf buf = Unpooled.directBuffer(256);
            buf.writeBytes(new byte[128]);
            buf.release();
        }
        long unpooledTime = System.nanoTime() - start;

        System.out.printf("池化 direct 分配 %d 次耗时: %.2f ms%n", rounds, pooledTime / 1e6);
        System.out.printf("非池化 direct 分配 %d 次耗时: %.2f ms%n", rounds, unpooledTime / 1e6);
        System.out.printf("池化快了 %.1f 倍%n", (double) unpooledTime / pooledTime);
        System.out.println("提示：在 EventLoop 线程中，PoolThreadCache 命中率极高，池化优势更加显著");
    }
}
