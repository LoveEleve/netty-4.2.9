package io.netty.buffer;

import java.lang.reflect.*;

/**
 * PoolChunk 数值验证程序（放在 io.netty.buffer 包下，可访问 package-private 类）
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
        long initHandle = (long) 512 << PoolChunk.SIZE_SHIFT;
        System.out.println("初始 run handle (hex)  = 0x" + Long.toHexString(initHandle));
        System.out.println("runOffset(initHandle)  = " + PoolChunk.runOffset(initHandle));
        System.out.println("runPages(initHandle)   = " + PoolChunk.runPages(initHandle));
        System.out.println("isUsed(initHandle)     = " + PoolChunk.isUsed(initHandle));
        System.out.println("isSubpage(initHandle)  = " + PoolChunk.isSubpage(initHandle));

        // 模拟 splitLargeRun：申请 2 pages，剩余 510 pages
        long usedHandle = toRunHandle(0, 2, 1);
        long availRun   = toRunHandle(2, 510, 0);
        System.out.println("\n--- splitLargeRun(handle, needPages=2) ---");
        System.out.println("usedHandle (hex)  = 0x" + Long.toHexString(usedHandle)
                + "  runOffset=" + PoolChunk.runOffset(usedHandle)
                + " pages=" + PoolChunk.runPages(usedHandle)
                + " isUsed=" + PoolChunk.isUsed(usedHandle));
        System.out.println("availRun   (hex)  = 0x" + Long.toHexString(availRun)
                + "  runOffset=" + PoolChunk.runOffset(availRun)
                + " pages=" + PoolChunk.runPages(availRun)
                + " isUsed=" + PoolChunk.isUsed(availRun));

        // ===== §3 runsAvail 分桶验证 =====
        System.out.println("\n=== §3 runsAvail 分桶验证 ===");
        PooledByteBufAllocator allocator = PooledByteBufAllocator.DEFAULT;
        Field arenaField = PooledByteBufAllocator.class.getDeclaredField("heapArenas");
        arenaField.setAccessible(true);
        PoolArena<?>[] arenas = (PoolArena<?>[]) arenaField.get(allocator);
        PoolArena<?> arena = arenas[0];

        Field scField = PoolArena.class.getDeclaredField("sizeClass");
        scField.setAccessible(true);
        SizeClasses sc = (SizeClasses) scField.get(arena);

        System.out.println("nPSizes (runsAvail.length) = " + sc.nPSizes);

        int[] testPages = {1, 2, 3, 4, 8, 16, 32, 64, 128, 256, 510, 512};
        System.out.println("\npages -> pages2pageIdx -> pages2pageIdxFloor:");
        for (int p : testPages) {
            int idx      = sc.pages2pageIdx(p);
            int idxFloor = sc.pages2pageIdxFloor(p);
            System.out.printf("  pages=%-4d -> pages2pageIdx=%2d, pages2pageIdxFloor=%2d%n", p, idx, idxFloor);
        }
        System.out.println("初始 512 pages 放入桶 [" + sc.pages2pageIdxFloor(512) + "] (nPSizes-1=" + (sc.nPSizes - 1) + ")");
        System.out.println("剩余 510 pages 放入桶 [" + sc.pages2pageIdxFloor(510) + "]");

        // ===== §4 PoolChunkList 阈值验证 =====
        System.out.println("\n=== §4 PoolChunkList 阈值验证 ===");
        String[] listNames = {"qInit", "q000", "q025", "q050", "q075", "q100"};
        Field minF     = PoolChunkList.class.getDeclaredField("minUsage");
        Field maxF     = PoolChunkList.class.getDeclaredField("maxUsage");
        Field freeMinF = PoolChunkList.class.getDeclaredField("freeMinThreshold");
        Field freeMaxF = PoolChunkList.class.getDeclaredField("freeMaxThreshold");
        minF.setAccessible(true); maxF.setAccessible(true);
        freeMinF.setAccessible(true); freeMaxF.setAccessible(true);

        for (String name : listNames) {
            Field f = PoolArena.class.getDeclaredField(name);
            f.setAccessible(true);
            PoolChunkList<?> list = (PoolChunkList<?>) f.get(arena);
            int minUsage = (int) minF.get(list);
            int maxUsage = (int) maxF.get(list);
            int freeMin  = (int) freeMinF.get(list);
            int freeMax  = (int) freeMaxF.get(list);
            System.out.printf("  %-6s minUsage=%-12s maxUsage=%-12s freeMinThreshold=%-14s freeMaxThreshold=%s%n",
                    name,
                    minUsage == Integer.MIN_VALUE ? "MIN_VALUE" : String.valueOf(minUsage),
                    maxUsage == Integer.MAX_VALUE ? "MAX_VALUE" : String.valueOf(maxUsage),
                    String.valueOf(freeMin),
                    String.valueOf(freeMax));
        }

        // ===== §5 calculateRunSize 验证 =====
        System.out.println("\n=== §5 calculateRunSize 验证 ===");
        int pageSize    = sc.pageSize;
        int pageShifts  = sc.pageShifts;
        int maxElements = 1 << pageShifts - SizeClasses.LOG2_QUANTUM;
        System.out.println("pageSize=" + pageSize + " pageShifts=" + pageShifts
                + " LOG2_QUANTUM=" + SizeClasses.LOG2_QUANTUM + " maxElements=" + maxElements);

        int[] testSizeIdx = {0, 1, 4, 8, 16, 24, 32, 38};
        System.out.println("\nsizeIdx -> elemSize -> runSize -> nElements:");
        for (int sIdx : testSizeIdx) {
            if (sIdx >= sc.nSizes) continue;
            int elemSize = sc.sizeIdx2size(sIdx);
            int runSize  = calcRunSize(pageSize, maxElements, elemSize);
            int nElem    = runSize / elemSize;
            System.out.printf("  sizeIdx=%-3d elemSize=%-8d runSize=%-8d nElements=%d%n",
                    sIdx, elemSize, runSize, nElem);
        }

        // ===== §6 PoolSubpage 数值验证 =====
        System.out.println("\n=== §6 PoolSubpage 数值验证 ===");

        // 获取 smallSubpagePools
        Field smallSubpagePoolsField = PoolArena.class.getDeclaredField("smallSubpagePools");
        smallSubpagePoolsField.setAccessible(true);
        PoolSubpage<?>[] smallSubpagePools = (PoolSubpage<?>[]) smallSubpagePoolsField.get(arena);
        System.out.println("smallSubpagePools.length (nSubpages) = " + smallSubpagePools.length);

        // 分配一个 16B 的 buf，触发 Subpage 创建
        io.netty.buffer.ByteBuf buf = allocator.heapBuffer(16);
        System.out.println("分配 16B 后，smallSubpagePools[0].next == head? "
                + (smallSubpagePools[0].next == smallSubpagePools[0]));

        // 通过反射读取 Subpage 字段
        PoolSubpage<?> subpage = smallSubpagePools[0].next;
        Field maxNumElemsField  = PoolSubpage.class.getDeclaredField("maxNumElems");
        Field bitmapLengthField = PoolSubpage.class.getDeclaredField("bitmapLength");
        Field numAvailField     = PoolSubpage.class.getDeclaredField("numAvail");
        Field nextAvailField    = PoolSubpage.class.getDeclaredField("nextAvail");
        maxNumElemsField.setAccessible(true); bitmapLengthField.setAccessible(true);
        numAvailField.setAccessible(true);    nextAvailField.setAccessible(true);

        System.out.println("subpage.elemSize    = " + subpage.elemSize);
        System.out.println("subpage.maxNumElems = " + maxNumElemsField.get(subpage));
        System.out.println("subpage.bitmapLength= " + bitmapLengthField.get(subpage));
        System.out.println("subpage.numAvail    = " + numAvailField.get(subpage));
        System.out.println("subpage.nextAvail   = " + nextAvailField.get(subpage));

        buf.release();
        System.out.println("release 后 subpage.numAvail = " + numAvailField.get(subpage));
        System.out.println("release 后 subpage.nextAvail= " + nextAvailField.get(subpage));

        // 验证 bitmapIdx 编码进 handle
        // 手动计算 toHandle(bitmapIdx=0) 的值
        Field runOffsetField = PoolSubpage.class.getDeclaredField("runOffset");
        runOffsetField.setAccessible(true);
        int runOffset0 = (int) runOffsetField.get(subpage);
        Field runSizeField = PoolSubpage.class.getDeclaredField("runSize");
        runSizeField.setAccessible(true);
        int runSize0 = (int) runSizeField.get(subpage);
        int pages0 = runSize0 >> sc.pageShifts;
        long subpageHandle = (long) runOffset0 << PoolChunk.RUN_OFFSET_SHIFT
                | (long) pages0 << PoolChunk.SIZE_SHIFT
                | 1L << PoolChunk.IS_USED_SHIFT
                | 1L << PoolChunk.IS_SUBPAGE_SHIFT
                | 0; // bitmapIdx=0
        System.out.println("\nSubpage handle (bitmapIdx=0, hex) = 0x" + Long.toHexString(subpageHandle));
        System.out.println("  runOffset=" + PoolChunk.runOffset(subpageHandle)
                + " pages=" + PoolChunk.runPages(subpageHandle)
                + " isUsed=" + PoolChunk.isUsed(subpageHandle)
                + " isSubpage=" + PoolChunk.isSubpage(subpageHandle)
                + " bitmapIdx=" + PoolChunk.bitmapIdx(subpageHandle));

        System.out.println("\n=== 验证完成 ===");

        // ===== §7 PoolThreadCache 验证 =====
        System.out.println("\n=== §7 PoolThreadCache 验证 ===");
        // 必须在 FastThreadLocalThread 中运行，才能获取到有效的 PoolThreadCache
        final PooledByteBufAllocator allocator2 = new PooledByteBufAllocator(true);
        final String[] ptcResult = new String[6];
        Thread ftlThread = new io.netty.util.concurrent.FastThreadLocalThread(() -> {
            try {
                io.netty.buffer.ByteBuf heapBuf2 = allocator2.heapBuffer(16);
                Field cacheField3 = PooledByteBuf.class.getDeclaredField("cache");
                cacheField3.setAccessible(true);
                PoolThreadCache ptc2 = (PoolThreadCache) cacheField3.get(heapBuf2);
                heapBuf2.release();

                Field smallDirectF = PoolThreadCache.class.getDeclaredField("smallSubPageDirectCaches");
                Field normalDirectF = PoolThreadCache.class.getDeclaredField("normalDirectCaches");
                Field smallHeapF = PoolThreadCache.class.getDeclaredField("smallSubPageHeapCaches");
                Field normalHeapF = PoolThreadCache.class.getDeclaredField("normalHeapCaches");
                Field sweepF = PoolThreadCache.class.getDeclaredField("freeSweepAllocationThreshold");
                smallDirectF.setAccessible(true); normalDirectF.setAccessible(true);
                smallHeapF.setAccessible(true); normalHeapF.setAccessible(true); sweepF.setAccessible(true);

                Object[] sd = (Object[]) smallDirectF.get(ptc2);
                Object[] nd = (Object[]) normalDirectF.get(ptc2);
                Object[] sh = (Object[]) smallHeapF.get(ptc2);
                Object[] nh = (Object[]) normalHeapF.get(ptc2);
                int sw = (int) sweepF.get(ptc2);

                ptcResult[0] = "smallSubPageDirectCaches.length = " + (sd != null ? sd.length : "null");
                ptcResult[1] = "normalDirectCaches.length       = " + (nd != null ? nd.length : "null");
                ptcResult[2] = "smallSubPageHeapCaches.length   = " + (sh != null ? sh.length : "null");
                ptcResult[3] = "normalHeapCaches.length         = " + (nh != null ? nh.length : "null");
                ptcResult[4] = "freeSweepAllocationThreshold    = " + sw;

                // 验证缓存命中
                io.netty.buffer.ByteBuf db1 = allocator2.directBuffer(16);
                db1.release();
                io.netty.buffer.ByteBuf db2 = allocator2.directBuffer(16);
                ptcResult[5] = "二次分配 16B Direct buf 成功（缓存命中路径）: capacity=" + db2.capacity();
                db2.release();
            } catch (Exception e) {
                ptcResult[0] = "ERROR: " + e.getMessage();
            }
        });
        ftlThread.start();
        ftlThread.join();
        for (String r : ptcResult) { if (r != null) System.out.println(r); }

        // ===== §8 Recycler 验证 =====
        System.out.println("\n=== §8 Recycler 验证 ===");
        // 通过反射读取 Recycler 静态常量
        Field maxCapField = io.netty.util.Recycler.class.getDeclaredField("DEFAULT_MAX_CAPACITY_PER_THREAD");
        Field chunkSizeField = io.netty.util.Recycler.class.getDeclaredField("DEFAULT_QUEUE_CHUNK_SIZE_PER_THREAD");
        Field ratioField = io.netty.util.Recycler.class.getDeclaredField("RATIO");
        maxCapField.setAccessible(true); chunkSizeField.setAccessible(true); ratioField.setAccessible(true);
        System.out.println("DEFAULT_MAX_CAPACITY_PER_THREAD = " + maxCapField.get(null));
        System.out.println("DEFAULT_QUEUE_CHUNK_SIZE        = " + chunkSizeField.get(null));
        System.out.println("RATIO                           = " + ratioField.get(null));

        // 验证 ratio 机制：创建一个 Recycler，观察哪次 get() 会池化
        io.netty.util.Recycler<int[]> testRecycler = new io.netty.util.Recycler<int[]>() {
            @Override
            protected int[] newObject(io.netty.util.Recycler.Handle<int[]> handle) {
                return new int[]{System.identityHashCode(handle)};
            }
        };
        // 先 get 8 次，观察 ratio 行为
        int[] firstObj = null;
        for (int i = 1; i <= 9; i++) {
            int[] obj = testRecycler.get();
            if (i == 1) firstObj = obj;
            System.out.println("第" + i + "次 get(): identityHash=" + System.identityHashCode(obj)
                    + (obj[0] == 0 ? "（NOOP_HANDLE，不池化）" : "（有handle，可池化）"));
            // 通过 handle 回收（handle 存在 obj[0] 中，但这里用 threadLocalSize 观察）
        }
        System.out.println("pool size after 9 gets (no recycle): " + getThreadLocalSize(testRecycler));
    }

    private static int getThreadLocalSize(io.netty.util.Recycler<?> recycler) throws Exception {
        java.lang.reflect.Method m = io.netty.util.Recycler.class.getDeclaredMethod("threadLocalSize");
        m.setAccessible(true);
        return (int) m.invoke(recycler);
    }

    private static long toRunHandle(int runOffset, int runPages, int inUsed) {
        return (long) runOffset << PoolChunk.RUN_OFFSET_SHIFT
                | (long) runPages << PoolChunk.SIZE_SHIFT
                | (long) inUsed << PoolChunk.IS_USED_SHIFT;
    }

    private static int calcRunSize(int pageSize, int maxElements, int elemSize) {
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
