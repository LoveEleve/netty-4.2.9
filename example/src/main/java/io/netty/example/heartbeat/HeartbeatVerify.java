package io.netty.example.heartbeat;

import java.util.concurrent.TimeUnit;

/**
 * 验证 IdleStateHandler 关键数值与逻辑
 */
public class HeartbeatVerify {

    private static final long MIN_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

    public static void main(String[] args) {
        System.out.println("=== IdleStateHandler 关键数值验证 ===");

        // 1. MIN_TIMEOUT_NANOS 值
        System.out.println("\n--- MIN_TIMEOUT_NANOS ---");
        System.out.println("MIN_TIMEOUT_NANOS = " + MIN_TIMEOUT_NANOS + " ns");
        System.out.println("MIN_TIMEOUT_NANOS = " + TimeUnit.NANOSECONDS.toMillis(MIN_TIMEOUT_NANOS) + " ms");

        // 2. 构造函数中的时间转换逻辑
        System.out.println("\n--- 构造函数时间转换 ---");
        long readerIdleTime = 60;
        TimeUnit unit = TimeUnit.SECONDS;
        long readerIdleTimeNanos;
        if (readerIdleTime <= 0) {
            readerIdleTimeNanos = 0;
        } else {
            readerIdleTimeNanos = Math.max(unit.toNanos(readerIdleTime), MIN_TIMEOUT_NANOS);
        }
        System.out.println("readerIdleTime=60s -> readerIdleTimeNanos=" + readerIdleTimeNanos + " ns");
        System.out.println("readerIdleTime=60s -> " + TimeUnit.NANOSECONDS.toSeconds(readerIdleTimeNanos) + " s");

        // 3. 极小值保护：0.5ms 会被提升到 1ms
        long tinyTime = TimeUnit.MICROSECONDS.toNanos(500); // 0.5ms
        long result = Math.max(tinyTime, MIN_TIMEOUT_NANOS);
        System.out.println("\n--- 极小值保护 ---");
        System.out.println("0.5ms=" + tinyTime + "ns, Math.max(0.5ms, 1ms)=" + result + "ns = " +
                TimeUnit.NANOSECONDS.toMillis(result) + "ms");

        // 4. ReaderIdleTimeoutTask.run() 中的 nextDelay 计算
        System.out.println("\n--- ReaderIdleTimeoutTask nextDelay 计算 ---");
        long readerIdleTimeNanos2 = TimeUnit.SECONDS.toNanos(60); // 60s
        long lastReadTime = System.nanoTime() - TimeUnit.SECONDS.toNanos(55); // 55秒前读过
        boolean reading = false;
        long nextDelay = readerIdleTimeNanos2;
        if (!reading) {
            nextDelay -= System.nanoTime() - lastReadTime;
        }
        System.out.println("readerIdleTimeNanos=60s, 距上次读取=55s");
        System.out.println("nextDelay ≈ " + TimeUnit.NANOSECONDS.toSeconds(nextDelay) + "s (应约为5s，即还需等5s)");
        System.out.println("nextDelay <= 0? " + (nextDelay <= 0) + " (false=还没超时，重新调度)");

        // 5. 超时场景
        long lastReadTime2 = System.nanoTime() - TimeUnit.SECONDS.toNanos(65); // 65秒前读过
        long nextDelay2 = readerIdleTimeNanos2;
        if (!reading) {
            nextDelay2 -= System.nanoTime() - lastReadTime2;
        }
        System.out.println("\n距上次读取=65s (超过60s阈值)");
        System.out.println("nextDelay ≈ " + TimeUnit.NANOSECONDS.toSeconds(nextDelay2) + "s");
        System.out.println("nextDelay <= 0? " + (nextDelay2 <= 0) + " (true=触发READER_IDLE事件)");

        // 6. AllIdleTimeoutTask 中 Math.max(lastReadTime, lastWriteTime)
        System.out.println("\n--- AllIdleTimeoutTask nextDelay 计算 ---");
        long allIdleTimeNanos = TimeUnit.SECONDS.toNanos(30);
        long lastReadTime3 = System.nanoTime() - TimeUnit.SECONDS.toNanos(20); // 20秒前读
        long lastWriteTime3 = System.nanoTime() - TimeUnit.SECONDS.toNanos(10); // 10秒前写
        long nextDelay3 = allIdleTimeNanos;
        if (!reading) {
            nextDelay3 -= System.nanoTime() - Math.max(lastReadTime3, lastWriteTime3);
        }
        System.out.println("allIdleTimeNanos=30s, 距上次读=20s, 距上次写=10s");
        System.out.println("取 max(lastReadTime, lastWriteTime) = 最近活动时间(写,10s前)");
        System.out.println("nextDelay ≈ " + TimeUnit.NANOSECONDS.toSeconds(nextDelay3) + "s (应约为20s，即还需等20s)");

        // 7. state 常量值
        System.out.println("\n--- state 常量 ---");
        byte ST_INITIALIZED = 1;
        byte ST_DESTROYED = 2;
        System.out.println("ST_INITIALIZED = " + ST_INITIALIZED);
        System.out.println("ST_DESTROYED = " + ST_DESTROYED);

        // 8. firstXxxIdleEvent 语义
        System.out.println("\n--- firstXxxIdleEvent 语义 ---");
        System.out.println("第1次触发: first=true  -> FIRST_READER_IDLE_STATE_EVENT");
        System.out.println("后续触发: first=false -> READER_IDLE_STATE_EVENT");
        System.out.println("每次 channelRead 都会重置 firstReaderIdleEvent=true");

        System.out.println("\n=== 验证完成 ===");
    }
}
