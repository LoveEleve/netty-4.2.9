package io.netty.md.ch23;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.util.ResourceLeakDetector;

/**
 * 【Ch23 可运行 Demo】内存泄漏排查验证
 *
 * 验证目标：
 *   1. 开启 PARANOID 级别泄漏检测
 *   2. 故意制造内存泄漏（分配不释放）
 *   3. 触发 GC 后观察泄漏报告
 *   4. 正确的分配/释放对比
 *
 * 运行方式：
 *   直接运行 main 方法
 *   也可加 JVM 参数: -Dio.netty.leakDetection.level=PARANOID
 */
public class Ch23_LeakDetectionDemo {

    public static void main(String[] args) throws Exception {
        // 1. 设置泄漏检测级别为 PARANOID（最严格，每次分配都检测）
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
        System.out.println("泄漏检测级别: " + ResourceLeakDetector.getLevel());
        System.out.println("注意：泄漏报告依赖 GC，可能需要等待几秒\n");

        PooledByteBufAllocator alloc = PooledByteBufAllocator.DEFAULT;

        // 2. 正确的使用方式：分配后释放
        System.out.println("===== 正确使用（有 release）=====");
        for (int i = 0; i < 10; i++) {
            ByteBuf buf = alloc.buffer(256);
            buf.writeBytes(("正确使用-" + i).getBytes());
            buf.release(); // ✅ 正确释放
        }
        System.out.println("✅ 10 次正确分配/释放完成");

        // 3. 错误的使用方式：分配不释放（制造泄漏）
        System.out.println("\n===== 故意泄漏（无 release）=====");
        for (int i = 0; i < 10; i++) {
            ByteBuf leaked = alloc.buffer(256);
            leaked.writeBytes(("泄漏对象-" + i).getBytes());
            // ❌ 故意不调用 release()，制造泄漏
        }
        System.out.println("⚠ 10 次分配但未释放");

        // 4. 触发 GC 让泄漏检测器发现泄漏
        System.out.println("\n===== 触发 GC =====");
        System.gc();
        Thread.sleep(1000);

        // 5. 再分配一次，触发泄漏检测器的检查逻辑
        System.out.println("再次分配以触发泄漏检测...");
        for (int i = 0; i < 5; i++) {
            ByteBuf buf = alloc.buffer(256);
            buf.release();
        }
        System.gc();
        Thread.sleep(2000);

        System.out.println("\n检查上方是否有 'LEAK:' 开头的泄漏报告");
        System.out.println("如果没有，可多运行几次或加大泄漏对象数量");
        System.out.println("\n✅ Demo 结束");
    }
}
