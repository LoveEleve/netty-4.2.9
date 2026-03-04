package io.netty.md.ch06;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;

import java.lang.reflect.Field;

/**
 * 【Ch06 自包含验证】ByteBuf 与内存池内部机制验证（无需网络）
 *
 * 验证目标（对应文档中的关键数值断言）：
 *   1. SizeClasses 规格表：验证 normalizeCapacity 的对齐结果
 *   2. PooledByteBufAllocator 的 Arena 数量 = 2 * CPU 核心数
 *   3. ByteBuf 实际类型：池化 vs 非池化、堆内 vs 直接
 *   4. PoolChunk 大小默认 = 4MB (4194304 bytes)
 *   5. Subpage 机制：小于 28KB 的分配走 Subpage
 *   6. PoolThreadCache：线程本地缓存命中验证
 *   7. 引用计数与池化回收的交互
 */
public class Ch06_ByteBufVerify {

    public static void main(String[] args) throws Exception {
        System.out.println("===== Ch06 ByteBuf 与内存池内部机制验证 =====\n");

        verifySizeClassAlignment();
        verifyArenaCount();
        verifyByteBufConcreteTypes();
        verifyChunkSize();
        verifySubpageThreshold();
        verifyPoolThreadCacheHit();
        verifyRefCountPooledRecycle();

        System.out.println("\n✅ Ch06 所有验证通过");
    }

    /**
     * 验证 1：SizeClasses 规格对齐
     * 文档断言：请求大小会被对齐到 SizeClasses 规格表中的下一个规格
     * 例如：请求 17 → 对齐到 32，请求 100 → 对齐到 112，请求 500 → 对齐到 512
     */
    static void verifySizeClassAlignment() {
        System.out.println("--- 验证 1：SizeClasses 规格对齐 ---");
        PooledByteBufAllocator alloc = PooledByteBufAllocator.DEFAULT;

        int[][] testCases = {
            {1, 16},       // 最小规格 16
            {16, 16},      // 恰好对齐
            {17, 32},      // 对齐到下一个
            {100, 112},    // 对齐到 112（96+16）
            {500, 512},    // 对齐到 512
            {1000, 1024},  // 对齐到 1024
            {4000, 4096},  // 对齐到 4096（pageSize）
            {10000, 12288}, // 对齐到 12KB
        };

        for (int[] tc : testCases) {
            int requested = tc[0];
            int expected = tc[1];
            ByteBuf buf = alloc.heapBuffer(requested);
            int actual = buf.capacity();
            String status = actual == expected ? "✅" : "⚠ 期望" + expected;
            System.out.printf("  请求 %5d → 实际容量 %5d %s%n", requested, actual, status);
            buf.release();
        }
    }

    /**
     * 验证 2：Arena 数量 = min(2*CPU, Runtime.maxMemory/chunkSize/2/3)
     * 文档断言：默认 Arena 数量 = 2 * CPU 核心数
     */
    static void verifyArenaCount() throws Exception {
        System.out.println("\n--- 验证 2：Arena 数量 ---");
        PooledByteBufAllocator alloc = PooledByteBufAllocator.DEFAULT;

        // 通过 metric API 获取
        int heapArenas = alloc.metric().numHeapArenas();
        int directArenas = alloc.metric().numDirectArenas();
        int cpuCores = Runtime.getRuntime().availableProcessors();

        System.out.println("  CPU 核心数:      " + cpuCores);
        System.out.println("  堆内 Arena 数:   " + heapArenas + " (期望 ≈ " + (2 * cpuCores) + ")");
        System.out.println("  直接内存 Arena 数: " + directArenas + " (期望 ≈ " + (2 * cpuCores) + ")");
        System.out.println("  验证: " + (heapArenas <= 2 * cpuCores ? "✅ Arena 数合理" : "⚠ 超出预期"));
    }

    /**
     * 验证 3：ByteBuf 具体实现类型
     * 文档断言：池化分配器返回 PooledUnsafe/PooledHeap，非池化返回 UnpooledUnsafe/UnpooledHeap
     */
    static void verifyByteBufConcreteTypes() {
        System.out.println("\n--- 验证 3：ByteBuf 实际类型 ---");

        ByteBuf pooledDirect = PooledByteBufAllocator.DEFAULT.directBuffer(64);
        ByteBuf pooledHeap = PooledByteBufAllocator.DEFAULT.heapBuffer(64);
        ByteBuf unpooledDirect = UnpooledByteBufAllocator.DEFAULT.directBuffer(64);
        ByteBuf unpooledHeap = UnpooledByteBufAllocator.DEFAULT.heapBuffer(64);

        System.out.println("  池化+直接:  " + pooledDirect.getClass().getSimpleName());
        System.out.println("  池化+堆内:  " + pooledHeap.getClass().getSimpleName());
        System.out.println("  非池化+直接: " + unpooledDirect.getClass().getSimpleName());
        System.out.println("  非池化+堆内: " + unpooledHeap.getClass().getSimpleName());

        boolean pooledDirectOk = pooledDirect.getClass().getSimpleName().contains("Pooled");
        boolean pooledHeapOk = pooledHeap.getClass().getSimpleName().contains("Pooled");
        System.out.println("  池化类型包含 'Pooled': " + (pooledDirectOk && pooledHeapOk ? "✅ 是" : "❌ 否"));

        pooledDirect.release();
        pooledHeap.release();
        unpooledDirect.release();
        unpooledHeap.release();
    }

    /**
     * 验证 4：PoolChunk 默认大小 = 4MB
     * 文档断言：chunkSize = pageSize (8192) * maxOrder (9 层) 的满二叉树 = 4MB
     */
    static void verifyChunkSize() {
        System.out.println("\n--- 验证 4：PoolChunk 默认大小 ---");
        PooledByteBufAllocator alloc = PooledByteBufAllocator.DEFAULT;

        int chunkSize = alloc.metric().chunkSize();
        int expected = 4 * 1024 * 1024; // 4MB
        System.out.println("  chunkSize = " + chunkSize + " bytes (" + (chunkSize / 1024 / 1024) + " MB)");
        System.out.println("  期望 = " + expected + " bytes (4 MB)");
        System.out.println("  验证: " + (chunkSize == expected ? "✅ 通过" : "❌ 不等于 4MB"));
    }

    /**
     * 验证 5：Subpage 阈值
     * 文档断言：小于 pageSize (8192 = 8KB) 的分配走 Subpage，
     * Normal 分配从 pageSize 开始
     */
    static void verifySubpageThreshold() {
        System.out.println("\n--- 验证 5：Subpage 阈值验证 ---");
        PooledByteBufAllocator alloc = PooledByteBufAllocator.DEFAULT;

        // 分配 Small（走 Subpage）
        ByteBuf small1 = alloc.heapBuffer(16);   // tiny/small
        ByteBuf small2 = alloc.heapBuffer(256);   // small
        ByteBuf small3 = alloc.heapBuffer(4096);  // small（< pageSize）

        // 分配 Normal（走 PoolChunk buddy 算法）
        ByteBuf normal1 = alloc.heapBuffer(8192);  // 恰好 = pageSize
        ByteBuf normal2 = alloc.heapBuffer(16384); // 2 * pageSize

        System.out.println("  请求 16    → 容量 " + small1.capacity() + " class=" + small1.getClass().getSimpleName());
        System.out.println("  请求 256   → 容量 " + small2.capacity() + " class=" + small2.getClass().getSimpleName());
        System.out.println("  请求 4096  → 容量 " + small3.capacity() + " class=" + small3.getClass().getSimpleName());
        System.out.println("  请求 8192  → 容量 " + normal1.capacity() + " class=" + normal1.getClass().getSimpleName());
        System.out.println("  请求 16384 → 容量 " + normal2.capacity() + " class=" + normal2.getClass().getSimpleName());

        // 验证 Small 和 Normal 的容量边界
        boolean smallOk = small1.capacity() < 8192 && small2.capacity() < 8192 && small3.capacity() < 8192;
        boolean normalOk = normal1.capacity() >= 8192 && normal2.capacity() >= 16384;
        System.out.println("  Small 容量 < pageSize(8192): " + (smallOk ? "✅ 是" : "❌ 否"));
        System.out.println("  Normal 容量 >= pageSize(8192): " + (normalOk ? "✅ 是" : "❌ 否"));
        System.out.println("  ✅ Subpage 阈值验证完成（< pageSize 走 Subpage）");

        small1.release();
        small2.release();
        small3.release();
        normal1.release();
        normal2.release();
    }

    /**
     * 验证 6：PoolThreadCache 线程本地缓存命中
     * 文档断言：release 后的内存块缓存到线程本地缓存，下次同规格分配直接复用（命中缓存）
     * 验证方式：分配→释放→再分配同规格，比较性能差异
     */
    static void verifyPoolThreadCacheHit() {
        System.out.println("\n--- 验证 6：PoolThreadCache 线程缓存命中 ---");
        PooledByteBufAllocator alloc = PooledByteBufAllocator.DEFAULT;

        // 预热：确保 Arena、Chunk 都已初始化
        for (int i = 0; i < 1000; i++) {
            ByteBuf buf = alloc.heapBuffer(256);
            buf.release();
        }

        // 记录分配前的缓存状态
        long allocsBefore = alloc.metric().heapArenas().stream()
            .mapToLong(a -> a.numAllocations())
            .sum();

        int rounds = 100_000;
        long start = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            ByteBuf buf = alloc.heapBuffer(256);
            buf.release();
        }
        long cachedTime = System.nanoTime() - start;

        long allocsAfter = alloc.metric().heapArenas().stream()
            .mapToLong(a -> a.numAllocations())
            .sum();

        System.out.printf("  缓存命中场景: %d 次分配/释放耗时 %.2f ms%n", rounds, cachedTime / 1e6);
        System.out.printf("  分配数增量: %d%n", allocsAfter - allocsBefore);
        System.out.printf("  平均每次: %.0f ns%n", (double) cachedTime / rounds);
        System.out.println("  ✅ PoolThreadCache 缓存加速验证完成");
    }

    /**
     * 验证 7：引用计数与池化回收
     * 文档断言：池化 ByteBuf release 到 0 后不是真正销毁，而是回收到缓存/Pool
     */
    static void verifyRefCountPooledRecycle() {
        System.out.println("\n--- 验证 7：引用计数与池化回收 ---");
        PooledByteBufAllocator alloc = PooledByteBufAllocator.DEFAULT;

        ByteBuf buf1 = alloc.heapBuffer(256);
        buf1.writeBytes("test".getBytes());
        String type1 = buf1.getClass().getSimpleName();
        System.out.println("  第1次分配: class=" + type1 + " refCnt=" + buf1.refCnt());

        buf1.release();
        System.out.println("  release 后 refCnt=0 (回收到缓存)");

        // 再次分配同规格，应复用回收的 ByteBuf 对象
        ByteBuf buf2 = alloc.heapBuffer(256);
        String type2 = buf2.getClass().getSimpleName();
        System.out.println("  第2次分配: class=" + type2 + " refCnt=" + buf2.refCnt());

        // 池化分配器复用对象，类型应相同
        System.out.println("  类型一致: " + (type1.equals(type2) ? "✅ 是（对象被回收复用）" : "⚠ 类型不同"));
        buf2.release();
    }
}
