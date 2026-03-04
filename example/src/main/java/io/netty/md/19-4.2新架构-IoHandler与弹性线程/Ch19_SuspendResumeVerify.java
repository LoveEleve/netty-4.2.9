package io.netty.md.ch19;

import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.util.concurrent.AutoScalingEventExecutorChooserFactory;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.EventExecutorChooserFactory.ObservableEventExecutorChooser;
import io.netty.util.concurrent.SingleThreadEventExecutor;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 第19章验证：Suspend/Resume 动态伸缩机制
 *
 * 验证目标：
 * 1. AutoScaling 的状态转换路径
 * 2. 线程的释放与复用
 * 3. 挂起后的任务唤醒
 * 4. 利用率度量
 *
 * 运行方式：在 example 模块下直接运行 main 方法
 */
public class Ch19_SuspendResumeVerify {

    // 反射获取 state 字段
    private static final Field stateField;

    static {
        try {
            stateField = SingleThreadEventExecutor.class.getDeclaredField("state");
            stateField.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String stateName(int state) {
        switch (state) {
            case 1: return "ST_NOT_STARTED";
            case 2: return "ST_SUSPENDING";
            case 3: return "ST_SUSPENDED";
            case 4: return "ST_STARTED";
            case 5: return "ST_SHUTTING_DOWN";
            case 6: return "ST_SHUTDOWN";
            case 7: return "ST_TERMINATED";
            default: return "UNKNOWN(" + state + ")";
        }
    }

    private static int getState(EventExecutor executor) {
        try {
            return stateField.getInt(executor);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("═══════════════════════════════════════════════");
        System.out.println("  Ch19 验证：Suspend/Resume 动态伸缩机制");
        System.out.println("═══════════════════════════════════════════════\n");

        verifyAutoScaling();
    }

    /**
     * 验证：AutoScaling 完整生命周期
     */
    private static void verifyAutoScaling() throws Exception {
        System.out.println("▸ 验证：AutoScaling 状态转换与线程伸缩");
        System.out.println("  配置：minThreads=1, maxThreads=4, scaleDown=0.1, scaleUp=0.8");
        System.out.println("  检测周期=2s, 耐心周期=1\n");

        // 创建弹性伸缩 EventLoopGroup
        AutoScalingEventExecutorChooserFactory chooserFactory =
                new AutoScalingEventExecutorChooserFactory(
                        1,      // minThreads
                        4,      // maxThreads
                        2,      // utilizationWindow = 2 秒
                        TimeUnit.SECONDS,
                        0.1,    // scaleDownThreshold
                        0.8,    // scaleUpThreshold
                        2,      // maxRampUpStep
                        1,      // maxRampDownStep
                        1       // scalingPatienceCycles = 1（快速触发便于测试）
                );

        MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(
                4, null, chooserFactory, NioIoHandler.newFactory());

        try {
            // ── 阶段1：初始状态 ──
            System.out.println("── 阶段1：初始状态 ──");
            printGroupState(group);

            // 提交任务让所有 EventLoop 启动
            CountDownLatch startLatch = new CountDownLatch(4);
            for (EventExecutor executor : group) {
                executor.execute(() -> {
                    System.out.println("    [启动] " + Thread.currentThread().getName() + " 开始运行");
                    startLatch.countDown();
                });
            }
            startLatch.await(5, TimeUnit.SECONDS);
            Thread.sleep(500);
            System.out.println("\n  启动后状态：");
            printGroupState(group);

            // ── 阶段2：等待空闲线程被挂起 ──
            System.out.println("\n── 阶段2：等待空闲线程被挂起 ──");
            System.out.println("  所有线程空闲中，Monitor 应检测到低利用率...");

            for (int i = 0; i < 8; i++) {
                Thread.sleep(2000);
                System.out.println("\n  [等待 " + (i + 1) * 2 + "s]");
                printGroupState(group);
                printUtilizationMetrics(group);

                int suspendedCount = countSuspended(group);
                if (suspendedCount > 0) {
                    System.out.println("  ✅ 检测到 " + suspendedCount + " 个线程被挂起！");
                    break;
                }
            }

            // ── 阶段3：提交任务唤醒挂起的线程 ──
            System.out.println("\n── 阶段3：提交任务唤醒挂起的线程 ──");
            CountDownLatch wakeLatch = new CountDownLatch(4);
            for (int i = 0; i < 4; i++) {
                final int taskId = i;
                group.next().execute(() -> {
                    System.out.println("    [唤醒] task-" + taskId + " 在 "
                            + Thread.currentThread().getName() + " 上执行");
                    wakeLatch.countDown();
                });
            }
            wakeLatch.await(5, TimeUnit.SECONDS);
            Thread.sleep(500);
            System.out.println("\n  唤醒后状态：");
            printGroupState(group);

            System.out.println("\n✅ 验证完成");
        } finally {
            // ── 阶段4：关闭 ──
            System.out.println("\n── 阶段4：优雅关闭 ──");
            group.shutdownGracefully(0, 2, TimeUnit.SECONDS).sync();
            System.out.println("  关闭后状态：");
            printGroupState(group);
        }
    }

    private static void printGroupState(MultiThreadIoEventLoopGroup group) {
        int idx = 0;
        for (EventExecutor executor : group) {
            int state = getState(executor);
            boolean suspended = executor.isSuspended();
            System.out.printf("    EventLoop[%d]: state=%d (%s), isSuspended=%s%n",
                    idx, state, stateName(state), suspended);
            idx++;
        }
    }

    @SuppressWarnings("unchecked")
    private static void printUtilizationMetrics(MultiThreadIoEventLoopGroup group) {
        try {
            // 通过反射获取 chooser 字段
            Field chooserField = null;
            Class<?> clazz = group.getClass();
            while (clazz != null) {
                try {
                    chooserField = clazz.getDeclaredField("chooser");
                    break;
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }
            if (chooserField == null) {
                System.out.println("    (无法找到 chooser 字段)");
                return;
            }
            chooserField.setAccessible(true);
            Object chooser = chooserField.get(group);
            if (chooser instanceof ObservableEventExecutorChooser) {
                ObservableEventExecutorChooser observable = (ObservableEventExecutorChooser) chooser;
                List<AutoScalingEventExecutorChooserFactory.AutoScalingUtilizationMetric> metrics =
                        observable.executorUtilizations();
                System.out.print("    利用率：[");
                for (int i = 0; i < metrics.size(); i++) {
                    if (i > 0) System.out.print(", ");
                    System.out.printf("%.2f%%", metrics.get(i).utilization() * 100);
                }
                System.out.println("]");
                System.out.println("    活跃线程数：" + observable.activeExecutorCount());
            }
        } catch (Exception e) {
            System.out.println("    (无法获取利用率指标: " + e.getMessage() + ")");
        }
    }

    private static int countSuspended(MultiThreadIoEventLoopGroup group) {
        int count = 0;
        for (EventExecutor executor : group) {
            if (executor.isSuspended()) {
                count++;
            }
        }
        return count;
    }
}
