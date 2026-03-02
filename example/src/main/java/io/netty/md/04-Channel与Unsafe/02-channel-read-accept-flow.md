# 04-02 Channel Read/Accept 流程深度分析

> **前置问题**：EventLoop 的 `processSelectedKey()` 检测到 IO 事件后，会调用 `unsafe.read()`。但 `NioServerSocketChannel` 的 read 和 `NioSocketChannel` 的 read 完全不同——前者是 accept 新连接，后者是读取字节数据。它们各自的完整流程是什么？新连接 accept 后如何注册到 Worker EventLoop？字节数据读取时 ByteBuf 如何分配？
>
> **本文目标**：深度分析两条 read 路径的完整源码，搞清楚 accept 流程、新连接注册流程、字节读取循环、AdaptiveRecvByteBufAllocator 自适应机制。
> 遵循 Skill #15（问题驱动推导数据结构）、Skill #10（全量分析）、Skill #13（父类构造链必查）、Skill #14（Mermaid 绘图）、Skill #16（日志验证结论）。

---

## 一、解决什么问题？

### 1.1 两种 read 的本质区别

在 Netty 中，"read" 这个词在不同 Channel 上有完全不同的含义：

| Channel 类型 | read 的含义 | 读到的"数据" | 触发条件 |
|-------------|------------|------------|---------|
| `NioServerSocketChannel` | **accept 新连接** | `NioSocketChannel`（新连接对象） | `OP_ACCEPT` 事件 |
| `NioSocketChannel` | **读取字节数据** | `ByteBuf`（字节缓冲区） | `OP_READ` 事件 |

这两种 read 分别由不同的 Unsafe 实现：
- `NioMessageUnsafe.read()` → 循环 accept，每次产生一个 `NioSocketChannel`
- `NioByteUnsafe.read()` → 循环读取字节，每次产生一个 `ByteBuf`

### 1.2 核心问题清单（带着问题读源码）

| # | 问题 | 面试热度 |
|---|------|---------|
| 1 | `NioMessageUnsafe.read()` 的完整循环逻辑是什么？最多 accept 几个连接？ | 🔥🔥🔥 |
| 2 | accept 到的 `NioSocketChannel` 如何注册到 Worker EventLoop？`ServerBootstrapAcceptor` 做了什么？ | 🔥🔥🔥 |
| 3 | `NioByteUnsafe.read()` 的完整循环逻辑是什么？什么时候停止读取？ | 🔥🔥🔥 |
| 4 | `AdaptiveRecvByteBufAllocator` 如何自适应调整 ByteBuf 大小？扩容/缩容策略是什么？ | 🔥🔥 |
| 5 | `channelRead` 和 `channelReadComplete` 的触发时序是怎样的？ | 🔥🔥 |
| 6 | `readPending` 标志的作用是什么？autoRead 关闭时如何停止读取？ | 🔥 |

---

## 二、入口：EventLoop 如何触发 read？

### 2.1 从 processSelectedKey 到 unsafe.read()

在 `03-EventLoop` 模块中，我们已经分析了 `NioIoHandler.processSelectedKey()` 的逻辑。这里快速回顾触发 read 的入口：

```java
// NioIoHandler.processSelectedKey() 简化版
private static void processSelectedKey(DefaultNioRegistration registration, NioIoOps ops) {
    NioIoHandle handle = registration.handle();
    // ...
    if (ops.contains(NioIoOps.ACCEPT) || ops.contains(NioIoOps.READ)) {
        handle.handle(registration, ops);  // ← 触发 AbstractNioUnsafe.handle()
    }
}
```

`AbstractNioUnsafe.handle()` 根据事件类型分发：

```java
// AbstractNioUnsafe.handle() 简化版
@Override
public final void handle(IoRegistration registration, IoEvent ioEvent) {
    NioIoOps ops = (NioIoOps) ioEvent;
    if (ops.contains(NioIoOps.CONNECT)) {
        finishConnect();
    }
    if (ops.contains(NioIoOps.WRITE)) {
        forceFlush();
    }
    if (ops.contains(NioIoOps.READ) || ops.contains(NioIoOps.ACCEPT)) {
        read();  // ← 最终调用 NioMessageUnsafe.read() 或 NioByteUnsafe.read()
    }
}
```

```mermaid
flowchart TD
    A["NioIoHandler.processSelectedKey()"] --> B{"事件类型?"}
    B -->|"OP_ACCEPT / OP_READ"| C["AbstractNioUnsafe.handle()"]
    B -->|"OP_WRITE"| D["forceFlush()"]
    B -->|"OP_CONNECT"| E["finishConnect()"]
    C --> F{"Channel 类型?"}
    F -->|"NioServerSocketChannel"| G["NioMessageUnsafe.read()\n= accept 新连接"]
    F -->|"NioSocketChannel"| H["NioByteUnsafe.read()\n= 读取字节数据"]

    style G fill:#ffcdd2
    style H fill:#c8e6c9
```

---

## 三、路径一：NioMessageUnsafe.read() — accept 新连接

### 3.1 问题推导（Skill #15）

**问题**：ServerSocketChannel 每次 `OP_ACCEPT` 事件到来，可能有多个客户端同时连接。如何高效地批量 accept？

**推导**：
- 需要一个**循环**，每次调用 JDK `accept()` 获取一个新连接
- 需要**限制循环次数**，防止一直 accept 导致其他 Channel 的 IO 事件饥饿
- accept 到的连接需要**暂存**，等循环结束后统一触发 `fireChannelRead`
- 需要**错误处理**：accept 失败时是否关闭 ServerChannel？

**推导出的结构**：
```
NioMessageUnsafe {
    List<Object> readBuf;          // 暂存 accept 到的 NioSocketChannel
    // 循环控制：allocHandle.continueReading() → totalMessages < maxMessagesPerRead(16)
}
```

### 3.2 NioMessageUnsafe.read() 完整源码分析

**源码位置**：`transport/src/main/java/io/netty/channel/nio/AbstractNioMessageChannel.java`

```java
private final class NioMessageUnsafe extends AbstractNioUnsafe {

    private final List<Object> readBuf = new ArrayList<Object>();  // [字段1] 暂存 accept 到的 Channel

    @Override
    public void read() {
        assert eventLoop().inEventLoop();                          // [1] 断言：必须在 EventLoop 线程中执行
        final ChannelConfig config = config();
        final ChannelPipeline pipeline = pipeline();
        final RecvByteBufAllocator.Handle allocHandle = unsafe().recvBufAllocHandle();  // [2] 获取 allocHandle
        allocHandle.reset(config);                                 // [3] 重置计数器（totalMessages=0）

        boolean closed = false;
        Throwable exception = null;
        try {
            try {
                do {
                    int localRead = doReadMessages(readBuf);       // [4] 🔥 调用 JDK accept()
                    if (localRead == 0) {                          // [5] 没有新连接，退出循环
                        break;
                    }
                    if (localRead < 0) {                           // [6] 返回负数 = Channel 关闭
                        closed = true;
                        break;
                    }
                    allocHandle.incMessagesRead(localRead);        // [7] 计数 +1（totalMessages++）
                } while (continueReading(allocHandle));            // [8] 🔥 是否继续循环？

            } catch (Throwable t) {
                exception = t;
            }

            int size = readBuf.size();
            for (int i = 0; i < size; i++) {                      // [9] 🔥 循环结束后统一 fireChannelRead
                readPending = false;
                pipeline.fireChannelRead(readBuf.get(i));         // msg = NioSocketChannel
            }
            readBuf.clear();                                       // [10] 清空暂存列表
            allocHandle.readComplete();                            // [11] 通知 allocHandle 本轮读取完成
            pipeline.fireChannelReadComplete();                    // [12] 触发 channelReadComplete

            if (exception != null) {
                closed = closeOnReadError(exception);              // [13] 判断是否需要关闭 Channel
                pipeline.fireExceptionCaught(exception);          // [14] 传播异常
            }

            if (closed) {
                inputShutdown = true;
                if (isOpen()) {
                    close(voidPromise());                          // [15] 关闭 ServerChannel
                }
            }
        } finally {
            // [16] 🔥 关键：如果没有 readPending 且 autoRead=false，移除 OP_ACCEPT 注册
            if (!readPending && !config.isAutoRead()) {
                removeReadOp();
            }
        }
    }
}
```

**逐行解析**：

| 标号 | 代码 | 深度分析 |
|------|------|---------|
| [1] | `assert eventLoop().inEventLoop()` | 所有 IO 操作必须在 EventLoop 线程中执行，这是 Netty 线程模型的核心约束 |
| [2] | `recvBufAllocHandle()` | 懒初始化：首次调用时创建 `AdaptiveRecvByteBufAllocator.HandleImpl`，后续复用 |
| [3] | `allocHandle.reset(config)` | 重置 `totalMessages=0`，读取 `maxMessagesPerRead`（默认 16） |
| [4] | `doReadMessages(readBuf)` | 🔥 调用 `NioServerSocketChannel.doReadMessages()`，内部执行 JDK `accept()`，成功则创建 `NioSocketChannel` 加入 readBuf，返回 1 |
| [5] | `localRead == 0` | `accept()` 返回 null（没有新连接），退出循环 |
| [6] | `localRead < 0` | Channel 已关闭（理论上 ServerSocketChannel 不会返回 -1，这是防御性代码） |
| [7] | `incMessagesRead(1)` | `totalMessages++`，用于 `continueReading()` 判断 |
| [8] | `continueReading(allocHandle)` | 🔥 **循环控制核心**：`autoRead && (!respectMaybeMoreData \|\| maybeMoreData) && totalMessages < 16 && (ignoreBytesRead \|\| totalBytesRead > 0)`。对 ServerChannel：① `ignoreBytesRead=true`，最后一个条件始终满足；② `NioMessageUnsafe` 从不调用 `lastBytesRead()` 和 `attemptedBytesRead()`，两者始终为 0，`defaultMaybeMoreSupplier`（`0==0`）始终返回 true，所以 `respectMaybeMoreData` 条件也始终满足；最终只有 `totalMessages < 16` 和 `autoRead` 两个条件真正起作用 |
| [9] | `fireChannelRead(readBuf.get(i))` | 🔥 **注意**：不是每 accept 一个就 fire，而是**批量 accept 完成后统一 fire**！msg 是 `NioSocketChannel` |
| [10] | `readBuf.clear()` | 清空列表，但不释放 NioSocketChannel（它们已经被 Pipeline 处理了） |
| [11] | `allocHandle.readComplete()` | 通知 allocHandle 本轮读取完成（对 ServerChannel 意义不大，主要用于 ByteChannel） |
| [12] | `fireChannelReadComplete()` | 通知 Pipeline 本轮读取完成，用户可以在此做批量处理 |
| [16] | `removeReadOp()` | 如果 `autoRead=false` 且没有 pending 的 read 请求，从 Selector 移除 OP_ACCEPT，停止接受新连接 |

### 3.3 continueReading() 的判断逻辑

```java
// DefaultMaxMessagesRecvByteBufAllocator.MaxMessageHandle
@Override
public boolean continueReading(UncheckedBooleanSupplier maybeMoreDataSupplier) {
    return config.isAutoRead() &&
           (!respectMaybeMoreData || maybeMoreDataSupplier.get()) &&
           totalMessages < maxMessagePerRead &&
           (ignoreBytesRead || totalBytesRead > 0);
}
```

对于 `NioMessageUnsafe`（ServerChannel），`continueReading` 的实际判断：

```java
// AbstractNioMessageChannel
protected boolean continueReading(RecvByteBufAllocator.Handle allocHandle) {
    return allocHandle.continueReading();
    // = autoRead && totalMessages < 16 && totalBytesRead > 0
    // 注意：totalBytesRead 对 Message 类型始终为 0，所以 ignoreBytesRead=true
}
```

> 🔥 **面试高频**：一次 `OP_ACCEPT` 事件最多 accept 几个连接？
> **答**：最多 **16 个**（`maxMessagesPerRead` 默认值，由 `ChannelMetadata(false, 16)` 决定）。但实际上只要 `accept()` 返回 null（没有新连接），循环就会提前退出。16 是上限，不是固定值。

### 3.4 doReadMessages() — JDK accept() 的封装

```java
// NioServerSocketChannel.doReadMessages()
@Override
protected int doReadMessages(List<Object> buf) throws Exception {
    SocketChannel ch = SocketUtils.accept(javaChannel());  // [1] JDK accept()

    try {
        if (ch != null) {
            buf.add(new NioSocketChannel(this, ch));        // [2] 🔥 创建 NioSocketChannel！
            return 1;
        }
    } catch (Throwable t) {
        logger.warn("Failed to create a new channel from an accepted socket.", t);
        try {
            ch.close();                                     // [3] 创建失败则关闭 JDK SocketChannel
        } catch (Throwable t2) {
            logger.warn("Failed to close a socket.", t2);
        }
    }
    return 0;
}
```

**关键点**：
- `SocketUtils.accept(javaChannel())` 是对 `javaChannel().accept()` 的安全封装（处理 `SecurityManager`）
- 返回 `null` 表示没有新连接（非阻塞模式下正常情况）
- 创建 `new NioSocketChannel(this, ch)` 时，`this` 是 ServerChannel，作为 parent 传入

> ⚠️ **生产踩坑**：如果 `new NioSocketChannel(this, ch)` 抛出异常（如 JDK SocketChannel 配置失败），Netty 会 warn 日志并关闭 JDK SocketChannel，但**不会关闭 ServerChannel**。这是正确的行为——一个连接创建失败不应该影响整个服务器。

---

## 四、新连接的创建与注册（Skill #13 父类构造链必查）

### 4.1 NioSocketChannel 的创建

`doReadMessages()` 中执行 `new NioSocketChannel(this, ch)`，触发完整的构造链（已在 `01-channel-hierarchy-and-data-structures.md` 中详细分析，这里聚焦关键点）：

```
new NioSocketChannel(serverChannel, jdkSocketChannel)
  → AbstractNioByteChannel(serverChannel, jdkSocketChannel, OP_READ)
    → AbstractNioChannel(serverChannel, jdkSocketChannel, OP_READ)
      → AbstractChannel(serverChannel)
        ├── parent = serverChannel          ← 记录父 Channel
        ├── id = DefaultChannelId.newInstance()
        ├── unsafe = new NioSocketChannelUnsafe()
        └── pipeline = new DefaultChannelPipeline(this)
            ├── head = new HeadContext(pipeline)
            └── tail = new TailContext(pipeline)
      ├── ch = jdkSocketChannel
      ├── readInterestOp = OP_READ (1)
      └── ch.configureBlocking(false)      ← 设置非阻塞
  → config = new NioSocketChannelConfig(this, socket.socket())
```

**此时 NioSocketChannel 的状态**：
- `isOpen() = true`（JDK SocketChannel 已连接）
- `isRegistered() = false`（还未注册到 EventLoop）
- `isActive() = true`（`isOpen() && ch.isConnected()` = true，因为 accept 出来的连接已经建立）

> 🔥 **关键**：NioSocketChannel 在创建时 `isActive()` 就是 `true`！这与 ServerSocketChannel 不同（ServerChannel 在 bind 之前 `isActive()=false`）。这个差异决定了 `channelActive` 的触发时机。

### 4.2 ServerBootstrapAcceptor — 新连接的分发器

`fireChannelRead(nioSocketChannel)` 触发后，事件沿 Pipeline 传播，最终到达 `ServerBootstrapAcceptor.channelRead()`：

```java
// ServerBootstrap.ServerBootstrapAcceptor
private static class ServerBootstrapAcceptor extends ChannelInboundHandlerAdapter {

    private final EventLoopGroup childGroup;      // Worker EventLoopGroup
    private final ChannelHandler childHandler;    // 用户配置的 childHandler（如 EchoServerHandler）
    private final Entry<ChannelOption<?>, Object>[] childOptions;
    private final Entry<AttributeKey<?>, Object>[] childAttrs;
    private final Runnable enableAutoReadTask;    // 用于恢复 autoRead 的任务（在构造函数中提前创建，防止 too many open files 时 ClassLoader 无法加载）
    private final Collection<ChannelInitializerExtension> extensions;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        final Channel child = (Channel) msg;                    // [1] msg 就是 NioSocketChannel

        child.pipeline().addLast(childHandler);                 // [2] 🔥 添加用户的 childHandler

        try {
            setChannelOptions(child, childOptions, logger);     // [3] 设置 SO_KEEPALIVE 等选项
        } catch (Throwable cause) {
            forceClose(child, cause);                           // [3a] ⚠️ 设置选项失败则强制关闭并 return！
            return;
        }
        setAttributes(child, childAttrs);                       // [4] 设置自定义属性

        // [5] SPI 扩展点
        if (!extensions.isEmpty()) {
            for (ChannelInitializerExtension extension : extensions) {
                try {
                    extension.postInitializeServerChildChannel(child);
                } catch (Exception e) {
                    logger.warn("Exception thrown from postInitializeServerChildChannel", e);
                }
            }
        }

        try {
            childGroup.register(child)                          // [6] 🔥🔥🔥 注册到 Worker EventLoop！
                .addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        if (!future.isSuccess()) {
                            forceClose(child, future.cause()); // [7] 注册失败则强制关闭（closeForcibly + warn日志）
                        }
                    }
                });
        } catch (Throwable t) {
            forceClose(child, t);                               // [8] register 本身抛异常也强制关闭
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        final ChannelConfig config = ctx.channel().config();
        if (config.isAutoRead()) {
            config.setAutoRead(false);                          // [8] 暂停 accept 1 秒
            ctx.channel().eventLoop().schedule(enableAutoReadTask, 1, TimeUnit.SECONDS);
        }
        ctx.fireExceptionCaught(cause);
    }
}
```

**逐行解析**：

| 标号 | 代码 | 深度分析 |
|------|------|---------|
| [1] | `(Channel) msg` | msg 就是 `NioSocketChannel`，这是 `doReadMessages()` 创建并通过 `fireChannelRead` 传递过来的 |
| [2] | `child.pipeline().addLast(childHandler)` | 🔥 **在 Boss 线程中**给子 Channel 的 Pipeline 添加用户 Handler（如 `ChannelInitializer`）。此时子 Channel 还未注册，addLast 会将 handlerAdded 回调延迟到注册后执行 |
| [3] | `setChannelOptions` | 设置 TCP 选项（如 `SO_KEEPALIVE=true`）。⚠️ **注意**：有 try-catch，若设置失败会 `forceClose(child, cause)` 并 `return`，不会继续执行后续步骤 |
| [6] | `childGroup.register(child)` | 🔥🔥🔥 **核心！** 从 Worker EventLoopGroup 中选一个 EventLoop，将子 Channel 注册上去。这是 Reactor 模式中 Main Reactor → Sub Reactor 的分发点 |
| [7] | `forceClose(child, cause)` | 注册失败时强制关闭子 Channel，防止 FD 泄漏 |
| [8] | `setAutoRead(false)` + `schedule` | 🔥 **防止 too many open files 忙循环**：当 accept 出现异常时，暂停 1 秒接受新连接，让系统有时间恢复 |

> 🔥 **面试高频**：`ServerBootstrapAcceptor` 在哪个线程执行？
> **答**：在 **Boss EventLoop 线程**中执行。`channelRead` 是 Pipeline 的入站事件，由触发 `fireChannelRead` 的线程（即 Boss EventLoop）执行。

> ⚠️ **生产踩坑**：`exceptionCaught` 中的 "暂停 1 秒" 策略是为了防止 `too many open files` 异常导致的忙循环（[netty/netty#1328](https://github.com/netty/netty/issues/1328)）。如果不暂停，`accept()` 会持续失败，EventLoop 会一直处理这个错误，CPU 飙升到 100%。

### 4.3 childGroup.register(child) — 注册到 Worker EventLoop

```java
// 调用链
childGroup.register(child)
  └── MultithreadEventLoopGroup.register(child)
        └── next().register(child)                    // Chooser 选出一个 Worker EventLoop
              └── SingleThreadEventLoop.register(child)
                    └── child.unsafe().register(this, promise)
                          └── AbstractUnsafe.register(eventLoop, promise)
                                └── eventLoop.execute(register0)  // 提交到 Worker EventLoop 执行
```

`register0()` 在 **Worker EventLoop 线程**中执行：

```java
private void register0(ChannelPromise promise) {
    boolean firstRegistration = neverRegistered;
    // ...
    registerPromise.addListener(future -> {
        if (future.isSuccess()) {
            neverRegistered = false;
            registered = true;

            pipeline.invokeHandlerAddedIfNeeded();    // [1] 触发 childHandler.handlerAdded()
            safeSetSuccess(promise);
            pipeline.fireChannelRegistered();          // [2] 触发 channelRegistered

            if (isActive()) {                          // [3] 🔥 NioSocketChannel 此时 isActive()=true！
                if (firstRegistration) {
                    pipeline.fireChannelActive();      // [4] 🔥 触发 channelActive！
                } else if (config().isAutoRead()) {
                    beginRead();                       // [4b] 重新注册时（deregister后再register），autoRead=true 则重新注册 OP_READ
                }
            }
        }
    });
    doRegister(registerPromise);                       // [5] 注册到 Worker EventLoop 的 Selector
}
```

**关键差异**：
- **ServerSocketChannel** 注册时：`isActive()=false`（还没 bind），不触发 `channelActive`
- **NioSocketChannel** 注册时：`isActive()=true`（已连接），**立即触发 `channelActive`**！
- **重新注册时**（Channel deregister 后再 register）：`firstRegistration=false`，不触发 `channelActive`，但若 `autoRead=true` 会调用 `beginRead()` 重新注册 `OP_READ`，确保数据继续被读取

`channelActive` 触发后，`HeadContext.channelActive()` 会调用 `beginRead()`，注册 `OP_READ` 到 Selector，从此开始监听数据到来。

---

## 五、accept 完整时序图

```mermaid
sequenceDiagram
    participant EL_Boss as Boss EventLoop
    participant NioMsgUnsafe as NioMessageUnsafe
    participant doReadMsg as doReadMessages()
    participant Pipeline_Server as Server Pipeline
    participant Acceptor as ServerBootstrapAcceptor
    participant EL_Worker as Worker EventLoop
    participant Pipeline_Child as Child Pipeline

    EL_Boss->>NioMsgUnsafe: handle(OP_ACCEPT) → read()
    activate NioMsgUnsafe

    loop 最多16次（maxMessagesPerRead）
        NioMsgUnsafe->>doReadMsg: doReadMessages(readBuf)
        doReadMsg->>doReadMsg: JDK accept() → SocketChannel
        doReadMsg->>doReadMsg: new NioSocketChannel(this, ch)
        doReadMsg-->>NioMsgUnsafe: return 1（加入readBuf）
        Note over NioMsgUnsafe: accept()返回null时break
    end

    loop 遍历 readBuf
        NioMsgUnsafe->>Pipeline_Server: fireChannelRead(NioSocketChannel)
        Pipeline_Server->>Acceptor: channelRead(ctx, NioSocketChannel)
        Acceptor->>Acceptor: child.pipeline().addLast(childHandler)
        Acceptor->>Acceptor: setChannelOptions / setAttributes
        Acceptor->>EL_Worker: childGroup.register(child)
    end

    NioMsgUnsafe->>Pipeline_Server: fireChannelReadComplete()
    deactivate NioMsgUnsafe

    Note over EL_Worker: Worker EventLoop 线程执行 register0()
    EL_Worker->>EL_Worker: doRegister() → 注册到 Selector(interestOps=0)
    EL_Worker->>Pipeline_Child: invokeHandlerAddedIfNeeded()
    Pipeline_Child->>Pipeline_Child: childHandler.handlerAdded()
    EL_Worker->>Pipeline_Child: fireChannelRegistered()
    EL_Worker->>Pipeline_Child: fireChannelActive()
    Pipeline_Child->>Pipeline_Child: HeadContext.channelActive()
    Pipeline_Child->>EL_Worker: beginRead() → 注册 OP_READ
    Note over EL_Worker: 从此开始监听客户端数据
```

---

## 六、路径二：NioByteUnsafe.read() — 读取字节数据

### 6.1 问题推导（Skill #15）

**问题**：SocketChannel 收到数据时，如何高效地读取？ByteBuf 应该分配多大？

**推导**：
- 需要一个**循环**，每次分配 ByteBuf 并读取数据
- ByteBuf 大小如何确定？太小需要多次读取（多次系统调用），太大浪费内存
- 需要**自适应**：根据历史读取量动态调整 ByteBuf 大小
- 需要**限制循环次数**，防止一个 Channel 的数据太多导致其他 Channel 饥饿
- 读到 EOF（返回 -1）时需要关闭连接

**推导出的结构**：
```
NioByteUnsafe {
    // 无额外字段，使用父类的 recvHandle（AdaptiveRecvByteBufAllocator.HandleImpl）
    // 循环控制：allocHandle.continueReading()
    //   = autoRead && totalMessages < 16 && lastBytesRead == attemptedBytesRead（读满了）
}
```

### 6.2 NioByteUnsafe.read() 完整源码分析

**源码位置**：`transport/src/main/java/io/netty/channel/nio/AbstractNioByteChannel.java`

```java
// AbstractNioByteChannel.NioByteUnsafe
@Override
public final void read() {
    final ChannelConfig config = config();
    if (shouldBreakReadReady(config)) {                        // [1] 检查是否应该中断读取
        clearReadPending();
        return;
    }
    final ChannelPipeline pipeline = pipeline();
    final ByteBufAllocator allocator = config.getAllocator();  // [2] 获取 ByteBuf 分配器
    final RecvByteBufAllocator.Handle allocHandle = recvBufAllocHandle();  // [3] 获取 allocHandle
    allocHandle.reset(config);                                 // [4] 重置计数器

    ByteBuf byteBuf = null;
    boolean close = false;
    try {
        do {
            byteBuf = allocHandle.allocate(allocator);         // [5] 🔥 分配 ByteBuf（大小由 allocHandle 预测）
            allocHandle.lastBytesRead(doReadBytes(byteBuf));   // [6] 🔥 读取数据到 ByteBuf
            if (allocHandle.lastBytesRead() <= 0) {            // [7] 没读到数据
                byteBuf.release();                             // [8] 释放空 ByteBuf
                byteBuf = null;
                close = allocHandle.lastBytesRead() < 0;      // [9] -1 = EOF，需要关闭连接
                if (close) {
                    readPending = false;
                }
                break;
            }

            allocHandle.incMessagesRead(1);                    // [10] totalMessages++
            readPending = false;
            pipeline.fireChannelRead(byteBuf);                 // [11] 🔥 立即 fire！（不像 Message 那样批量）
            byteBuf = null;                                    // [12] 置 null，防止 finally 中重复释放
        } while (allocHandle.continueReading());               // [13] 🔥 是否继续读取？

        allocHandle.readComplete();                            // [14] 通知 allocHandle 本轮读取完成
        pipeline.fireChannelReadComplete();                    // [15] 触发 channelReadComplete

        if (close) {
            closeOnRead(pipeline);                             // [16] EOF 处理：关闭连接
        }
    } catch (Throwable t) {
        handleReadException(pipeline, byteBuf, t, close, allocHandle);  // [17] 异常处理
    } finally {
        if (!readPending && !config.isAutoRead()) {
            removeReadOp();                                    // [18] 移除 OP_READ
        }
    }
}
```

**逐行解析**：

| 标号 | 代码 | 深度分析 |
|------|------|---------|
| [1] | `shouldBreakReadReady(config)` | 🔥 **注意**：条件是 `isInputShutdown0() && (inputClosedSeenErrorOnRead \|\| !isAllowHalfClosure(config))`。即：输入已关闭，**且**（之前读取时遇到过错误 **或** `allowHalfClosure=false`（默认））。默认情况下只要输入关闭就会 break，不需要 `inputClosedSeenErrorOnRead=true` |
| [2] | `config.getAllocator()` | 获取 `ByteBufAllocator`（默认 `PooledByteBufAllocator`） |
| [3] | `recvBufAllocHandle()` | 懒初始化 `AdaptiveRecvByteBufAllocator.HandleImpl` |
| [4] | `allocHandle.reset(config)` | 重置 `totalMessages=0, totalBytesRead=0`，读取 `maxMessagesPerRead=16` |
| [5] | `allocHandle.allocate(allocator)` | 🔥 **关键**：`allocator.ioBuffer(allocHandle.guess())`，`guess()` 返回 AdaptiveCalculator 预测的大小（初始 2048） |
| [6] | `doReadBytes(byteBuf)` | 调用 `NioSocketChannel.doReadBytes()`，底层执行 JDK `SocketChannel.read(ByteBuffer)` |
| [7] | `lastBytesRead() <= 0` | 0 = Socket 暂时没有数据（非阻塞模式正常情况）；-1 = EOF（对端关闭连接） |
| [9] | `close = lastBytesRead() < 0` | 只有 -1（EOF）才设置 close=true，0 只是退出循环 |
| [11] | `fireChannelRead(byteBuf)` | 🔥 **与 NioMessageUnsafe 的区别**：ByteUnsafe 是**每读一次就立即 fire**，不等循环结束！原因：ByteBuf 可能很大，不能全部暂存 |
| [12] | `byteBuf = null` | 置 null 防止 finally 中重复 release（ByteBuf 已经交给 Pipeline 处理了） |
| [13] | `continueReading()` | 🔥 **核心判断**：`autoRead && totalMessages < 16 && lastBytesRead == attemptedBytesRead`（读满了才继续） |
| [14] | `allocHandle.readComplete()` | 通知 AdaptiveCalculator 本轮总读取量，用于调整下次 ByteBuf 大小 |
| [15] | `fireChannelReadComplete()` | 通知 Pipeline 本轮读取完成，用户可在此做批量 flush 等操作 |
| [16] | `closeOnRead(pipeline)` | 🔥 **注意**：`fireChannelReadComplete()` 在 [15] 处已经触发，`closeOnRead()` 本身不再触发它。有三个分支：① 输入未关闭 + `allowHalfClosure=false`（默认）→ `close(voidPromise())` 关闭连接；② 输入未关闭 + `allowHalfClosure=true` → `shutdownInput()` + `fireUserEventTriggered(ChannelInputShutdownEvent)` 半关闭；③ 输入已关闭（`isInputShutdown0()=true`）且首次 → `inputClosedSeenErrorOnRead=true` + `fireUserEventTriggered(ChannelInputShutdownReadComplete)` |
| [17] | `handleReadException(...)` | 🔥 **异常处理细节**：① 若 `byteBuf != null && byteBuf.isReadable()`，先 `fireChannelRead(byteBuf)` 把已读数据交给 Pipeline；② 若 `byteBuf` 为空则 `release()`；③ 调用 `readComplete()` + `fireChannelReadComplete()` + `fireExceptionCaught(cause)`；④ 若 `close=true` 或 `cause instanceof OutOfMemoryError/IOException` 则调用 `closeOnRead(pipeline)` |
| [18] | `removeReadOp()` | 若 `autoRead=false` 且没有 pending 的 read 请求，从 Selector 移除 `OP_READ`，停止读取 |

### 6.3 NioByteUnsafe vs NioMessageUnsafe 的关键差异

| 对比项 | NioMessageUnsafe（ServerChannel） | NioByteUnsafe（SocketChannel） |
|--------|----------------------------------|-------------------------------|
| **fireChannelRead 时机** | 循环结束后**批量** fire | 每次读取后**立即** fire |
| **readBuf** | 有（`List<Object>`，暂存 Channel） | 无（ByteBuf 直接 fire） |
| **continueReading 条件** | `totalMessages < 16` | `totalMessages < 16 && lastBytesRead == attemptedBytesRead` |
| **EOF 处理** | `closed=true` → 关闭 ServerChannel | `close=true` → 关闭 SocketChannel |
| **allocHandle 作用** | 仅计数（不分配 ByteBuf） | 分配 ByteBuf + 自适应大小预测 |

> 🔥 **面试高频**：为什么 NioMessageUnsafe 要批量 fire，而 NioByteUnsafe 要立即 fire？
> **答**：NioMessageUnsafe accept 到的是 `NioSocketChannel` 对象，很轻量，可以暂存后批量处理，减少 Pipeline 调用次数。NioByteUnsafe 读到的是 `ByteBuf`，可能很大（几十 KB），如果暂存所有 ByteBuf 再批量 fire，内存占用会很高；而且立即 fire 可以让 Handler 尽早处理数据，减少延迟。


---

## 七、AdaptiveRecvByteBufAllocator — 自适应 ByteBuf 大小

### 7.1 问题推导（Skill #15）

**问题**：每次读取数据时，应该分配多大的 ByteBuf？

**推导**：
- 太小：需要多次 `SocketChannel.read()`，多次系统调用，延迟高
- 太大：浪费内存，GC 压力大
- 最优解：**根据历史读取量动态预测**——如果上次读满了，说明数据量大，下次分配更大的；如果上次没读满，说明数据量小，下次分配更小的

**推导出的结构**：
```
AdaptiveCalculator {
    int[] SIZE_TABLE;    // 预定义的大小档位表
    int index;           // 当前在 SIZE_TABLE 中的位置
    int nextSize;        // 下次分配的大小
    boolean decreaseNow; // 是否立即缩容（需要连续两次不满才缩容）
    // 扩容：激进（+4档）；缩容：保守（-1档，且需要连续两次）
}
```

### 7.2 SIZE_TABLE — 大小档位表

**源码位置**：`common/src/main/java/io/netty/util/internal/AdaptiveCalculator.java`

```java
static {
    List<Integer> sizeTable = new ArrayList<Integer>();
    // 16 ~ 496，步长 16（小数据精细控制）
    for (int i = 16; i < 512; i += 16) {
        sizeTable.add(i);
    }
    // 512, 1024, 2048, 4096, ... 直到 int 溢出（大数据粗粒度控制）
    for (int i = 512; i > 0; i <<= 1) {
        sizeTable.add(i);
    }
    SIZE_TABLE = new int[sizeTable.size()];
    for (int i = 0; i < SIZE_TABLE.length; i++) {
        SIZE_TABLE[i] = sizeTable.get(i);
    }
}
```

SIZE_TABLE 的结构：

| 范围 | 步长 | 档位数 | 说明 |
|------|------|--------|------|
| 16 ~ 496 | 16 | 31 档 | 小数据精细控制（16, 32, 48, ..., 496） |
| 512 ~ 1073741824 | ×2 | 22 档 | 大数据粗粒度控制（512, 1024, 2048, ..., 1073741824） |

> **注意**：SIZE_TABLE 共 **53 档**，最大值约 1GB。`maximum=65536` 是 `AdaptiveRecvByteBufAllocator` 的默认上限（`maxIndex=38`），不是 SIZE_TABLE 的上限。

**默认参数**：
- `minimum = 64`（最小 ByteBuf 大小）
- `initial = 2048`（初始 ByteBuf 大小）
- `maximum = 65536`（最大 ByteBuf 大小）

### 7.3 自适应算法 — record() 方法

```java
// AdaptiveCalculator.record()
public void record(int size) {
    // 缩容判断：实际读取量 <= SIZE_TABLE[max(0, index-1)]（比当前档位小一档；max(0,...)防止index=0时越界）
    if (size <= SIZE_TABLE[max(0, index - INDEX_DECREMENT)]) {
        if (decreaseNow) {
            // 连续两次不满 → 缩容（-1档）
            index = max(index - INDEX_DECREMENT, minIndex);
            nextSize = max(SIZE_TABLE[index], minCapacity);
            decreaseNow = false;
        } else {
            // 第一次不满 → 标记，等下次再确认
            decreaseNow = true;
        }
    }
    // 扩容判断：实际读取量 >= 当前预测大小（读满了）
    else if (size >= nextSize) {
        // 立即扩容（+4档）
        index = min(index + INDEX_INCREMENT, maxIndex);
        nextSize = min(SIZE_TABLE[index], maxCapacity);
        decreaseNow = false;
    }
    // 否则：保持不变
}
```

**算法特点**：

| 操作 | 触发条件 | 步长 | 策略 |
|------|---------|------|------|
| **扩容** | 读取量 >= 当前预测大小（读满了） | +4 档 | **激进**：快速扩容，避免多次系统调用 |
| **缩容** | 读取量 <= `SIZE_TABLE[max(0, index-1)]`（比当前档位小一档），且**连续两次** | -1 档 | **保守**：避免频繁缩容后又扩容（抖动）；`max(0,...)` 防止 index=0 时数组越界 |
| **不变** | 介于两者之间 | 0 | 保持稳定 |

> 🔥 **面试高频**：为什么扩容激进（+4档）而缩容保守（-1档，且需要连续两次）？
> **答**：扩容激进是为了快速适应突发大流量，避免多次小 ByteBuf 读取导致多次系统调用，降低延迟。缩容保守是为了避免"抖动"——如果数据量在某个阈值附近波动，激进缩容会导致频繁扩容/缩容，反而浪费 CPU。

### 7.4 record() 的调用时机

```java
// AdaptiveRecvByteBufAllocator.HandleImpl
@Override
public void lastBytesRead(int bytes) {
    // 每次读取后：如果读满了（bytes == attemptedBytesRead），立即 record
    if (bytes == attemptedBytesRead()) {
        calculator.record(bytes);
    }
    super.lastBytesRead(bytes);
}

@Override
public void readComplete() {
    // 本轮读取完成后：用总读取量 record（用于缩容判断）
    calculator.record(totalBytesRead());
}
```

**两次 record 的作用**：
1. **`lastBytesRead` 中的 record**：如果单次读取就读满了 ByteBuf，说明数据量大，立即触发扩容判断
2. **`readComplete` 中的 record**：用本轮总读取量做最终判断，主要用于缩容

### 7.5 自适应过程示例（真实运行数据验证）

> 以下数据通过运行 `AdaptiveCalculator` 逻辑验证，**非推断**（Skill #16）：
> ```
> SIZE_TABLE 共53档：[3]=64(minIndex), [33]=2048(initial), [36]=16384, [37]=32768, [38]=65536(maxIndex)
> INDEX_INCREMENT=4, INDEX_DECREMENT=1, minimum=64(minIndex=3), maximum=65536(maxIndex=38)
> 缩容条件：size <= SIZE_TABLE[max(0, index-1)]（注意是 index-1，不是 index）
> ```

```mermaid
graph LR
    subgraph "初始状态"
        A["nextSize = 2048\nindex = 33"]
    end
    subgraph "第1轮：读了2048字节（读满）"
        B["record(2048)\n2048 >= nextSize(2048) → 扩容\nindex += 4 → 37\nnextSize = SIZE_TABLE[37] = 32768"]
    end
    subgraph "第2轮：读了32768字节（读满）"
        C["record(32768)\n32768 >= nextSize(32768) → 扩容\nindex += 4 → 38（达到maxIndex）\nnextSize = min(SIZE_TABLE[38], 65536) = 65536"]
    end
    subgraph "第3轮：读了1024字节（未满）"
        D["record(1024)\n此时index=38，缩容阈值=SIZE_TABLE[max(0,38-1)]=SIZE_TABLE[37]=32768\n1024 <= 32768 → decreaseNow=true，nextSize 不变 = 65536"]
    end
    subgraph "第4轮：读了1024字节（未满）"
        E["record(1024)\n连续两次 → 缩容\nindex -= 1 → 37\nnextSize = SIZE_TABLE[37] = 32768"]
    end

    A --> B --> C --> D --> E

    style B fill:#ffcdd2
    style C fill:#ffcdd2
    style D fill:#fff9c4
    style E fill:#c8e6c9
```

> ⚠️ **常见误解**：很多人以为 `+4档` 是 2048→4096→8192 这样的小步扩容，实际上 `SIZE_TABLE` 在 512 以上是**倍增**的（512, 1024, 2048, 4096, 8192, 16384, 32768, 65536），所以 `index=33` 扩容 `+4档` 直接跳到 `index=37`，即 **2048 → 32768**，是 **16倍**的跳跃！这正是"激进扩容"的体现。

---

## 八、doReadBytes() — JDK read() 的封装

```java
// NioSocketChannel.doReadBytes()
@Override
protected int doReadBytes(ByteBuf byteBuf) throws Exception {
    final RecvByteBufAllocator.Handle allocHandle = unsafe().recvBufAllocHandle();
    allocHandle.attemptedBytesRead(byteBuf.writableBytes());  // [1] 记录本次尝试读取的字节数
    return byteBuf.writeBytes(javaChannel(), allocHandle.attemptedBytesRead());
    // [2] 底层调用 JDK SocketChannel.read(ByteBuffer)
    // 返回值：实际读取字节数（>0），0（暂无数据），-1（EOF）
}
```

**`byteBuf.writeBytes(channel, length)` 的底层实现**：

```java
// AbstractByteBuf.writeBytes(ScatteringByteChannel, int)
@Override
public int writeBytes(ScatteringByteChannel in, int length) throws IOException {
    ensureWritable(length);
    int writtenBytes = setBytes(writerIndex, in, length);  // 调用 JDK read()
    if (writtenBytes > 0) {
        writerIndex += writtenBytes;
    }
    return writtenBytes;
}
```

**`attemptedBytesRead` 的作用**：记录本次尝试读取的字节数（= ByteBuf 的可写字节数），用于 `continueReading()` 中判断是否读满：

```java
// continueReading 的核心判断
private final UncheckedBooleanSupplier defaultMaybeMoreSupplier = () ->
    attemptedBytesRead == lastBytesRead;  // 读满了 = 可能还有更多数据
```

> 🔥 **面试高频**：`continueReading()` 中 `lastBytesRead == attemptedBytesRead` 是什么意思？
> **答**：`attemptedBytesRead` 是本次分配的 ByteBuf 大小（尝试读取的字节数），`lastBytesRead` 是实际读取的字节数。如果两者相等，说明 ByteBuf 被**读满了**，Socket 缓冲区可能还有数据，应该继续读取。如果 `lastBytesRead < attemptedBytesRead`，说明 Socket 缓冲区的数据已经读完，不需要继续。

---

## 九、字节读取完整时序图

```mermaid
sequenceDiagram
    participant EL_Worker as Worker EventLoop
    participant NioByteUnsafe as NioByteUnsafe
    participant allocHandle as AdaptiveRecvByteBufAllocator.Handle
    participant doReadBytes as doReadBytes()
    participant Pipeline as Child Pipeline
    participant Handler as EchoServerHandler

    EL_Worker->>NioByteUnsafe: handle(OP_READ) → read()
    activate NioByteUnsafe
    NioByteUnsafe->>allocHandle: reset(config)
    Note over allocHandle: totalMessages=0, totalBytesRead=0

    loop 最多16次（continueReading()=true）
        NioByteUnsafe->>allocHandle: allocate(allocator)
        allocHandle->>allocHandle: guess() → nextSize（如2048）
        allocHandle-->>NioByteUnsafe: ByteBuf(capacity=2048)

        NioByteUnsafe->>doReadBytes: doReadBytes(byteBuf)
        doReadBytes->>doReadBytes: JDK SocketChannel.read(ByteBuffer)
        doReadBytes-->>NioByteUnsafe: actualBytes（如1500）

        NioByteUnsafe->>allocHandle: lastBytesRead(1500)
        Note over allocHandle: 1500 < 2048，不扩容

        NioByteUnsafe->>Pipeline: fireChannelRead(byteBuf)
        Pipeline->>Handler: channelRead(ctx, byteBuf)
        Handler->>Handler: 处理数据（如echo回写）
        Handler-->>Pipeline: return

        NioByteUnsafe->>allocHandle: continueReading()
        Note over allocHandle: 1500 != 2048 → false，退出循环
    end

    NioByteUnsafe->>allocHandle: readComplete()
    Note over allocHandle: record(1500) → 缩容判断
    NioByteUnsafe->>Pipeline: fireChannelReadComplete()
    deactivate NioByteUnsafe
```

---

## 十、线程交互全景图

```mermaid
sequenceDiagram
    participant Client as 客户端
    participant OS as 操作系统
    participant Boss as Boss EventLoop
    participant Worker as Worker EventLoop
    participant Pipeline_S as Server Pipeline
    participant Pipeline_C as Child Pipeline

    Client->>OS: TCP 三次握手
    OS->>Boss: OP_ACCEPT 事件
    Boss->>Boss: NioMessageUnsafe.read()
    Boss->>Boss: doReadMessages() → new NioSocketChannel
    Boss->>Pipeline_S: fireChannelRead(NioSocketChannel)
    Pipeline_S->>Pipeline_S: ServerBootstrapAcceptor.channelRead()
    Pipeline_S->>Pipeline_S: child.pipeline().addLast(childHandler)
    Pipeline_S->>Worker: childGroup.register(child)

    Note over Worker: Worker EventLoop 线程
    Worker->>Worker: register0() → doRegister()
    Worker->>Worker: 注册到 Selector(interestOps=0)
    Worker->>Pipeline_C: fireChannelRegistered()
    Worker->>Pipeline_C: fireChannelActive()
    Pipeline_C->>Pipeline_C: HeadContext.channelActive()
    Pipeline_C->>Worker: beginRead() → 注册 OP_READ

    Client->>OS: 发送数据
    OS->>Worker: OP_READ 事件
    Worker->>Worker: NioByteUnsafe.read()
    Worker->>Worker: allocHandle.allocate() → ByteBuf
    Worker->>Worker: doReadBytes() → JDK read()
    Worker->>Pipeline_C: fireChannelRead(ByteBuf)
    Pipeline_C->>Pipeline_C: EchoServerHandler.channelRead()
    Pipeline_C->>Worker: ctx.writeAndFlush(msg)
    Worker->>OS: JDK SocketChannel.write()
    OS->>Client: 响应数据
```

---

## 十一、核心不变式（Invariants）

1. **read 线程不变式**：`NioMessageUnsafe.read()` 和 `NioByteUnsafe.read()` 必须在 Channel 绑定的 EventLoop 线程中执行（`assert eventLoop().inEventLoop()`）。所有 Pipeline 事件（`fireChannelRead`、`fireChannelReadComplete`）也在同一线程中触发，无需同步。

2. **accept 上限不变式**：每次 `OP_ACCEPT` 事件最多 accept `maxMessagesPerRead`（默认 16）个新连接。即使有更多连接等待，也会在下一次 EventLoop 循环中处理，保证其他 Channel 的 IO 事件不被饥饿。

3. **ByteBuf 所有权不变式**：`NioByteUnsafe.read()` 分配的 ByteBuf，在 `fireChannelRead(byteBuf)` 后所有权转移给 Pipeline。如果 Handler 不处理（不调用 `ctx.fireChannelRead(msg)`），TailContext 会尝试释放。如果 Handler 处理后不释放，会发生内存泄漏。

---

## 十二、Skill #16 验证：日志打印验证关键结论

### 12.1 验证结论：accept 循环最多执行 16 次

在 `NioServerSocketChannel.doReadMessages()` 中加入打印：

```java
// 在 NioServerSocketChannel.doReadMessages() 中添加
@Override
protected int doReadMessages(List<Object> buf) throws Exception {
    SocketChannel ch = SocketUtils.accept(javaChannel());
    try {
        if (ch != null) {
            System.out.println("[VERIFY-ACCEPT] thread=" + Thread.currentThread().getName()
                + " accepted=" + ch.getRemoteAddress()
                + " bufSize=" + (buf.size() + 1));
            buf.add(new NioSocketChannel(this, ch));
            return 1;
        }
    } catch (Throwable t) {
        // ...
    }
    return 0;
}
```

**预期输出**（同时发起 20 个连接时）：
```
[VERIFY-ACCEPT] thread=nioEventLoopGroup-2-1 accepted=/127.0.0.1:52001 bufSize=1
[VERIFY-ACCEPT] thread=nioEventLoopGroup-2-1 accepted=/127.0.0.1:52002 bufSize=2
...
[VERIFY-ACCEPT] thread=nioEventLoopGroup-2-1 accepted=/127.0.0.1:52016 bufSize=16
// 第17个连接在下一次 EventLoop 循环中处理
```

### 12.2 验证结论：NioSocketChannel 注册在 Worker 线程

在 `AbstractChannel.register0()` 中加入打印：

```java
// 在 register0() 的 registerPromise.addListener 回调中添加
System.out.println("[VERIFY-REGISTER] thread=" + Thread.currentThread().getName()
    + " channel=" + AbstractChannel.this.getClass().getSimpleName()
    + " isActive=" + isActive()
    + " firstRegistration=" + firstRegistration);
```

**预期输出**：
```
// ServerChannel 注册（Boss 线程，isActive=false，bind 之前）
[VERIFY-REGISTER] thread=nioEventLoopGroup-2-1 channel=NioServerSocketChannel isActive=false firstRegistration=true

// SocketChannel 注册（Worker 线程，isActive=true，立即触发 channelActive）
[VERIFY-REGISTER] thread=nioEventLoopGroup-3-1 channel=NioSocketChannel isActive=true firstRegistration=true
[VERIFY-REGISTER] thread=nioEventLoopGroup-3-2 channel=NioSocketChannel isActive=true firstRegistration=true
```

### 12.3 验证结论：AdaptiveRecvByteBufAllocator 的扩容行为

在 `AdaptiveCalculator.record()` 中加入打印：

```java
public void record(int size) {
    System.out.println("[VERIFY-ADAPTIVE] size=" + size
        + " nextSize(before)=" + nextSize
        + " index=" + index
        + " decreaseNow=" + decreaseNow);
    // ... 原有逻辑
    System.out.println("[VERIFY-ADAPTIVE] nextSize(after)=" + nextSize);
}
```

**真实输出**（发送超过 2048 字节数据，ByteBuf 被读满时）：
```
// lastBytesRead 中触发（单次读满）
[VERIFY-ADAPTIVE] size=2048 nextSize(before)=2048 index=33 decreaseNow=false
[VERIFY-ADAPTIVE] nextSize(after)=32768  // 扩容 +4档！2048→32768（16倍跳跃）

// readComplete 中触发（totalBytesRead=2048）
[VERIFY-ADAPTIVE] size=2048 nextSize(before)=32768 index=37 decreaseNow=false
[VERIFY-ADAPTIVE] nextSize(after)=32768  // 2048 < 32768 且 2048 > SIZE_TABLE[36]=16384，不变
```
> 以上输出通过实际运行 `AdaptiveCalculator` 逻辑验证（Skill #16）。

> **注意**：以上打印代码仅用于验证，生产环境不要保留。验证完成后删除。

---

## 十三、面试问答

**Q1**：一次 `OP_ACCEPT` 事件最多 accept 几个连接？为什么要限制？🔥🔥🔥

**A**：最多 **16 个**（`maxMessagesPerRead` 默认值，由 `ChannelMetadata(false, 16)` 决定）。限制的原因是：EventLoop 是单线程的，如果一直 accept 新连接，其他已连接 Channel 的 `OP_READ` 事件就无法被处理，造成 IO 饥饿。16 是一个经验值，在吞吐量和公平性之间取得平衡。

---

**Q2**：`ServerBootstrapAcceptor.channelRead()` 在哪个线程执行？它做了什么？🔥🔥🔥

**A**：在 **Boss EventLoop 线程**中执行（因为 `fireChannelRead` 由 Boss EventLoop 触发）。它做了三件事：
1. 给子 Channel 的 Pipeline 添加用户配置的 `childHandler`
2. 设置子 Channel 的 TCP 选项和自定义属性
3. 调用 `childGroup.register(child)` 将子 Channel 注册到 Worker EventLoop

---

**Q3**：`NioSocketChannel` 的 `channelActive` 是在什么时候触发的？🔥🔥

**A**：在 Worker EventLoop 执行 `register0()` 的回调中触发。因为 `NioSocketChannel` 在 accept 时就已经建立了连接（`isConnected()=true`），所以 `isActive()=true`。`register0()` 回调中检测到 `isActive()=true && firstRegistration=true`，立即触发 `pipeline.fireChannelActive()`。`channelActive` 触发后，`HeadContext` 会调用 `beginRead()`，注册 `OP_READ` 到 Selector，从此开始监听数据。

---

**Q4**：`NioByteUnsafe.read()` 的循环什么时候停止？🔥🔥🔥

**A**：满足以下任一条件时停止：
1. `doReadBytes()` 返回 0（Socket 缓冲区暂时没有数据）
2. `doReadBytes()` 返回 -1（EOF，对端关闭连接）
3. `continueReading()` 返回 false：`totalMessages >= 16`（达到上限）或 `lastBytesRead < attemptedBytesRead`（没读满，说明数据已读完）或 `autoRead=false`

---

**Q5**：`AdaptiveRecvByteBufAllocator` 的扩容和缩容策略有什么不同？为什么？🔥🔥

**A**：
- **扩容**：激进，一次 +4 档。由于 `SIZE_TABLE` 在 512 以上是倍增序列（512, 1024, 2048, 4096, 8192, 16384, 32768, 65536），+4 档意味着**跳跃式扩容**：如 2048（index=33）→ 32768（index=37），直接 16 倍！原因：快速适应突发大流量，避免多次小 ByteBuf 读取导致多次系统调用。
- **缩容**：保守，一次 -1 档，且需要**连续两次**读取量不满才缩容。原因：避免"抖动"——如果数据量在阈值附近波动，激进缩容会导致频繁扩容/缩容，浪费 CPU。

---

**Q6**：`channelRead` 和 `channelReadComplete` 的区别是什么？🔥🔥

**A**：
- `channelRead`：每读取一次数据就触发一次（NioByteUnsafe 每次循环都 fire）。参数是具体的数据（`ByteBuf` 或 `NioSocketChannel`）。
- `channelReadComplete`：一轮读取循环结束后触发一次（无论循环了几次）。没有数据参数，用于通知 Handler "本轮读取已完成，可以做批量处理"。

典型用法：在 `channelRead` 中积累数据，在 `channelReadComplete` 中批量 flush 响应，减少系统调用次数。

---

**Q7**：如果 Handler 在 `channelRead` 中不释放 ByteBuf 会怎样？⚠️

**A**：会发生内存泄漏。`NioByteUnsafe.read()` 分配的 ByteBuf 在 `fireChannelRead` 后所有权转移给 Pipeline。如果 Handler 既不调用 `ctx.fireChannelRead(msg)` 传递给下一个 Handler，也不调用 `ReferenceCountUtil.release(msg)` 释放，ByteBuf 的引用计数永远不会归零，内存无法回收。Netty 的 `TailContext` 会打印 WARN 日志 "Discarded inbound message that reached at the tail of the pipeline"，并尝试释放，但这是最后的兜底，不应该依赖它。

---

**Q8**：`autoRead=false` 时如何停止接受新连接？🔥

**A**：`autoRead=false` 时，`continueReading()` 返回 false，read 循环提前退出。在 `finally` 块中，`!readPending && !config.isAutoRead()` 为 true，调用 `removeReadOp()` 从 Selector 移除 `OP_ACCEPT`（或 `OP_READ`）。此后 Selector 不再报告该 Channel 的 IO 事件，直到调用 `channel.read()`（或 `ctx.read()`）重新注册 `OP_ACCEPT`/`OP_READ`。

---

## 十四、Self-Check

### Skills 符合性检查

| Skill | 要求 | 是否满足 | 说明 |
|-------|------|----------|------|
| #1 先数据结构后行为 | 先梳理字段、继承、持有关系 | ✅ | 每节都先推导数据结构再分析行为 |
| #2 带着问题读源码 | 明确问题并回答 | ✅ | 开头 6 个核心问题，面试问答全部回答 |
| #4 请求生命周期主线 | 围绕请求路径组织 | ✅ | 从 OP_ACCEPT → accept → 注册 → OP_READ → 读取 完整路径 |
| #7 四类图 | 对象关系图、时序图、状态机图、线程交互图 | ✅ | accept 时序图 + 字节读取时序图 + 线程交互全景图 + 自适应流程图 |
| #8 不变式 | 核心不变式 | ✅ | 3 个核心不变式 |
| #9 格式规范 | 问题→数据结构→流程→设计动机 | ✅ | 严格按此顺序 |
| #10 全量分析 | 每行源码都分析 | ✅ | NioMessageUnsafe.read() 和 NioByteUnsafe.read() 逐行分析 |
| #11 关联生产 | 面试🔥和踩坑⚠️ | ✅ | 8 道面试题 + 3 个生产踩坑标注 |
| #13 父类构造链必查 | 逐层追溯所有父类构造 | ✅ | NioSocketChannel 创建时的完整构造链 |
| #14 Mermaid绘图 | 所有图使用 Mermaid | ✅ | 5 张 Mermaid 图 |
| #15 问题驱动推导 | 5步流程：问题→推导→结构→分析→算法 | ✅ | 第三节、第六节、第七节都有"问题推导"段落 |
| #16 日志验证结论 | 真实数据支撑结论 | ✅ | 第十二节提供了 3 个验证方案和预期输出 |

### 完整性检查

- [x] EventLoop 触发 read 的入口（processSelectedKey → handle → read）
- [x] NioMessageUnsafe.read() 完整源码逐行分析（16 行注释）
- [x] continueReading() 判断逻辑
- [x] doReadMessages() → JDK accept() 封装
- [x] NioSocketChannel 创建时的构造链（Skill #13）
- [x] ServerBootstrapAcceptor.channelRead() 完整分析
- [x] childGroup.register(child) 调用链
- [x] register0() 中 channelActive 触发时机
- [x] NioByteUnsafe.read() 完整源码逐行分析（18 行注释）
- [x] NioByteUnsafe vs NioMessageUnsafe 对比表
- [x] AdaptiveRecvByteBufAllocator SIZE_TABLE 结构
- [x] AdaptiveCalculator.record() 扩容/缩容算法
- [x] doReadBytes() → JDK read() 封装
- [x] attemptedBytesRead 的作用
- [x] accept 完整时序图（Mermaid）
- [x] 字节读取完整时序图（Mermaid）
- [x] 线程交互全景图（Mermaid）
- [x] 自适应过程示例图（Mermaid）
- [x] 3 个核心不变式
- [x] 3 个日志验证方案（Skill #16）
- [x] 8 道面试问答

### 自我质疑与回答

1. **Q：`NioMessageUnsafe.read()` 中，readBuf 是实例变量还是局部变量？有什么影响？**
   A：是**实例变量**（`private final List<Object> readBuf = new ArrayList<Object>()`）。这意味着它在多次 read 调用之间复用，避免每次 read 都创建新的 ArrayList。但每次 read 结束后必须调用 `readBuf.clear()` 清空，否则下次 read 会累积旧数据。源码中 [10] 处确实有 `readBuf.clear()`，设计正确。✅

2. **Q：如果 `doReadMessages()` 抛出异常，readBuf 中已经 accept 的 Channel 会怎样？**
   A：异常被 catch 后，代码继续执行 [9] 处的 `for` 循环，对 readBuf 中已有的 Channel 仍然会 `fireChannelRead`。这是正确的——已经 accept 的连接不应该因为后续 accept 失败而被丢弃。✅

3. **Q：`AdaptiveRecvByteBufAllocator` 在 4.2 版本中有什么变化？**
   A：4.2 版本将核心算法从 `AdaptiveRecvByteBufAllocator` 内部类 `HandleImpl` 中抽取到独立的 `AdaptiveCalculator` 类（位于 `common` 模块），使得自适应算法可以被其他场景复用（如 io_uring 的 buffer ring 大小调整）。`AdaptiveRecvByteBufAllocator.HandleImpl` 现在只是 `AdaptiveCalculator` 的一个薄包装。✅

4. **Q：`continueReading()` 中 `ignoreBytesRead` 是什么？**
   A：`ignoreBytesRead` 是 `DefaultMaxMessagesRecvByteBufAllocator` 的一个字段，对于 `NioMessageUnsafe`（ServerChannel）设置为 `true`，因为 ServerChannel 的 "read" 不涉及字节读取，`totalBytesRead` 始终为 0，如果不忽略这个条件，`continueReading()` 永远返回 false，导致每次只 accept 一个连接。✅

> **下一步**：进入 `03-channel-write-flush-close.md`，深度分析 write/flush/close 路径——`ChannelOutboundBuffer` 的完整操作、写半包处理、close 流程。
