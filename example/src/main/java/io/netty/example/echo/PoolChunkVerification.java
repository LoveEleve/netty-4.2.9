package io.netty.example.echo;

import io.netty.buffer.*;
import java.lang.reflect.*;
import java.nio.ByteBuffer;

/**
 * PoolChunk 数值验证程序
 * 验证：handle编码、runsAvail分桶、splitLargeRun、PoolChunkList阈值、calculateRunSize
 */
public class PoolChunkVerification {

    public static void main(String[] args) throws Exception {
        // ===== §1 handle 位域常量 =====
        System.out.println("=== §1 handle 位域常量 ===");
        System.out.println("IS_SUBPAGE_SHIFT  = " + PoolChunk.IS_SUBPAGE_SHIFT);
        System.out.println("IS_USED_SHIFT     = " + PoolChunk.IS_USED_SHIFT);
        System.out.println("SIZE_SHIFT        = " + PoolChunk.SIZE_SHIFT);
        System.out.println("RUN_OFFSET_SHIFT  = " + PoolChunk.RUN_OFFSET_SHIFT);

        // ===== §2 handle 编码验证 =====
        System.out.println("\n=== §2 handle 编码验证 ===");
        int pages512 = 512;
        long initHandle = (long) pages512 << PoolChunk.SIZE_SHIFT;
        System.out.println("初始 run handle (hex)  = 0x" + Long.toHexString(initHandle));
        System.out.println("runOffset(initHandle)  = " + PoolChunk.runOffset(initHandle));
        System.out.println("runPages(initHandle)   = " + PoolChunk.runPages(initHandle));
        System.out.println("isUsed(initHandle)     = " + PoolChunk.isUsed(initHandle));
        System.out.println("isSubpage(initHandle)  = " + PoolChunk.isSubpage(initHandle));

        // 模拟 splitLargeRun：申请 2 pages，剩余 510 pages
        int needPages = 2;
        int remPages = 512 - needPages;
        int runOffset = 0;
        int availOffset = runOffset + needPages;
        long usedHandle = toRunHandle(runOffset, needPages, 1);
        long availRun   = toRunHandle(availOffset, remPages, 0);
        System.out.println("\n--- splitLargeRun(handle, needPages=2) ---");
        System.out.println("usedHandle (hex)       = 0x" + Long.toHexString(usedHandle));
        System.out.println("  runOffset=" + PoolChunk.runOffset(usedHandle)
                + " pages=" + PoolChunk.runPages(usedHandle)
                + " isUsed=" + PoolChunk.isUsed(usedHandle));
        System.out.println("availRun (hex)         = 0x" + Long.toHexString(availRun));
        System.out.println("  runOffset=" + PoolChunk.runOffset(availRun)
                + " pages=" + PoolChunk.runPages(availRun)
                + " isUsed=" + PoolChunk.isUsed(availRun));

        // ===== §3 runsAvail 分桶验证 =====
        System.out.println("\n=== §3 runsAvail 分桶验证 ===");
        // 创建一个真实的 PooledByteBufAllocator，通过反射获取 SizeClasses
        PooledByteBufAllocator allocator = PooledByteBufAllocator.DEFAULT;
        // 获取 SizeClasses（通过 PoolArena）
        Field arenaField = PooledByteBufAllocator.class.getDeclaredField("heapArenas");
        arenaField.setAccessible(true);
        PoolArena<?>[] arenas = (PoolArena<?>[]) arenaField.get(allocator);
        PoolArena<?> arena = arenas[0];

        Field scField = PoolArena.class.getDeclaredField("sizeClass");
        scField.setAccessible(true);
        SizeClasses sc = (SizeClasses) scField.get(arena);

        int nPSizes = sc.nPSizes;
        System.out.println("nPSizes (runsAvail.length) = " + nPSizes);

        // 验证关键 pages 对应的 pageIdx
        int[] testPages = {1, 2, 3, 4, 8, 16, 32, 64, 128, 256, 510, 512};
        System.out.println("\npages -> pageIdx (pages2pageIdx) -> pageIdx (pages2pageIdxFloor):");
        for (int p : testPages) {
            if (p > 512) continue;
            int idx = sc.pages2pageIdx(p);
            int idxFloor = sc.pages2pageIdxFloor(p);
            System.out.printf("  pages=%-4d -> pages2pageIdx=%2d, pages2pageIdxFloor=%2d%n", p, idx, idxFloor);
        }

        // 初始 512 pages 放入哪个桶？
        int initBucket = sc.pages2pageIdxFloor(512);
        System.out.println("\n初始 512 pages 放入桶 [" + initBucket + "] (nPSizes-1=" + (nPSizes-1) + ")");

        // 剩余 510 pages 放入哪个桶？
        int remBucket = sc.pages2pageIdxFloor(510);
        System.out.println("剩余 510 pages 放入桶 [" + remBucket + "]");

        // ===== §4 PoolChunkList 阈值验证 =====
        System.out.println("\n=== §4 PoolChunkList 阈值验证 ===");
        String[] listNames = {"qInit", "q000", "q025", "q050", "q075", "q100"};
        for (String name : listNames) {
            Field f = PoolArena.class.getDeclaredField(name);
            f.setAccessible(true);
            PoolChunkList<?> list = (PoolChunkList<?>) f.get(arena);

            Field minF = PoolChunkList.class.getDeclaredField("minUsage");
            Field maxF = PoolChunkList.class.getDeclaredField("maxUsage");
            Field freeMinF = PoolChunkList.class.getDeclaredField("freeMinThreshold");
            Field freeMaxF = PoolChunkList.class.getDeclaredField("freeMaxThreshold");
            minF.setAccessible(true); maxF.setAccessible(true);
            freeMinF.setAccessible(true); freeMaxF.setAccessible(true);

            int minUsage = (int) minF.get(list);
            int maxUsage = (int) maxF.get(list);
            long freeMin = (long) freeMinF.get(list);
            long freeMax = (long) freeMaxF.get(list);
            System.out.printf("  %-6s minUsage=%-12s maxUsage=%-12s freeMinThreshold=%-12s freeMaxThreshold=%s%n",
                    name,
                    minUsage == Integer.MIN_VALUE ? "MIN_VALUE" : String.valueOf(minUsage),
                    maxUsage == Integer.MAX_VALUE ? "MAX_VALUE" : String.valueOf(maxUsage),
                    freeMin == Long.MAX_VALUE ? "MAX_VALUE" : String.valueOf(freeMin),
                    freeMax == Long.MAX_VALUE ? "MAX_VALUE" : String.valueOf(freeMax));
        }

        // ===== §5 calculateRunSize 验证 =====
        System.out.println("\n=== §5 calculateRunSize 验证 ===");
        int pageSize = sc.pageSize;
        int pageShifts = sc.pageShifts;
        int maxElements = 1 << pageShifts - SizeClasses.LOG2_QUANTUM;
        System.out.println("pageSize=" + pageSize + " pageShifts=" + pageShifts
                + " LOG2_QUANTUM=" + SizeClasses.LOG2_QUANTUM
                + " maxElements=" + maxElements);

        // 验证几个典型 sizeIdx 的 runSize
        int[] testSizeIdx = {0, 1, 4, 8, 16, 24, 32, 38};
        System.out.println("\nsizeIdx -> elemSize -> runSize -> nElements:");
        for (int sIdx : testSizeIdx) {
            if (sIdx >= sc.nSizes) continue;
            int elemSize = sc.sizeIdx2size(sIdx);
            int runSize = calcRunSize(pageSize, pageShifts, maxElements, elemSize);
            int nElements = runSize / elemSize;
            System.out.printf("  sizeIdx=%-3d elemSize=%-8d runSize=%-8d nElements=%d%n",
                    sIdx, elemSize, runSize, nElements);
        }

        System.out.println("\n=== 验证完成 ===");
    }

    // 复现 PoolChunk.toRunHandle()
    private static long toRunHandle(int runOffset, int runPages, int inUsed) {
        return (long) runOffset << PoolChunk.RUN_OFFSET_SHIFT
                | (long) runPages << PoolChunk.SIZE_SHIFT
                | (long) inUsed << PoolChunk.IS_USED_SHIFT;
    }

    // 复现 PoolChunk.calculateRunSize()
    private static int calcRunSize(int pageSize, int pageShifts, int maxElements, int elemSize) {
        int runSize = 0;
        int nElements;
        do {
            runSize += pageSize;
            nElements = runSize / elemSize;
        } while (nElements < maxElements && runSize != nElements * elemSize);

        while (nElements > maxElements) {
            runSize -= pageSize;
            nElements = runSize / elemSize;
        }
        return runSize;
    }
}
