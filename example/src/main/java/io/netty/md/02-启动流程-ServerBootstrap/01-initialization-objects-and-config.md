# 02-初始化阶段：对象创建与配置（bind()之前发生了什么？）

> **阅读目标**：搞清楚 `ServerBootstrap.bind(PORT)` 之前，内存中到底创建了哪些对象、它们之间的持有关系是什么、每个配置方法到底做了什么。
>
> 遵循 Rule #1（先搞懂数据结构再看行为）：本篇**只看对象创建和配置**，不看 bind() 的执行。
> 遵循 Rule #10（全量分析）：每一行源码都配解释，不跳过。

---

## 一、EchoServer 初始化阶段代码全貌

```java
// ========== 阶段 1：创建对象 ==========
// 第 ① 步：创建 IoHandlerFactory（工厂，还没创建 IoHandler）
IoHandlerFactory factory = NioIoHandler.newFactory();

// 第 ② 步：创建 EventLoopGroup（创建了 N 个 EventLoop，每个 EventLoop 持有一个 IoHandler）
EventLoopGroup group = new MultiThreadIoEventLoopGroup(factory);

// 第 ③ 步：创建业务 Handler
final EchoServerHandler serverHandler = new EchoServerHandler();

// 第 ④ 步：创建 ServerBootstrap（空壳）
ServerBootstrap b = new ServerBootstrap();

// ========== 阶段 2：配置 ServerBootstrap ==========
b.group(group)                                    // → 存到 AbstractBootstrap.group 和 ServerBootstrap.childGroup
 .channel(NioServerSocketChannel.class)           // → 创建 ReflectiveChannelFactory 存到 AbstractBootstrap.channelFactory
 .option(ChannelOption.SO_BACKLOG, 100)           // → 存到 AbstractBootstrap.options Map
 .handler(new LoggingHandler(LogLevel.INFO))      // → 存到 AbstractBootstrap.handler（Boss Pipeline 用）
 .childHandler(new ChannelInitializer<>() {...}); // → 存到 ServerBootstrap.childHandler（Worker Pipeline 用）

// ========== 阶段 3：bind() — 下一篇分析 ==========
ChannelFuture f = b.bind(PORT).sync();
```

> 💡 **核心洞察**：bind() 之前的代码**没有任何 IO 操作**，全部都是在内存中构建对象和配置。bind() 才是真正"开始干活"的入口。

---

## 二、第 ① 步：NioIoHandler.newFactory() — 创建 IO 处理器工厂

### 2.1 源码全量分析

> 📍 源码位置：`transport/src/main/java/io/netty/channel/nio/NioIoHandler.java`

```java
// 无参 newFactory() — 用户调用的入口
public static IoHandlerFactory newFactory() {
    // SelectorProvider.provider() — 获取 JDK 默认的 SelectorProvider
    //   Linux 上返回 EPollSelectorProvider
    //   macOS 上返回 KQueueSelectorProvider
    //   Windows 上返回 WindowsSelectorProvider
    // DefaultSelectStrategyFactory.INSTANCE — 默认的 Select 策略工厂（单例）
    return newFactory(SelectorProvider.provider(), DefaultSelectStrategyFactory.INSTANCE);
}

// 带参 newFactory() — 真正的创建逻辑
public static IoHandlerFactory newFactory(final SelectorProvider selectorProvider,
                                          final SelectStrategyFactory selectStrategyFactory) {
    ObjectUtil.checkNotNull(selectorProvider, "selectorProvider");
    ObjectUtil.checkNotNull(selectStrategyFactory, "selectStrategyFactory");

    // 返回一个匿名 IoHandlerFactory 实现
    return new IoHandlerFactory() {
        @Override
        public IoHandler newHandler(ThreadAwareExecutor executor) {
            // 注意！这里才真正 new NioIoHandler
            // 此时还没被调用！只是定义了"怎么创建"
            return new NioIoHandler(executor, selectorProvider, selectStrategyFactory.newSelectStrategy());
        }

        @Override
        public boolean isChangingThreadSupported() {
            return true;  // NIO 支持线程切换
        }
    };
}
```

### 2.2 IoHandlerFactory 接口定义

> 📍 源码位置：`transport/src/main/java/io/netty/channel/IoHandlerFactory.java`

```java
public interface IoHandlerFactory {
    // 创建一个新的 IoHandler 实例
    // ioExecutor — 就是 EventLoop 自己（它实现了 ThreadAwareExecutor 接口）
    IoHandler newHandler(ThreadAwareExecutor ioExecutor);

    // 是否支持运行时切换线程（4.2 新增特性）
    default boolean isChangingThreadSupported() {
        return false;
    }
}
```

### 2.3 此步骤的对象关系

```
执行 NioIoHandler.newFactory() 之后，内存中只有：

┌─────────────────────────────────┐
│   IoHandlerFactory（匿名实现）    │
│                                  │
│   持有（通过闭包捕获）：            │
│   ├── selectorProvider           │ ← JDK SelectorProvider（Linux: EPollSelectorProvider）
│   └── selectStrategyFactory      │ ← DefaultSelectStrategyFactory.INSTANCE（单例）
│                                  │
│   能力：newHandler(executor)      │ → 将来调用时才创建 NioIoHandler
│         isChangingThreadSupported │ → true
└─────────────────────────────────┘
```

> ⚠️ **注意**：此时**还没有创建 Selector**！NioIoHandler 是懒创建的，要等到每个 EventLoop 初始化时才通过 `newHandler()` 创建。

### 2.4 为什么不直接 new NioIoHandler？为什么要用工厂模式？

因为一个 `EventLoopGroup` 包含 N 个 `EventLoop`，每个 `EventLoop` 需要**独立的** `NioIoHandler`（独立的 Selector）。工厂模式让 Group 可以按需为每个 EventLoop 创建独立的 IoHandler。如果直接 new 一个 NioIoHandler 传进去，那 N 个 EventLoop 就共享同一个 Selector，这是不正确的。

---

## 三、第 ② 步：new MultiThreadIoEventLoopGroup(factory) — 创建线程组

### 3.1 继承链全景 🔥

```
MultiThreadIoEventLoopGroup
    ↑ extends
MultithreadEventLoopGroup                ← 确定默认线程数（CPU*2）
    ↑ extends
MultithreadEventExecutorGroup            ← 核心！创建 children[] 数组
    ↑ extends
AbstractEventExecutorGroup               ← 提供 submit/schedule 等便捷方法
    ↑ implements
EventExecutorGroup                       ← 顶级接口
```

> 🔥 **面试常考**：这个继承链是理解 Netty 线程模型的基础，尤其是 `MultithreadEventExecutorGroup` 中的 `children[]` 数组。

### 3.2 源码全量分析：构造链调用路径

#### 3.2.1 入口：MultiThreadIoEventLoopGroup

```java
// 用户调用：new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory())
public MultiThreadIoEventLoopGroup(IoHandlerFactory ioHandlerFactory) {
    this(0, ioHandlerFactory);  // nThreads = 0，表示使用默认线程数
}

public MultiThreadIoEventLoopGroup(int nThreads, IoHandlerFactory ioHandlerFactory) {
    this(nThreads, (Executor) null, ioHandlerFactory);  // executor = null
}

public MultiThreadIoEventLoopGroup(int nThreads, Executor executor,
                                   IoHandlerFactory ioHandlerFactory) {
    super(nThreads, executor, ioHandlerFactory);
    //     ↑ 调用父类 MultithreadEventLoopGroup 构造器
    //       args[0] = ioHandlerFactory（通过 varargs 传递）
}
```

#### 3.2.2 父类：MultithreadEventLoopGroup — 确定默认线程数

> 📍 源码位置：`transport/src/main/java/io/netty/channel/MultithreadEventLoopGroup.java`

```java
public abstract class MultithreadEventLoopGroup extends MultithreadEventExecutorGroup
                                                implements EventLoopGroup {

    private static final int DEFAULT_EVENT_LOOP_THREADS;

    static {
        // 默认线程数 = max(1, CPU核心数 * 2)
        // 可通过 -Dio.netty.eventLoopThreads=N 覆盖
        DEFAULT_EVENT_LOOP_THREADS = Math.max(1, SystemPropertyUtil.getInt(
                "io.netty.eventLoopThreads", NettyRuntime.availableProcessors() * 2));
    }

    protected MultithreadEventLoopGroup(int nThreads, Executor executor, Object... args) {
        // 如果 nThreads == 0，使用默认线程数（CPU*2）
        super(nThreads == 0 ? DEFAULT_EVENT_LOOP_THREADS : nThreads, executor, args);
        //     ↑ 继续调用祖父类 MultithreadEventExecutorGroup
    }
}
```

> 🔥 **面试常考**：Netty 默认线程数是 `CPU核心数 × 2`。例如 8 核机器默认 16 个 EventLoop。

#### 3.2.3 祖父类：MultithreadEventExecutorGroup — **核心中的核心** 🔥

> 📍 源码位置：`common/src/main/java/io/netty/util/concurrent/MultithreadEventExecutorGroup.java`

```java
public abstract class MultithreadEventExecutorGroup extends AbstractEventExecutorGroup {

    // ========== 核心字段 ==========
    private final EventExecutor[] children;           // EventLoop 数组！这就是线程池的本体！
    private final Set<EventExecutor> readonlyChildren; // children 的只读视图
    private final AtomicInteger terminatedChildren;    // 已终止的 EventLoop 计数
    private final Promise<?> terminationFuture;        // 全部终止时的通知 Future
    private final EventExecutorChooser chooser;        // 选择器：next() 时用哪个 EventLoop

    // ========== 中间构造器（补上默认 ChooserFactory） ==========
    // 从 MultithreadEventLoopGroup 调用到这里：
    protected MultithreadEventExecutorGroup(int nThreads, Executor executor, Object... args) {
        this(nThreads, executor, DefaultEventExecutorChooserFactory.INSTANCE, args);
        //                       ↑ 默认使用 DefaultEventExecutorChooserFactory（轮询策略）
    }

    // ========== 最终构造器 ==========
    protected MultithreadEventExecutorGroup(int nThreads, Executor executor,
                                            EventExecutorChooserFactory chooserFactory,
                                            Object... args) {
        // 【第 1 步】参数校验
        checkPositive(nThreads, "nThreads");

        // 【第 2 步】如果 executor 为 null，创建默认的 ThreadPerTaskExecutor
        if (executor == null) {
            executor = new ThreadPerTaskExecutor(newDefaultThreadFactory());
            // ThreadPerTaskExecutor：每提交一个任务就创建一个新线程
            // newDefaultThreadFactory()：创建 DefaultThreadFactory，线程名格式为 "nioEventLoopGroup-X-Y"
        }

        // 【第 3 步】创建 children 数组 ← 核心！
        children = new EventExecutor[nThreads];

        // 【第 4 步】循环创建每个 EventLoop
        for (int i = 0; i < nThreads; i++) {
            boolean success = false;
            try {
                // newChild 是抽象方法，由 MultiThreadIoEventLoopGroup 实现
                // args[0] = ioHandlerFactory
                children[i] = newChild(executor, args);
                success = true;
            } catch (Exception e) {
                throw new IllegalStateException("failed to create a child event loop", e);
            } finally {
                // 如果某个 child 创建失败，关闭已创建的所有 child
                if (!success) {
                    for (int j = 0; j < i; j++) {
                        children[j].shutdownGracefully();
                    }
                    for (int j = 0; j < i; j++) {
                        EventExecutor e = children[j];
                        try {
                            while (!e.isTerminated()) {
                                e.awaitTermination(Integer.MAX_VALUE, TimeUnit.SECONDS);
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        }

        // 【第 5 步】创建 Chooser（选择策略）
        // DefaultEventExecutorChooserFactory 会根据 nThreads 是否为 2 的幂次来选择优化版本
        // - 2 的幂次 → PowerOfTwoEventExecutorChooser（用位运算 &）
        // - 否则    → GenericEventExecutorChooser（用取模 %）
        chooser = chooserFactory.newChooser(children);

        // 【第 6 步】给每个 child 注册终止监听器
        final FutureListener<Object> terminationListener = new FutureListener<Object>() {
            @Override
            public void operationComplete(Future<Object> future) throws Exception {
                // 每个 EventLoop 终止时，计数 +1
                // 当所有 EventLoop 都终止时，设置 terminationFuture 为成功
                if (terminatedChildren.incrementAndGet() == children.length) {
                    terminationFuture.setSuccess(null);
                }
            }
        };

        for (EventExecutor e : children) {
            e.terminationFuture().addListener(terminationListener);
        }

        // 【第 7 步】创建只读视图
        Set<EventExecutor> childrenSet = new LinkedHashSet<>(children.length);
        Collections.addAll(childrenSet, children);
        readonlyChildren = Collections.unmodifiableSet(childrenSet);
    }
}
```

> ⚠️ **生产踩坑**：如果 `newChild()` 抛异常（比如打开 Selector 失败——fd 不够用），已创建的 EventLoop 会被 `shutdownGracefully()` 清理。所以生产环境要注意文件描述符限制（`ulimit -n`）。

#### 3.2.4 newChild 的具体实现：MultiThreadIoEventLoopGroup.newChild()

```java
// MultiThreadIoEventLoopGroup 中：
@Override
protected EventLoop newChild(Executor executor, Object... args) throws Exception {
    // args[0] 就是 IoHandlerFactory
    IoHandlerFactory handlerFactory = (IoHandlerFactory) args[0];
    Object[] argsCopy;
    if (args.length > 1) {
        argsCopy = new Object[args.length - 1];
        System.arraycopy(args, 1, argsCopy, 0, argsCopy.length);
    } else {
        argsCopy = EmptyArrays.EMPTY_OBJECTS;  // 没有额外参数
    }
    return newChild(executor, handlerFactory, argsCopy);
}

// 最终创建 SingleThreadIoEventLoop
protected IoEventLoop newChild(Executor executor, IoHandlerFactory ioHandlerFactory,
                               Object... args) {
    return new SingleThreadIoEventLoop(this, executor, ioHandlerFactory);
    //                                  ↑     ↑         ↑
    //                                parent  任务执行器   IO处理器工厂
}
```

#### 3.2.5 SingleThreadIoEventLoop 构造器 — 创建 IoHandler 的关键时刻 🔥

> 📍 源码位置：`transport/src/main/java/io/netty/channel/SingleThreadIoEventLoop.java`

```java
public class SingleThreadIoEventLoop extends SingleThreadEventLoop implements IoEventLoop {

    private final long maxTaskProcessingQuantumNs;  // 单次最大任务处理时间（默认 1000ms）
    private final IoHandlerContext context;          // IoHandler 的运行上下文
    private final IoHandler ioHandler;               // ← 核心！每个 EventLoop 独享的 IO 处理器
    private final AtomicInteger numRegistrations;    // 注册的 Channel 数量

    public SingleThreadIoEventLoop(IoEventLoopGroup parent, Executor executor,
                                   IoHandlerFactory ioHandlerFactory) {
        super(parent, executor, false,
                ObjectUtil.checkNotNull(ioHandlerFactory, "ioHandlerFactory").isChangingThreadSupported());
        //      ↑ addTaskWakesUp = false（NIO 不需要通过添加任务来唤醒）
        //                                              ↑ threadChangingSupported = true（NIO 支持）

        this.maxTaskProcessingQuantumNs = DEFAULT_MAX_TASK_PROCESSING_QUANTUM_NS;

        // ★★★ 关键！这里调用了 IoHandlerFactory.newHandler(this) ★★★
        // this = 当前的 SingleThreadIoEventLoop（它实现了 ThreadAwareExecutor）
        // 对于 NIO：会创建 NioIoHandler，其中会打开一个新的 Selector！
        this.ioHandler = ioHandlerFactory.newHandler(this);
    }
}
```

#### 3.2.6 NioIoHandler 构造器 — Selector 在这里被创建！

```java
private NioIoHandler(ThreadAwareExecutor executor, SelectorProvider selectorProvider,
                     SelectStrategy strategy) {
    this.executor = ObjectUtil.checkNotNull(executor, "executionContext");
    this.provider = ObjectUtil.checkNotNull(selectorProvider, "selectorProvider");
    this.selectStrategy = ObjectUtil.checkNotNull(strategy, "selectStrategy");

    // ★ openSelector() 打开 Selector，并做了 SelectionKeySet 优化！
    final SelectorTuple selectorTuple = openSelector();
    this.selector = selectorTuple.selector;             // 优化后的 Selector（如果优化成功）
    this.unwrappedSelector = selectorTuple.unwrappedSelector;  // 原始 Selector
}
```

> 🔥 **面试常考**：每个 `NioIoHandler`（即每个 EventLoop）都有**自己独立的 Selector**。这就是为什么 Netty 不存在 Selector 竞争问题——每个线程操作自己的 Selector。

### 3.3 此步骤完成后的完整对象关系图 🔥

```
new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory()) 执行完毕后：

┌─────────────────────────────────────────────────────────────────────────┐
│              MultiThreadIoEventLoopGroup                                │
│                                                                         │
│   children[]: EventExecutor[]  (假设 8 核 → 16 个元素)                    │
│   ┌─────────────────────┐  ┌─────────────────────┐       ┌───────────┐ │
│   │ SingleThreadIoEL #0 │  │ SingleThreadIoEL #1 │  ...  │ ...#15    │ │
│   │                     │  │                     │       │           │ │
│   │  ioHandler:         │  │  ioHandler:         │       │           │ │
│   │  ┌───────────────┐  │  │  ┌───────────────┐  │       │           │ │
│   │  │ NioIoHandler  │  │  │  │ NioIoHandler  │  │       │           │ │
│   │  │  selector ────┼──┼──┼──┼→ Selector#0   │  │       │           │ │
│   │  │  provider     │  │  │  │  selector ────┼──┼──→ Selector#1    │ │
│   │  └───────────────┘  │  │  └───────────────┘  │       │           │ │
│   │                     │  │                     │       │           │ │
│   │  taskQueue: MpscQ   │  │  taskQueue: MpscQ   │       │           │ │
│   │  tailTaskQ: MpscQ   │  │  tailTaskQ: MpscQ   │       │           │ │
│   │  executor ──────────┼──┼──┼→ ThreadPerTaskExec│       │           │ │
│   │  thread: null       │  │  thread: null        │       │           │ │
│   │  (懒启动，还没线程)   │  │  (懒启动，还没线程)    │       │           │ │
│   └─────────────────────┘  └─────────────────────┘       └───────────┘ │
│                                                                         │
│   chooser: EventExecutorChooser                                         │
│     └── next() → 轮询返回 children[i] （PowerOfTwo 或 Generic）           │
│                                                                         │
│   terminatedChildren: AtomicInteger(0)                                  │
│   terminationFuture: DefaultPromise                                     │
└─────────────────────────────────────────────────────────────────────────┘
```

### 3.4 关键不变式（Invariants）

1. **每个 EventLoop 拥有独立的 IoHandler（和 Selector）** — 无竞争
2. **EventLoop 的线程是懒启动的** — 构造时 `thread = null`，第一个任务提交时才创建线程
3. **children 数组一旦创建就不可变** — 线程数在启动时确定，运行时不能增减

---

## 四、第 ③ 步：new EchoServerHandler() — 创建业务 Handler

```java
final EchoServerHandler serverHandler = new EchoServerHandler();
```

这是用户自定义的业务 Handler，非常简单：

```java
@Sharable
public class EchoServerHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ctx.write(msg);  // 收到什么写回什么
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();  // 读取完成后刷新
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();  // 异常关闭连接
    }
}
```

> 💡 注意 `@Sharable` 注解 — 标记这个 Handler 可以被多个 Channel 共享。因为 `serverHandler` 是一个实例被所有连接复用的。如果 Handler 有状态（如计数器），就不能加 `@Sharable`。
>
> ⚠️ **生产踩坑**：如果一个有状态的 Handler 误加了 `@Sharable` 并被多个 Channel 共享，会导致线程安全问题。Netty 在 `addLast()` 时会检查：如果 Handler 没有 `@Sharable` 注解但已经被添加过，会抛异常。

---

## 五、第 ④ 步：new ServerBootstrap() — 创建启动器（空壳）

### 5.1 ServerBootstrap 数据结构全景

```java
// ServerBootstrap 自己的字段：
public class ServerBootstrap extends AbstractBootstrap<ServerBootstrap, ServerChannel> {
    private final Map<ChannelOption<?>, Object> childOptions;   // Worker Channel 的选项
    private final Map<AttributeKey<?>, Object> childAttrs;      // Worker Channel 的属性
    private final ServerBootstrapConfig config;                  // 配置快照封装
    private volatile EventLoopGroup childGroup;                  // Worker 线程组
    private volatile ChannelHandler childHandler;                // Worker Handler

    public ServerBootstrap() { }  // 空构造器，什么都不做
}

// AbstractBootstrap 的字段（被 ServerBootstrap 继承）：
public abstract class AbstractBootstrap<B, C extends Channel> {
    volatile EventLoopGroup group;                               // Boss 线程组
    private volatile ChannelFactory<? extends C> channelFactory; // Channel 工厂
    private volatile SocketAddress localAddress;                 // 绑定地址
    private final Map<ChannelOption<?>, Object> options;         // Boss Channel 的选项
    private final Map<AttributeKey<?>, Object> attrs;            // Boss Channel 的属性
    private volatile ChannelHandler handler;                      // Boss Handler
    private volatile ClassLoader extensionsClassLoader;           // 扩展类加载器
}
```

### 5.2 new ServerBootstrap() 之后的对象状态

```
┌─────────────────────────────────────────────┐
│              ServerBootstrap                  │
│                                               │
│  ── 继承自 AbstractBootstrap ──                │
│  group:           null                        │
│  channelFactory:  null                        │
│  localAddress:    null                        │
│  options:         {} (空 LinkedHashMap)        │
│  attrs:           {} (空 ConcurrentHashMap)    │
│  handler:         null                        │
│                                               │
│  ── ServerBootstrap 自有 ──                    │
│  childGroup:      null                        │
│  childHandler:    null                        │
│  childOptions:    {} (空 LinkedHashMap)        │
│  childAttrs:      {} (空 ConcurrentHashMap)    │
│  config:          ServerBootstrapConfig(this)  │
└─────────────────────────────────────────────┘

所有字段都是 null 或空集合 — 这就是一个"空壳"。
```

> 💡 **设计模式**：ServerBootstrap 使用了 **Builder 模式**（链式调用），先创建空壳，再通过 `group()`、`channel()` 等方法逐步填充配置。

---

## 六、阶段 2：配置链方法逐行分析

### 6.1 b.group(group) — 设置线程组

> 📍 源码位置：`ServerBootstrap.java`

```java
// ServerBootstrap 重写了 group(EventLoopGroup) 方法：
@Override
public ServerBootstrap group(EventLoopGroup group) {
    return group(group, group);  // ← 注意！Boss 和 Worker 用的是同一个 Group！
}

// 双参数版本：
public ServerBootstrap group(EventLoopGroup parentGroup, EventLoopGroup childGroup) {
    super.group(parentGroup);
    //   ↑ 调用 AbstractBootstrap.group()：
    //     this.group = parentGroup;  （Boss Group）

    if (this.childGroup != null) {
        throw new IllegalStateException("childGroup set already");
    }
    this.childGroup = ObjectUtil.checkNotNull(childGroup, "childGroup");
    //   ↑ 设置 Worker Group

    return this;
}
```

> 📍 源码位置：`AbstractBootstrap.java`

```java
// AbstractBootstrap.group()：
public B group(EventLoopGroup group) {
    ObjectUtil.checkNotNull(group, "group");
    if (this.group != null) {
        throw new IllegalStateException("group set already");  // 不能重复设置
    }
    this.group = group;  // 存储 Boss Group
    return self();       // return (B) this; — 返回自己，支持链式调用
}
```

> 🔥 **核心洞察**：EchoServer 中只传了一个 `group`，所以 **Boss 和 Worker 用的是同一个 EventLoopGroup**！这意味着 accept 连接和处理 IO 在同一个线程池中。如果要分离 Boss 和 Worker，需要这样写：
> ```java
> EventLoopGroup bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
> EventLoopGroup workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
> b.group(bossGroup, workerGroup);
> ```

### 6.2 .channel(NioServerSocketChannel.class) — 设置 Channel 类型

> 📍 源码位置：`AbstractBootstrap.java`

```java
public B channel(Class<? extends C> channelClass) {
    return channelFactory(new ReflectiveChannelFactory<C>(
            ObjectUtil.checkNotNull(channelClass, "channelClass")
    ));
}
```

> 📍 源码位置：`ReflectiveChannelFactory.java`

```java
public class ReflectiveChannelFactory<T extends Channel> implements ChannelFactory<T> {

    private final Constructor<? extends T> constructor;  // Channel 的无参构造器

    public ReflectiveChannelFactory(Class<? extends T> clazz) {
        ObjectUtil.checkNotNull(clazz, "clazz");
        try {
            // 通过反射获取无参构造器，并缓存
            this.constructor = clazz.getConstructor();
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("Class " + StringUtil.simpleClassName(clazz) +
                    " does not have a public non-arg constructor", e);
            // 如果 Channel 类没有无参构造器，直接报错
        }
    }

    @Override
    public T newChannel() {
        try {
            return constructor.newInstance();  // 反射创建 Channel 实例
        } catch (Throwable t) {
            throw new ChannelException("Unable to create Channel from class " +
                    constructor.getDeclaringClass(), t);
        }
    }
}
```

然后存储到 AbstractBootstrap：

```java
public B channelFactory(ChannelFactory<? extends C> channelFactory) {
    ObjectUtil.checkNotNull(channelFactory, "channelFactory");
    if (this.channelFactory != null) {
        throw new IllegalStateException("channelFactory set already");  // 不能重复设置
    }
    this.channelFactory = channelFactory;  // 存储工厂
    return self();
}
```

> 💡 **此时 NioServerSocketChannel 还没被创建！** 只是拿到了它的 Constructor，存在 ReflectiveChannelFactory 里。真正 `new NioServerSocketChannel()` 要等到 `bind()` 里的 `initAndRegister()` 调用。

### 6.3 .option(ChannelOption.SO_BACKLOG, 100) — 设置 Boss Channel 选项

> 📍 源码位置：`AbstractBootstrap.java`

```java
public <T> B option(ChannelOption<T> option, T value) {
    ObjectUtil.checkNotNull(option, "option");
    synchronized (options) {  // 加锁保护 LinkedHashMap
        if (value == null) {
            options.remove(option);  // value 为 null 表示移除选项
        } else {
            options.put(option, value);  // 存入 options Map
        }
    }
    return self();
}
```

> `SO_BACKLOG = 100` 是 TCP 全连接队列大小。这个值存在 `AbstractBootstrap.options` 里，会在 `init()` 时设置到 ServerSocketChannel 上。
>
> ⚠️ **生产踩坑**：如果 `SO_BACKLOG` 设太小（比如默认 50），在高并发场景下新连接会被拒绝。生产建议至少 `1024`。

### 6.4 .handler(new LoggingHandler(LogLevel.INFO)) — 设置 Boss Handler

> 📍 源码位置：`AbstractBootstrap.java`

```java
public B handler(ChannelHandler handler) {
    this.handler = ObjectUtil.checkNotNull(handler, "handler");
    return self();
}
```

> 这个 `handler` 会被添加到 **ServerSocketChannel 的 Pipeline** 中（Boss Pipeline），用于记录 accept 事件的日志。

### 6.5 .childHandler(new ChannelInitializer<>() {...}) — 设置 Worker Handler 🔥

> 📍 源码位置：`ServerBootstrap.java`

```java
public ServerBootstrap childHandler(ChannelHandler childHandler) {
    this.childHandler = ObjectUtil.checkNotNull(childHandler, "childHandler");
    return this;
}
```

> 这个 `childHandler` 会被用于初始化**每个新连接的 SocketChannel Pipeline**（Worker Pipeline）。
> 
> `ChannelInitializer` 是一个特殊的 Handler——它在 Channel 注册时执行 `initChannel()` 添加真正的 Handler，然后把自己从 Pipeline 中移除。这是一个**一次性 Handler**。

---

## 七、配置完成后的完整对象关系图 🔥🔥🔥

```
bind() 之前，内存中的完整对象关系：

┌─────────────────────────────────────────────────────────────────────┐
│                        ServerBootstrap                               │
│                                                                       │
│  ┌─ 继承自 AbstractBootstrap ─────────────────────────────────────┐   │
│  │ group ──────────────────────┐                                  │   │
│  │ channelFactory ────────┐    │                                  │   │
│  │ options: {SO_BACKLOG:100}   │                                  │   │
│  │ handler ───────────┐   │    │                                  │   │
│  └────────────────────┼───┼────┼──────────────────────────────────┘   │
│                       │   │    │                                       │
│  childGroup ──────────┼───┼────┤  (同一个 group！)                     │
│  childHandler ────┐   │   │    │                                       │
│  childOptions: {} │   │   │    │                                       │
│  childAttrs:   {} │   │   │    │                                       │
└───────────────────┼───┼───┼────┼───────────────────────────────────────┘
                    │   │   │    │
                    ▼   │   ▼    ▼
  ChannelInitializer    │  LoggingHandler
  (匿名内部类)          │  (Boss Pipeline 用)
  initChannel() {       │
    p.addLast(ssl...)   │
    p.addLast(echoH)    │
  }                     │
                        ▼
          ReflectiveChannelFactory
          constructor: NioServerSocketChannel.<init>()
          (还没创建 Channel！只是持有 Constructor)

                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│              MultiThreadIoEventLoopGroup (16个线程)                   │
│                                                                       │
│  children[0]: SingleThreadIoEventLoop                                 │
│    ├── ioHandler: NioIoHandler { selector: Selector#0 }               │
│    ├── taskQueue: MpscQueue (空)                                      │
│    ├── thread: null (懒启动)                                          │
│    └── numRegistrations: 0                                            │
│                                                                       │
│  children[1]: SingleThreadIoEventLoop                                 │
│    ├── ioHandler: NioIoHandler { selector: Selector#1 }               │
│    └── ...                                                            │
│                                                                       │
│  ... (共 16 个)                                                       │
│                                                                       │
│  children[15]: SingleThreadIoEventLoop                                │
│    ├── ioHandler: NioIoHandler { selector: Selector#15 }              │
│    └── ...                                                            │
│                                                                       │
│  chooser: PowerOfTwoEventExecutorChooser (16是2的幂)                   │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 七-B、bind() 之前所有被创建对象的 **完整清单**（含隐式对象）🔥🔥🔥

> 前面几节按"用户视角"介绍了主要对象，但很多对象隐藏在**父类构造器、字段初始化器、静态初始化块**中，不看源码根本注意不到。这里做一个**无死角的完整盘点**。
>
> 遵循 Rule #10（全量分析不跳过一行）和 Rule #1（先搞懂数据结构再看行为）。

### ⓪ NioIoHandler.newFactory() 创建的对象

| # | 对象 | 类型 | 创建时机 | 说明 |
|---|------|------|---------|------|
| 1 | `selectorProvider` | `SelectorProvider` | `SelectorProvider.provider()` | JDK 单例，Linux 上是 `EPollSelectorProvider` |
| 2 | `DefaultSelectStrategyFactory.INSTANCE` | `DefaultSelectStrategyFactory` | 类加载时（static final） | 单例，用于创建 `SelectStrategy` |
| 3 | `DefaultSelectStrategy.INSTANCE` | `DefaultSelectStrategy` | 类加载时（static final） | 单例，`calculateStrategy()` 的默认实现 |
| 4 | **匿名 `IoHandlerFactory`** | `IoHandlerFactory` | `newFactory()` 返回时 | 闭包捕获了 selectorProvider 和 selectStrategyFactory |

### ① new MultiThreadIoEventLoopGroup(factory) — 构造链中创建的所有对象

#### A. 静态初始化（类加载时触发，只执行一次）

| # | 对象 | 所在类 | 说明 |
|---|------|--------|------|
| 5 | `DEFAULT_EVENT_LOOP_THREADS` (int) | `MultithreadEventLoopGroup` | `max(1, CPU*2)`，通过 `SystemPropertyUtil.getInt()` 读取 |
| 6 | `STATE_UPDATER` | `SingleThreadEventExecutor` | `AtomicIntegerFieldUpdater`，用于 CAS 更新 state 字段 |
| 7 | `PROPERTIES_UPDATER` | `SingleThreadEventExecutor` | `AtomicReferenceFieldUpdater`，CAS 更新 threadProperties |
| 8 | `ACCUMULATED_ACTIVE_TIME_NANOS_UPDATER` | `SingleThreadEventExecutor` | `AtomicLongFieldUpdater`，CAS 更新 accumulatedActiveTimeNanos |
| 9 | `CONSECUTIVE_IDLE_CYCLES_UPDATER` | `SingleThreadEventExecutor` | `AtomicIntegerFieldUpdater` |
| 10 | `CONSECUTIVE_BUSY_CYCLES_UPDATER` | `SingleThreadEventExecutor` | `AtomicIntegerFieldUpdater` |
| 11 | `NOOP_TASK` | `SingleThreadEventExecutor` | 静态 `Runnable`，空操作占位任务 |
| 12 | `SCHEDULED_FUTURE_TASK_COMPARATOR` | `AbstractScheduledEventExecutor` | 静态 `Comparator`，用于定时任务优先级排序 |
| 13 | `WAKEUP_TASK` | `AbstractScheduledEventExecutor` | 静态 `Runnable`，唤醒阻塞的 EventLoop |
| 14 | `DEFAULT_SHUTDOWN_QUIET_PERIOD` / `DEFAULT_SHUTDOWN_TIMEOUT` | `AbstractEventExecutor` | 静态常量 (2s / 15s) |
| 15 | `SELECTOR_AUTO_REBUILD_THRESHOLD` | `NioIoHandler` | 静态，默认 512，JDK bug 的 workaround |
| 16 | `REJECT` | `RejectedExecutionHandlers` | 静态单例 `RejectedExecutionHandler`，直接抛 `RejectedExecutionException` |
| 17 | `INSTANCE` | `DefaultEventExecutorChooserFactory` | 静态单例，Chooser 工厂 |
| 18 | `DEFAULT_MAX_PENDING_TASKS` (int) | `SingleThreadEventLoop` | `max(16, sysprop("io.netty.eventLoop.maxPendingTasks", MAX_VALUE))` |
| 19 | `DEFAULT_MAX_PENDING_EXECUTOR_TASKS` (int) | `SingleThreadEventExecutor` | 同上 |
| 20 | `DEFAULT_MAX_TASK_PROCESSING_QUANTUM_NS` (long) | `SingleThreadIoEventLoop` | `max(100, sysprop(..., 1000))` 转纳秒 |

#### B. MultithreadEventExecutorGroup 字段初始化器（每个实例创建时）

| # | 对象 | 类型 | 说明 |
|---|------|------|------|
| 21 | `terminatedChildren` | `AtomicInteger(0)` | 已终止 EventLoop 计数器 |
| 22 | `terminationFuture` | `DefaultPromise` | 传入 `GlobalEventExecutor.INSTANCE` 作为 executor |
| 23 | `GlobalEventExecutor.INSTANCE` ⚡ | `GlobalEventExecutor` | **首次引用时触发类加载和构造**！内部会创建 `quietPeriodTask`、`BlockingQueue`、`DefaultThreadFactory`、`FailedFuture` 等对象（详见下方 TIPS） |

> ⚡ **TIPS：GlobalEventExecutor.INSTANCE 的隐式初始化链**  
> `DefaultPromise(GlobalEventExecutor.INSTANCE)` 会触发 `GlobalEventExecutor` 类加载，其构造器内部创建：
> - `LinkedBlockingQueue<Runnable> taskQueue`
> - `ScheduledFutureTask<Void> quietPeriodTask`（周期性空任务，用于保持线程存活）
> - `DefaultThreadFactory`（池名 `globalEventExecutor`）→ 被 `ThreadExecutorMap.apply()` 包装
> - `FailedFuture<Object> terminationFuture`（因为 GlobalEventExecutor 不支持终止）
> - `TaskRunner taskRunner`（内部类实例）
> - `AtomicBoolean started`
> 
> 但 GlobalEventExecutor 的线程本身也是懒启动的，构造时不会创建线程。

#### C. MultithreadEventExecutorGroup 构造器中动态创建的对象

| # | 对象 | 类型 | 说明 |
|---|------|------|------|
| 24 | `DefaultThreadFactory` | `DefaultThreadFactory` | 线程名前缀 `multiThreadIoEventLoopGroup-X-`，priority=MAX_PRIORITY |
|    |  └── `prefix` (String) | | 格式：`poolName + '-' + poolId.incrementAndGet() + '-'` |
|    |  └── `AtomicInteger nextId` | | 线程编号计数器 |
| 25 | `ThreadPerTaskExecutor` | `ThreadPerTaskExecutor` | 持有上面的 ThreadFactory，每次 `execute()` 创建新线程 |
| 26 | `children[]` | `EventExecutor[nThreads]` | 数组本身（16 个槽位） |
| 27 | **×16 个** `SingleThreadIoEventLoop` | 详见下方 D | 每个都有独立的 IoHandler、taskQueue 等 |
| 28 | `chooser` | `PowerOfTwoEventExecutorChooser` | （16 是 2 的幂次，所以用 PowerOfTwo 版本） |
|    |  └── `AtomicInteger idx` | | 轮询计数器，`next()` 时 `idx++ & (len-1)` |
| 29 | `terminationListener` | `FutureListener<Object>` (匿名内部类) | 挂到每个 child 的 `terminationFuture` 上 |
| 30 | `childrenSet` | `LinkedHashSet<EventExecutor>` | children 的集合视图 |
| 31 | `readonlyChildren` | `UnmodifiableSet` | `Collections.unmodifiableSet(childrenSet)` |

#### D. 每个 SingleThreadIoEventLoop（×16）内部创建的对象

以下对象**每个 EventLoop 都有一份**，16 个 EventLoop 共 16 份：

**D.1 AbstractEventExecutor 层（最顶层）**

| # | 对象 | 类型 | 说明 |
|---|------|------|------|
| 32 | `parent` | 引用 | 指向 `MultiThreadIoEventLoopGroup` |
| 33 | `selfCollection` | `Collections.singleton(this)` | 用于 `iterator()` 返回只包含自己的集合 |

**D.2 AbstractScheduledEventExecutor 层**

| # | 对象 | 类型 | 说明 |
|---|------|------|------|
| 34 | `scheduledTaskQueue` | `null` | **懒初始化！** 构造时为 null，首次 schedule 时才创建 `DefaultPriorityQueue` |
| 35 | `nextTaskId` | `long = 0` | 定时任务 ID 计数器 |

**D.3 SingleThreadEventExecutor 层 — 字段最多的一层！**

| # | 对象 | 类型 | 说明 |
|---|------|------|------|
| 36 | `executor` | 匿名 `Executor` | `ThreadExecutorMap.apply(threadPerTaskExecutor, this)` 包装的 Executor ⚠️ |
| 37 | `taskQueue` | `MpscQueue` 🔥 | 通过 `newTaskQueue()` 创建，MPSC = 多生产者单消费者无锁队列 |
| 38 | `processingLock` | `ReentrantLock` | 保护线程处理过程 |
| 39 | `threadLock` | `CountDownLatch(1)` | 线程终止时 countDown，`awaitTermination()` 用 |
| 40 | `shutdownHooks` | `LinkedHashSet<Runnable>` | 关闭钩子集合 |
| 41 | `rejectedExecutionHandler` | 引用 | 指向 `RejectedExecutionHandlers.REJECT` 单例 |
| 42 | `terminationFuture` | `DefaultPromise<Void>` | 传入 `GlobalEventExecutor.INSTANCE` |
| 43 | `addTaskWakesUp` | `boolean = false` | NIO 不需要通过添加任务唤醒 |
| 44 | `supportSuspension` | `boolean = true` | NIO 支持 suspension（4.2 新特性） |
| 45 | `maxPendingTasks` | `int` | `max(16, Integer.MAX_VALUE)` → 实际就是 MAX_VALUE |
| 46 | `state` | `volatile int = ST_NOT_STARTED(1)` | 状态机初始状态 |
| 47 | `thread` | `volatile Thread = null` | **懒启动！** 构造时为 null |

> ⚠️ **#36 的关键细节**：`ThreadExecutorMap.apply(executor, this)` 会创建一个**匿名 Executor 包装器**。它在每次 `execute(command)` 时，把 command 再包一层，设置当前线程的 `EventExecutor` 映射到 FastThreadLocal 中。这就是 `EventExecutor.currentExecutor()` 能工作的原因！

> 🔥 **#37 面试常考**：Netty 的 taskQueue 使用 `MpscQueue`（jctools 的 Multi-Producer Single-Consumer 队列），而不是 JDK 的 `LinkedBlockingQueue`。这是因为 EventLoop 的任务队列只有一个消费者（EventLoop 线程自己），但有多个生产者（外部业务线程提交任务）。MpscQueue 在这种场景下比 `LinkedBlockingQueue` 快很多（无锁 CAS）。

**D.4 SingleThreadEventLoop 层**

| # | 对象 | 类型 | 说明 |
|---|------|------|------|
| 48 | `tailTasks` | `MpscQueue` | 尾部任务队列，每个 EventLoop 迭代结束后执行 |

**D.5 SingleThreadIoEventLoop 层**

| # | 对象 | 类型 | 说明 |
|---|------|------|------|
| 49 | `context` | `IoHandlerContext`（匿名内部类）🔥 | 内联实现了 `canBlock()`、`delayNanos()`、`deadlineNanos()`、`reportActiveIoTime()`、`shouldReportActiveIoTime()` |
| 50 | `ioHandler` | `NioIoHandler` | 通过 `ioHandlerFactory.newHandler(this)` 创建（详见 D.6） |
| 51 | `numRegistrations` | `AtomicInteger(0)` | 当前注册到此 EventLoop 的 Channel 数量 |
| 52 | `maxTaskProcessingQuantumNs` | `long` | 默认 1000ms 转纳秒 |

**D.6 NioIoHandler（每个 EventLoop 一个）**

| # | 对象 | 类型 | 说明 |
|---|------|------|------|
| 53 | `provider` | `SelectorProvider` | 引用（共享同一个 JDK SelectorProvider） |
| 54 | `selectStrategy` | `DefaultSelectStrategy` | 引用（共享同一个 `DefaultSelectStrategy.INSTANCE`） |
| 55 | `wakenUp` | `AtomicBoolean(false)` | 唤醒标志 |
| 56 | `selectNowSupplier` | `IntSupplier`（匿名内部类） | 闭包引用 NioIoHandler.this，用于 `selectStrategy.calculateStrategy()` |
| 57 | `unwrappedSelector` | `Selector` | JDK 原始 Selector（`provider.openSelector()`） |
| 58 | `selectedKeys` | `SelectedSelectionKeySet` 🔥 | **Netty 优化！** 替换了 JDK Selector 内部的 HashSet |
|    |  └── `keys[]` | `SelectionKey[1024]` | 初始 1024 容量的数组（比 HashSet 快！） |
| 59 | `selector` | `SelectedSelectionKeySetSelector` | 包装 unwrappedSelector + selectedKeys 的代理 Selector |

> 🔥 **#58 面试常考**：Netty 通过反射（或 Unsafe）将 JDK Selector 内部的 `selectedKeys`（HashSet 实现）替换为自己的 `SelectedSelectionKeySet`（数组实现）。这个优化在高连接数场景下能显著减少 GC 压力，因为：
> - HashSet 的 `iterator()` 每次都创建新对象 → GC 压力
> - 数组遍历是顺序访问 → CPU cache 友好
> - 数组不需要 `remove()` → 直接 null 置空

### ② new EchoServerHandler() 创建的对象

| # | 对象 | 类型 | 说明 |
|---|------|------|------|
| 60 | `EchoServerHandler` 实例 | `EchoServerHandler` | `@Sharable`，无字段，非常轻量 |

### ③ new ServerBootstrap() 创建的对象

| # | 对象 | 类型 | 说明 |
|---|------|------|------|
| 61 | `ServerBootstrap` 实例 | `ServerBootstrap` | 空壳，下面是它的字段初始化器创建的对象 |
| 62 | `childOptions` | `LinkedHashMap` | Worker Channel 选项容器（空） |
| 63 | `childAttrs` | `ConcurrentHashMap` | Worker Channel 属性容器（空） |
| 64 | `config` | `ServerBootstrapConfig` | `new ServerBootstrapConfig(this)`，持有 bootstrap 的引用 |
| 65 | `options` | `LinkedHashMap` | （继承自 AbstractBootstrap）Boss Channel 选项容器（空） |
| 66 | `attrs` | `ConcurrentHashMap` | （继承自 AbstractBootstrap）Boss Channel 属性容器（空） |

### ④ 配置链中创建的对象

| # | 对象 | 创建来源 | 说明 |
|---|------|---------|------|
| 67 | `ReflectiveChannelFactory` | `.channel(NioServerSocketChannel.class)` | 持有 `Constructor<NioServerSocketChannel>` |
| 68 | `Constructor<NioServerSocketChannel>` | `clazz.getConstructor()` | 反射获取的无参构造器对象 |
| 69 | `LoggingHandler` | `.handler(new LoggingHandler(LogLevel.INFO))` | Boss Pipeline 的日志 Handler |
|    |  └── `InternalLogger logger` | | `InternalLoggerFactory.getInstance(LoggingHandler.class)` |
|    |  └── `InternalLogLevel internalLevel` | | `LogLevel.INFO.toInternalLevel()` → `InternalLogLevel.INFO` |
|    |  └── `ByteBufFormat byteBufFormat` | | 默认 `ByteBufFormat.HEX_DUMP` |
| 70 | `ChannelInitializer`（匿名子类） | `.childHandler(new ChannelInitializer<>(){...})` | Worker Pipeline 的初始化器 |

### ⑤ 完整对象计数汇总

| 分类 | 独立实例数 | ×16 的实例数 | 合计 |
|------|-----------|-------------|------|
| 静态/单例对象（只创建一次） | ~20 | — | ~20 |
| EventLoopGroup 级别 | ~12 | — | ~12 |
| 每个 EventLoop 内部 | — | ~21 | ~336 |
| ServerBootstrap + 配置 | ~10 | — | ~10 |
| **总计** | | | **~378 个对象** |

> 💡 **核心结论**：`bind()` 之前，光是构造 `MultiThreadIoEventLoopGroup` + `ServerBootstrap` 就创建了约 **378 个对象**（8 核机器，16 个 EventLoop）。其中最重量级的是 16 个 `Selector`（每个 Selector 底层都是一个 epoll fd / kqueue fd）和 32 个 `MpscQueue`（16 个 taskQueue + 16 个 tailTasks）。

---

## 八、核心不变式总结（Invariants）

| # | 不变式 | 意义 |
|---|--------|------|
| 1 | **每个 EventLoop 拥有独立的 Selector** | 无 Selector 锁竞争 |
| 2 | **EventLoop 线程懒启动** | 构造时不创建线程，第一个任务提交时才启动 |
| 3 | **ServerBootstrap 配置方法只是存值** | 不做任何 IO 操作，bind() 才"开始干活" |
| 4 | **Boss 和 Worker 可以共享同一个 Group** | EchoServer 就是这种用法 |
| 5 | **Channel 通过工厂延迟创建** | 配置时只缓存 Constructor，bind() 时才反射实例化 |

---

## 九、为什么不用 XXX 方案？（深化理解）

### Q1: 为什么 EventLoop 的线程是懒启动的？
**如果构造时就创建线程**，16 个 EventLoop 就立刻启动 16 个线程在那空转（polling Selector），白白浪费 CPU。懒启动可以让线程在真正有任务（比如 Channel 注册）时才启动。

### Q2: 为什么用 ReflectiveChannelFactory 反射创建 Channel？不能直接 new 吗？
因为 `AbstractBootstrap` 不知道具体是哪种 Channel（可能是 NioServerSocketChannel、EpollServerSocketChannel 等）。通过工厂模式 + 反射，实现了对具体 Channel 类型的解耦。而且 Channel 不能在配置时创建，必须在 `bind()` 时才创建（因为创建时就要初始化底层资源）。

### Q3: 为什么 options 用 LinkedHashMap 而不是 HashMap？
因为 **ChannelOption 的设置顺序可能影响结果**（源码注释也说了）。比如某些选项之间有依赖关系，必须按特定顺序设置。LinkedHashMap 保证了插入顺序。

### Q4: 为什么 EchoServer 用一个 Group 而不是分 Boss/Worker？
对于大多数场景，一个 Group 就够了。因为 accept 操作非常轻量，不需要独占线程。分离 Boss/Worker 主要用于需要精确控制资源分配的高级场景（比如 Boss 只需要 1 个线程就够了）。

---

## 十、下一步阅读路径

现在我们已经完全理解了 `bind()` 之前的所有对象，下一篇正式进入 **bind() 的执行流程**：

1. `bind(PORT)` → `doBind()` → `initAndRegister()` — Channel 是怎么被创建和初始化的？
2. `init(channel)` — ServerBootstrapAcceptor 是什么？它怎么和 Boss/Worker 关联？
3. `group.register(channel)` — Channel 怎么注册到 EventLoop 的 Selector 上？
4. `doBind0()` — 端口绑定的完整调用链

> 🎯 **带着问题读源码**（Rule #2）：下一篇要回答——**从 `bind(PORT)` 调用到端口真正可用，到底经历了哪些步骤？ServerBootstrapAcceptor 这个隐藏的 Handler 到底做了什么？**
