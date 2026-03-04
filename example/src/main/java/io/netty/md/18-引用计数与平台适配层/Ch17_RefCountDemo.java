package io.netty.md.ch17;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.IllegalReferenceCountException;

/**
 * 【Ch17 可运行 Demo】引用计数机制验证
 *
 * 验证目标：
 *   1. retain()/release() 的引用计数变化
 *   2. release 归零后触发 deallocate
 *   3. 操作已释放对象抛出 IllegalReferenceCountException
 *   4. touch() 的用法（配合泄漏检测）
 *
 * 运行方式：直接运行 main 方法
 */
public class Ch17_RefCountDemo {

    public static void main(String[] args) {
        System.out.println("===== 1. 正常引用计数流程 =====");
        demoNormalRefCount();

        System.out.println("\n===== 2. 多次 retain/release =====");
        demoMultiRetain();

        System.out.println("\n===== 3. 释放后操作的异常 =====");
        demoUseAfterRelease();

        System.out.println("\n===== 4. 共享对象的引用计数 =====");
        demoSharedRefCount();

        System.out.println("\n✅ Demo 结束");
    }

    static void demoNormalRefCount() {
        ByteBuf buf = Unpooled.buffer(64);
        System.out.println("创建: refCnt=" + buf.refCnt());
        buf.writeBytes("Hello".getBytes());
        System.out.println("写入后: refCnt=" + buf.refCnt());
        boolean released = buf.release();
        System.out.println("release(): refCnt=" + (released ? 0 : buf.refCnt()) + " deallocated=" + released);
    }

    static void demoMultiRetain() {
        ByteBuf buf = Unpooled.buffer(64);
        System.out.println("创建:   refCnt=" + buf.refCnt()); // 1
        buf.retain();
        System.out.println("retain: refCnt=" + buf.refCnt()); // 2
        buf.retain();
        System.out.println("retain: refCnt=" + buf.refCnt()); // 3
        buf.release();
        System.out.println("release: refCnt=" + buf.refCnt()); // 2
        buf.release();
        System.out.println("release: refCnt=" + buf.refCnt()); // 1
        buf.release();
        System.out.println("release: deallocated=true"); // 0
    }

    static void demoUseAfterRelease() {
        ByteBuf buf = Unpooled.buffer(64);
        buf.release();
        try {
            buf.writeByte(1); // 应抛 IllegalReferenceCountException
            System.out.println("ERROR: 不应该到达这里！");
        } catch (IllegalReferenceCountException e) {
            System.out.println("✅ 操作已释放对象: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    static void demoSharedRefCount() {
        ByteBuf original = Unpooled.copiedBuffer("SHARED".getBytes());
        System.out.println("原始:   refCnt=" + original.refCnt());

        // slice 共享引用计数，不增加 refCnt
        ByteBuf slice = original.slice();
        System.out.println("slice后: original.refCnt=" + original.refCnt());

        // retainedSlice 会增加 refCnt
        ByteBuf retainedSlice = original.retainedSlice();
        System.out.println("retainedSlice后: original.refCnt=" + original.refCnt());

        retainedSlice.release();
        System.out.println("释放retainedSlice后: original.refCnt=" + original.refCnt());

        original.release();
        System.out.println("释放original后: deallocated");
    }
}
