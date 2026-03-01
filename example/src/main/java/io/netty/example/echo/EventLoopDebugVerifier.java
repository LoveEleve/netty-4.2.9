
/*
 * EventLoop Document Verification Program
 * 
 * This program uses reflection to verify all key conclusions in:
 *   - 01-eventloop-hierarchy-and-creation.md
 *   - 02-eventloop-run-loop.md
 *
 * It acts as a "programmatic debugger" — proving source code analysis via runtime evidence.
 */
package io.netty.example.echo;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.lang.reflect.Field;
import java.nio.channels.Selector;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class EventLoopDebugVerifier {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║   Netty EventLoop Document Verification (Programmatic Debug) ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        // ===== Test 1: Lazy Startup =====
        testLazyStartup();

        // ===== Test 2: Default Thread Count =====
        testDefaultThreadCount();

        // ===== Test 3: taskQueue is MpscQueue =====
        testTaskQueueType();

        // ===== Test 4: addTaskWakesUp = false (NIO) =====
        testAddTaskWakesUp();

        // ===== Test 5: Selector Optimization (SelectedSelectionKeySet) =====
        testSelectorOptimization();

        // ===== Test 6: Each EventLoop has Independent Selector =====
        testSelectorIndependence();

        // ===== Test 7: Thread Binding (Channel operations run in EventLoop thread) =====
        testThreadBinding();

        // ===== Summary =====
        System.out.println();
        System.out.println("═══════════════════════════════════════");
        System.out.printf("  RESULTS: %d PASSED, %d FAILED%n", passed, failed);
        System.out.println("═══════════════════════════════════════");

        if (failed > 0) {
            System.out.println("⚠️  Some verifications FAILED! Document may need correction.");
        } else {
            System.out.println("✅  All verifications PASSED! Document conclusions are accurate.");
        }

        System.exit(0);
    }

    // ========================================================================
    // Test 1: Lazy Startup — EventLoop thread is NOT started upon creation
    // Document claim: "EventLoop 的线程不是在创建时启动的，而是在第一次调用 execute() 时才启动"
    // ========================================================================
    static void testLazyStartup() throws Exception {
        System.out.println("━━━ Test 1: Lazy Startup ━━━");

        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        try {
            EventLoop el = group.next();

            // Use reflection to check the 'thread' field
            Field threadField = findField(el.getClass(), "thread");
            threadField.setAccessible(true);
            Thread threadBefore = (Thread) threadField.get(el);

            // Use reflection to check the 'state' field
            Field stateField = findField(el.getClass(), "state");
            stateField.setAccessible(true);
            int stateBefore = stateField.getInt(el);

            System.out.printf("  Before execute(): thread=%s, state=%d (expect null, 1=ST_NOT_STARTED)%n",
                    threadBefore, stateBefore);
            verify("Thread is null before execute()", threadBefore == null);
            verify("State is ST_NOT_STARTED(1) before execute()", stateBefore == 1);

            // Now trigger thread startup by submitting a task
            CountDownLatch latch = new CountDownLatch(1);
            el.execute(latch::countDown);
            latch.await(5, TimeUnit.SECONDS);

            Thread threadAfter = (Thread) threadField.get(el);
            int stateAfter = stateField.getInt(el);

            System.out.printf("  After execute():  thread=%s, state=%d (expect non-null, 4=ST_STARTED)%n",
                    threadAfter != null ? threadAfter.getName() : "null", stateAfter);
            verify("Thread is non-null after execute()", threadAfter != null);
            verify("State is ST_STARTED(4) after execute()", stateAfter == 4);
        } finally {
            group.shutdownGracefully().sync();
        }
        System.out.println();
    }

    // ========================================================================
    // Test 2: Default Thread Count = availableProcessors() * 2
    // Document claim: "DEFAULT_EVENT_LOOP_THREADS = availableProcessors() * 2"
    // ========================================================================
    static void testDefaultThreadCount() throws Exception {
        System.out.println("━━━ Test 2: Default Thread Count ━━━");

        EventLoopGroup group = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        try {
            // Count unique EventLoops
            Set<EventLoop> uniqueLoops = new HashSet<>();
            for (int i = 0; i < 1000; i++) {
                uniqueLoops.add(group.next());
            }

            int expected = Runtime.getRuntime().availableProcessors() * 2;
            System.out.printf("  Available processors: %d%n", Runtime.getRuntime().availableProcessors());
            System.out.printf("  Expected thread count: %d%n", expected);
            System.out.printf("  Actual EventLoop count: %d%n", uniqueLoops.size());
            verify("Default thread count == processors * 2", uniqueLoops.size() == expected);
        } finally {
            group.shutdownGracefully().sync();
        }
        System.out.println();
    }

    // ========================================================================
    // Test 3: taskQueue is MpscQueue
    // Document claim: "taskQueue 是 MpscUnboundedArrayQueue"
    // ========================================================================
    static void testTaskQueueType() throws Exception {
        System.out.println("━━━ Test 3: taskQueue Type ━━━");

        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        try {
            EventLoop el = group.next();

            Field taskQueueField = findField(el.getClass(), "taskQueue");
            taskQueueField.setAccessible(true);
            Queue<?> taskQueue = (Queue<?>) taskQueueField.get(el);

            String queueClassName = taskQueue.getClass().getName();
            System.out.printf("  taskQueue type: %s%n", queueClassName);
            verify("taskQueue is Mpsc queue",
                    queueClassName.contains("Mpsc"));

            // Also check tailTasks
            Field tailTasksField = findField(el.getClass(), "tailTasks");
            tailTasksField.setAccessible(true);
            Queue<?> tailTasks = (Queue<?>) tailTasksField.get(el);
            String tailClassName = tailTasks.getClass().getName();
            System.out.printf("  tailTasks type: %s%n", tailClassName);
            verify("tailTasks is Mpsc queue",
                    tailClassName.contains("Mpsc"));
        } finally {
            group.shutdownGracefully().sync();
        }
        System.out.println();
    }

    // ========================================================================
    // Test 4: addTaskWakesUp = false for NIO
    // Document claim: "addTaskWakesUp = false, 必须主动调用 selector.wakeup()"
    // ========================================================================
    static void testAddTaskWakesUp() throws Exception {
        System.out.println("━━━ Test 4: addTaskWakesUp == false ━━━");

        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        try {
            EventLoop el = group.next();

            Field addTaskWakesUpField = findField(el.getClass(), "addTaskWakesUp");
            addTaskWakesUpField.setAccessible(true);
            boolean addTaskWakesUp = addTaskWakesUpField.getBoolean(el);

            System.out.printf("  addTaskWakesUp: %s (expect false)%n", addTaskWakesUp);
            verify("addTaskWakesUp is false for NIO", !addTaskWakesUp);
        } finally {
            group.shutdownGracefully().sync();
        }
        System.out.println();
    }

    // ========================================================================
    // Test 5: Selector Optimization — SelectedSelectionKeySet replaces HashSet
    // Document claim: "通过反射将 HashSet 替换为数组实现的 SelectedSelectionKeySet"
    // ========================================================================
    static void testSelectorOptimization() throws Exception {
        System.out.println("━━━ Test 5: Selector Optimization ━━━");

        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        try {
            EventLoop el = group.next();

            // Get ioHandler from SingleThreadIoEventLoop
            Field ioHandlerField = findField(el.getClass(), "ioHandler");
            ioHandlerField.setAccessible(true);
            Object ioHandler = ioHandlerField.get(el);

            System.out.printf("  ioHandler type: %s%n", ioHandler.getClass().getName());
            verify("ioHandler is NioIoHandler",
                    ioHandler.getClass().getName().contains("NioIoHandler"));

            // Get selectedKeys from NioIoHandler
            Field selectedKeysField = ioHandler.getClass().getDeclaredField("selectedKeys");
            selectedKeysField.setAccessible(true);
            Object selectedKeys = selectedKeysField.get(ioHandler);

            if (selectedKeys != null) {
                String keySetClassName = selectedKeys.getClass().getName();
                System.out.printf("  selectedKeys type: %s%n", keySetClassName);
                verify("selectedKeys is SelectedSelectionKeySet (array-based)",
                        keySetClassName.contains("SelectedSelectionKeySet"));

                // Verify it has a 'keys' array field
                Field keysArrayField = selectedKeys.getClass().getDeclaredField("keys");
                keysArrayField.setAccessible(true);
                Object keysArray = keysArrayField.get(selectedKeys);
                System.out.printf("  keys array type: %s, length: %d%n",
                        keysArray.getClass().getSimpleName(),
                        java.lang.reflect.Array.getLength(keysArray));
                verify("selectedKeys backed by SelectionKey[]",
                        keysArray.getClass().getComponentType().getName().contains("SelectionKey"));
            } else {
                System.out.println("  selectedKeys is null — optimization disabled");
                verify("selectedKeys should NOT be null (optimization should be enabled)", false);
            }

            // Get selector type
            Field selectorField = ioHandler.getClass().getDeclaredField("selector");
            selectorField.setAccessible(true);
            Object selector = selectorField.get(ioHandler);
            System.out.printf("  selector type: %s%n", selector.getClass().getName());
            verify("selector is SelectedSelectionKeySetSelector (wrapper)",
                    selector.getClass().getName().contains("SelectedSelectionKeySetSelector"));

            // Get unwrappedSelector type
            Field unwrappedField = ioHandler.getClass().getDeclaredField("unwrappedSelector");
            unwrappedField.setAccessible(true);
            Object unwrappedSelector = unwrappedField.get(ioHandler);
            System.out.printf("  unwrappedSelector type: %s%n", unwrappedSelector.getClass().getName());
            verify("unwrappedSelector is JDK Selector impl",
                    unwrappedSelector instanceof Selector);
        } finally {
            group.shutdownGracefully().sync();
        }
        System.out.println();
    }

    // ========================================================================
    // Test 6: Each EventLoop has Independent Selector
    // Document claim: "每个 NioIoHandler 持有独立的 Selector 实例"
    // ========================================================================
    static void testSelectorIndependence() throws Exception {
        System.out.println("━━━ Test 6: Selector Independence ━━━");

        EventLoopGroup group = new MultiThreadIoEventLoopGroup(3, NioIoHandler.newFactory());
        try {
            Set<Object> selectors = new HashSet<>();
            Set<Object> ioHandlers = new HashSet<>();

            for (int i = 0; i < 3; i++) {
                EventLoop el = group.next();
                Field ioHandlerField = findField(el.getClass(), "ioHandler");
                ioHandlerField.setAccessible(true);
                Object ioHandler = ioHandlerField.get(el);
                ioHandlers.add(ioHandler);

                Field selectorField = ioHandler.getClass().getDeclaredField("unwrappedSelector");
                selectorField.setAccessible(true);
                Object selector = selectorField.get(ioHandler);
                selectors.add(selector);
            }

            System.out.printf("  Unique IoHandlers: %d (expect 3)%n", ioHandlers.size());
            System.out.printf("  Unique Selectors: %d (expect 3)%n", selectors.size());
            verify("Each EventLoop has unique IoHandler", ioHandlers.size() == 3);
            verify("Each EventLoop has unique Selector", selectors.size() == 3);
        } finally {
            group.shutdownGracefully().sync();
        }
        System.out.println();
    }

    // ========================================================================
    // Test 7: Thread Binding — All Channel operations run in the same EventLoop thread
    // Document claim: "一个 Channel 的所有 IO 操作都绑定到同一个 EventLoop 线程"
    // ========================================================================
    static void testThreadBinding() throws Exception {
        System.out.println("━━━ Test 7: Thread Binding ━━━");

        EventLoopGroup group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        try {
            AtomicReference<String> bindThreadName = new AtomicReference<>();
            AtomicReference<String> activeThreadName = new AtomicReference<>();
            AtomicBoolean inEventLoop = new AtomicBoolean(false);
            CountDownLatch latch = new CountDownLatch(1);

            ServerBootstrap b = new ServerBootstrap();
            b.group(group)
             .channel(NioServerSocketChannel.class)
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 public void initChannel(SocketChannel ch) {}
             })
             .handler(new ChannelInboundHandlerAdapter() {
                 @Override
                 public void channelRegistered(ChannelHandlerContext ctx) {
                     bindThreadName.set(Thread.currentThread().getName());
                 }

                 @Override
                 public void channelActive(ChannelHandlerContext ctx) {
                     activeThreadName.set(Thread.currentThread().getName());
                     inEventLoop.set(ctx.channel().eventLoop().inEventLoop());
                     latch.countDown();
                 }
             });

            ChannelFuture f = b.bind(0).sync();
            latch.await(5, TimeUnit.SECONDS);

            System.out.printf("  channelRegistered thread: %s%n", bindThreadName.get());
            System.out.printf("  channelActive thread:     %s%n", activeThreadName.get());
            System.out.printf("  inEventLoop():            %s%n", inEventLoop.get());

            verify("channelRegistered and channelActive run in same thread",
                    bindThreadName.get() != null && bindThreadName.get().equals(activeThreadName.get()));
            verify("Handler runs in EventLoop thread (inEventLoop=true)", inEventLoop.get());

            f.channel().close().sync();
        } finally {
            group.shutdownGracefully().sync();
        }
        System.out.println();
    }

    // ========================================================================
    // Utility methods
    // ========================================================================

    /**
     * Find a field by name, walking up the class hierarchy.
     */
    static Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new RuntimeException("Field '" + fieldName + "' not found in hierarchy of " + clazz.getName());
    }

    static void verify(String description, boolean condition) {
        if (condition) {
            System.out.printf("  ✅ PASS: %s%n", description);
            passed++;
        } else {
            System.out.printf("  ❌ FAIL: %s%n", description);
            failed++;
        }
    }
}
