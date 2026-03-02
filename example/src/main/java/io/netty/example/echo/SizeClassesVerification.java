package io.netty.example.echo;

import io.netty.buffer.PooledByteBufAllocator;

/**
 * 验证 SizeClasses 的核心数值：
 * 1. 完整的 sizeIdx → size 映射表（0~75）
 * 2. Small/Normal/Huge 边界
 * 3. size2SizeIdx() 查表验证
 * 4. pages2pageIdx() 查表验证
 * 5. normalizeSize() 验证
 */
public class SizeClassesVerification {

    public static void main(String[] args) {
        // 使用默认参数：pageSize=8192, pageShifts=13, chunkSize=4MiB, alignment=0
        PooledByteBufAllocator alloc = PooledByteBufAllocator.DEFAULT;

        System.out.println("=== 基本参数 ===");
        System.out.println("pageSize      = " + alloc.metric().chunkSize() / 512);
        System.out.println("chunkSize     = " + alloc.metric().chunkSize());
        System.out.println("chunkSize     = " + alloc.metric().chunkSize() + " bytes = " + alloc.metric().chunkSize()/1024/1024 + " MiB");

        // 通过反射读取 SizeClasses 内部字段
        try {
            // 获取 directArenas[0]，再获取其 sizeClass 字段（SizeClasses 实例）
            java.lang.reflect.Field directArenasField = PooledByteBufAllocator.class.getDeclaredField("directArenas");
            directArenasField.setAccessible(true);
            Object[] directArenas = (Object[]) directArenasField.get(alloc);
            Object arena = directArenas[0];
            Class<?> arenaClass = arena.getClass().getSuperclass(); // PoolArena

            java.lang.reflect.Field sizeClassField = arenaClass.getDeclaredField("sizeClass");
            sizeClassField.setAccessible(true);
            Object sc = sizeClassField.get(arena); // SizeClasses 实例
            Class<?> sizeClassesClass = sc.getClass(); // SizeClasses

            java.lang.reflect.Field nSizesField = sizeClassesClass.getDeclaredField("nSizes");
            nSizesField.setAccessible(true);
            int nSizes = (int) nSizesField.get(sc);

            java.lang.reflect.Field nSubpagesField = sizeClassesClass.getDeclaredField("nSubpages");
            nSubpagesField.setAccessible(true);
            int nSubpages = (int) nSubpagesField.get(sc);

            java.lang.reflect.Field nPSizesField = sizeClassesClass.getDeclaredField("nPSizes");
            nPSizesField.setAccessible(true);
            int nPSizes = (int) nPSizesField.get(sc);

            java.lang.reflect.Field smallMaxSizeIdxField = sizeClassesClass.getDeclaredField("smallMaxSizeIdx");
            smallMaxSizeIdxField.setAccessible(true);
            int smallMaxSizeIdx = (int) smallMaxSizeIdxField.get(sc);

            java.lang.reflect.Field lookupMaxSizeField = sizeClassesClass.getDeclaredField("lookupMaxSize");
            lookupMaxSizeField.setAccessible(true);
            int lookupMaxSize = (int) lookupMaxSizeField.get(sc);

            java.lang.reflect.Field sizeIdx2sizeTabField = sizeClassesClass.getDeclaredField("sizeIdx2sizeTab");
            sizeIdx2sizeTabField.setAccessible(true);
            int[] sizeIdx2sizeTab = (int[]) sizeIdx2sizeTabField.get(sc);

            java.lang.reflect.Field pageIdx2sizeTabField = sizeClassesClass.getDeclaredField("pageIdx2sizeTab");
            pageIdx2sizeTabField.setAccessible(true);
            int[] pageIdx2sizeTab = (int[]) pageIdx2sizeTabField.get(sc);

            System.out.println("\n=== 统计数据 ===");
            System.out.println("nSizes        = " + nSizes);
            System.out.println("nSubpages     = " + nSubpages + "  (Small 类型的 sizeIdx 数量)");
            System.out.println("nPSizes       = " + nPSizes + "  (pageSize 整数倍的 sizeIdx 数量)");
            System.out.println("smallMaxSizeIdx = " + smallMaxSizeIdx + "  (最大 Small sizeIdx)");
            System.out.println("lookupMaxSize = " + lookupMaxSize + " bytes = " + lookupMaxSize/1024 + " KB  (查表上限)");

            System.out.println("\n=== 完整 sizeIdx → size 映射表 ===");
            System.out.printf("%-6s %-12s %-10s%n", "idx", "size(bytes)", "size(human)");
            System.out.println("--------------------------------------");
            for (int i = 0; i < nSizes; i++) {
                int size = sizeIdx2sizeTab[i];
                String human = size < 1024 ? size + "B" :
                               size < 1024*1024 ? (size/1024) + "KB" :
                               (size/1024/1024) + "MB";
                String marker = i == smallMaxSizeIdx ? " ← smallMaxSizeIdx (Small/Normal边界)" :
                                i == nSizes - 1 ? " ← nSizes-1 (最大Normal = chunkSize)" : "";
                System.out.printf("%-6d %-12d %-10s%s%n", i, size, human, marker);
            }

            System.out.println("\n=== pageIdx → size 映射表（前10个）===");
            System.out.printf("%-8s %-12s%n", "pageIdx", "size(bytes)");
            System.out.println("--------------------");
            for (int i = 0; i < Math.min(10, pageIdx2sizeTab.length); i++) {
                System.out.printf("%-8d %-12d%n", i, pageIdx2sizeTab[i]);
            }
            System.out.println("... (共 " + nPSizes + " 个 pageIdx)");

            // 通过 SizeClassesMetric 接口验证查表方法
            io.netty.buffer.SizeClassesMetric metric = (io.netty.buffer.SizeClassesMetric) sc;

            System.out.println("\n=== size2SizeIdx() 验证 ===");
            int[] testSizes = {0, 1, 16, 17, 32, 48, 64, 80, 96, 112, 128,
                               256, 512, 1024, 2048, 4096, 8192, 16384, 32768,
                               65536, 131072, 262144, 524288, 1048576, 2097152, 4194304};
            System.out.printf("%-12s %-8s %-12s%n", "size", "sizeIdx", "normalized");
            System.out.println("----------------------------------");
            for (int size : testSizes) {
                if (size == 0) {
                    System.out.printf("%-12d %-8d %-12s%n", size, metric.size2SizeIdx(size), "0→idx0");
                    continue;
                }
                int idx = metric.size2SizeIdx(size);
                int normalized = idx < nSizes ? metric.sizeIdx2size(idx) : -1;
                System.out.printf("%-12d %-8d %-12d%n", size, idx, normalized);
            }

            System.out.println("\n=== normalizeSize() 验证 ===");
            int[] normSizes = {1, 15, 16, 17, 31, 32, 33, 48, 49, 64, 65, 128, 129, 256, 257, 4096, 4097, 8192, 8193};
            System.out.printf("%-8s → %-8s%n", "input", "normalized");
            System.out.println("------------------");
            for (int s : normSizes) {
                System.out.printf("%-8d → %-8d%n", s, metric.normalizeSize(s));
            }

            System.out.println("\n=== pages2pageIdx() 验证 ===");
            int[] testPages = {1, 2, 3, 4, 5, 8, 16, 32, 64, 128, 256, 512};
            System.out.printf("%-8s %-10s %-10s %-10s%n", "pages", "bytes", "pageIdx(ceil)", "pageIdx(floor)");
            System.out.println("------------------------------------------");
            for (int pages : testPages) {
                int bytes = pages * 8192;
                if (bytes > alloc.metric().chunkSize()) break;
                int ceil = metric.pages2pageIdx(pages);
                int floor = metric.pages2pageIdxFloor(pages);
                System.out.printf("%-8d %-10d %-10d %-10d%n", pages, bytes, ceil, floor);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
