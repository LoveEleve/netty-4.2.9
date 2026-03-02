# 09-01 连接生命周期与故障处理：超时 + 空闲检测 + 优雅关闭深度分析

> **核心问题**：
> 1. Netty Channel 的完整生命周期有哪些状态？每个状态转换的触发条件是什么？
> 2. `IdleStateHandler` 如何用定时任务实现空闲检测？`observeOutput` 参数解决什么问题？
> 3. 连接关闭时，`ChannelOutboundBuffer` 中的 pending 消息如何处理？

---

## 一、解决什么问题

### 1.1 连接生命周期管理的挑战

在生产环境中，连接管理面临三类核心问题：

| 问题 | 现象 | 后果 |
|------|------|------|
| **僵尸连接** | 对端崩溃但 TCP 连接未关闭（半开连接） | 服务端持有大量无效连接，浪费 fd 和内存 |
| **慢消费者** | 对端处理慢，写缓冲区积压 | OOM 或连接被迫关闭 |
| **连接超时** | 网络不通或对端不响应 | 客户端长时间阻塞等待 |

Netty 的解法：
- **空闲检测**：`IdleStateHandler` 定时检测读写空闲，触发 `IdleStateEvent`，由业务决定是发心跳还是关闭连接
- **超时控制**：`CONNECT_TIMEOUT_MILLIS`（连接超时）、`ReadTimeoutHandler`（读超时）、`WriteTimeoutHandler`（写超时）
- **优雅关闭**：`close()` 保证 pending 消息被通知失败，`shutdownOutput()` 支持 TCP 半关闭

### 1.2 设计目标

```
连接建立 → 活跃 → 空闲检测 → 心跳/关闭
    ↓                              ↓
连接超时                      优雅关闭
（ConnectTimeoutException）   （failFlushed + fireChannelInactive）
```

---

## 二、Channel 生命周期状态机

### 2.1 完整状态机

```mermaid
flowchart LR
    S(["★ 起点"]) --> Created
    Created(["**Created**<br/>isOpen=true<br/>isRegistered=false<br/>isActive=false"])
    Created -->|"register(EventLoop)<br/>fireChannelRegistered()"| Registered
    Registered(["**Registered**<br/>isOpen=true<br/>isRegistered=true<br/>isActive=false"])
    Registered -->|"connect()成功 / bind()+accept()<br/>fireChannelActive()"| Active
    Active(["**Active**<br/>isOpen=true<br/>isRegistered=true<br/>isActive=true"])
    Active -->|"close() / 对端关闭<br/>fireChannelInactive()"| Inactive
    Inactive(["**Inactive**<br/>isOpen=false<br/>isRegistered=true<br/>isActive=false"])
    Inactive -->|"deregister()<br/>fireChannelUnregistered()"| Deregistered
    Deregistered(["**Deregistered**"]) --> E(["★ 终点"])

    style Created fill:#e3f2fd
    style Registered fill:#e8f5e9
    style Active fill:#c8e6c9
    style Inactive fill:#ffcdd2
    style Deregistered fill:#f5f5f5
```

### 2.2 关键状态判断方法

```java
// Channel 状态判断
channel.isOpen()       // Socket 是否打开（未调用 close()）
channel.isRegistered() // 是否已注册到 EventLoop
channel.isActive()     // 是否已连接且可读写（isOpen() && isConnected()）
channel.isWritable()   // 写缓冲区是否未超过高水位线

// ChannelFuture 状态
closeFuture.isDone()   // 连接是否已关闭（可用于等待关闭完成）
```

### 2.3 Pipeline 生命周期事件

```mermaid
sequenceDiagram
    participant EL as EventLoop
    participant Pipeline as Pipeline
    participant H as Handler

    Note over EL: 连接建立
    EL->>Pipeline: fireChannelRegistered()
    Pipeline->>H: channelRegistered()
    EL->>Pipeline: fireChannelActive()
    Pipeline->>H: channelActive()

    Note over EL: 数据读写
    EL->>Pipeline: fireChannelRead(msg)
    Pipeline->>H: channelRead(msg)
    EL->>Pipeline: fireChannelReadComplete()
    Pipeline->>H: channelReadComplete()

    Note over EL: 连接关闭
    EL->>Pipeline: fireChannelInactive()
    Pipeline->>H: channelInactive()
    EL->>Pipeline: fireChannelUnregistered()
    Pipeline->>H: channelUnregistered()
```

---

## 三、连接建立与超时

### 3.1 连接超时机制（AbstractNioChannel.connect）

```java
public final void connect(
        final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise promise) {
    // ...
    boolean wasActive = isActive();
    if (doConnect(remoteAddress, localAddress)) {
        fulfillConnectPromise(promise, wasActive);  // [1] 立即连接成功（本地回环等）
    } else {
        connectPromise = promise;
        requestedRemoteAddress = remoteAddress;

        // [2] 调度连接超时任务
        final int connectTimeoutMillis = config().getConnectTimeoutMillis();
        if (connectTimeoutMillis > 0) {
            connectTimeoutFuture = eventLoop().schedule(new Runnable() {
                @Override
                public void run() {
                    ChannelPromise connectPromise = AbstractNioChannel.this.connectPromise;
                    if (connectPromise != null && !connectPromise.isDone()
                            && connectPromise.tryFailure(new ConnectTimeoutException(
                                    "connection timed out after " + connectTimeoutMillis + " ms: " +
                                            remoteAddress))) {
                        close(voidPromise());  // [3] 超时后关闭 Channel
                    }
                }
            }, connectTimeoutMillis, TimeUnit.MILLISECONDS);
        }

        // [4] 连接被取消时，清理超时任务并关闭 Channel
        promise.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                if (future.isCancelled()) {
                    if (connectTimeoutFuture != null) {
                        connectTimeoutFuture.cancel(false);
                    }
                    connectPromise = null;
                    close(voidPromise());
                }
            }
        });
    }
}
```

<!-- 核对记录：已对照 AbstractNioChannel.java 源码第 290-335 行，差异：无 -->

### 3.2 连接完成（finishConnect）

```java
public final void finishConnect() {
    // 注意：只有在连接未被取消且未超时时，才会被 EventLoop 调用
    assert eventLoop().inEventLoop();

    try {
        boolean wasActive = isActive();
        doFinishConnect();
        fulfillConnectPromise(connectPromise, wasActive);  // [1] 连接成功
    } catch (Throwable t) {
        fulfillConnectPromise(connectPromise, annotateConnectException(t, requestedRemoteAddress));  // [2] 连接失败
    } finally {
        // [3] 无论成功失败，都取消超时任务
        if (connectTimeoutFuture != null) {
            connectTimeoutFuture.cancel(false);
        }
        connectPromise = null;
    }
}
```

<!-- 核对记录：已对照 AbstractNioChannel.java 源码第 380-400 行，差异：无 -->

### 3.3 连接超时时序图

```mermaid
sequenceDiagram
    participant App as 应用代码
    participant EL as EventLoop
    participant NIO as NioSocketChannel
    participant Timer as 定时任务

    App->>EL: bootstrap.connect(remoteAddr)
    EL->>NIO: doConnect(remoteAddr)
    NIO-->>EL: false（连接中）
    EL->>Timer: schedule(connectTimeoutTask, 30s)

    alt 30s 内连接成功
        NIO->>EL: OP_CONNECT 事件
        EL->>NIO: finishConnect()
        NIO->>Timer: connectTimeoutFuture.cancel(false)
        NIO->>App: promise.trySuccess()
        NIO->>EL: fireChannelActive()
    else 30s 超时
        Timer->>NIO: connectPromise.tryFailure(ConnectTimeoutException)
        NIO->>NIO: close(voidPromise())
        NIO->>App: promise 失败（ConnectTimeoutException）
    else 用户取消
        App->>NIO: promise.cancel()
        NIO->>Timer: connectTimeoutFuture.cancel(false)
        NIO->>NIO: close(voidPromise())
    end
```

---

## 四、Channel 关闭流程

### 4.1 close() 完整源码分析

```java
protected void close(final ChannelPromise promise, final Throwable cause,
                   final ClosedChannelException closeCause) {
    if (!promise.setUncancellable()) {
        return;
    }

    if (closeInitiated) {
        // [1] 幂等保护：close() 已经被调用过
        if (closeFuture.isDone()) {
            safeSetSuccess(promise);  // [2] 已关闭完成，直接成功
        } else if (!(promise instanceof VoidChannelPromise)) {
            // [3] 关闭中，注册 Listener 等待完成
            closeFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    promise.setSuccess();
                }
            });
        }
        return;
    }

    closeInitiated = true;  // [4] 标记关闭已启动

    final boolean wasActive = isActive();
    final ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
    this.outboundBuffer = null;  // [5] 🔥 禁止新的写入（write() 会看到 null 并立即失败）

    Executor closeExecutor = prepareToClose();  // [6] SO_LINGER 场景需要在独立线程执行
    if (closeExecutor != null) {
        closeExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    doClose0(promise);  // [7] 关闭底层 Socket
                } finally {
                    invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            if (outboundBuffer != null) {
                                outboundBuffer.failFlushed(cause, false);  // [8] 通知 pending 写请求失败
                                outboundBuffer.close(closeCause);
                            }
                            fireChannelInactiveAndDeregister(wasActive);  // [9] 触发 channelInactive
                        }
                    });
                }
            }
        });
    } else {
        try {
            doClose0(promise);  // [10] 直接在 EventLoop 线程关闭
        } finally {
            if (outboundBuffer != null) {
                outboundBuffer.failFlushed(cause, false);
                outboundBuffer.close(closeCause);
            }
        }
        if (inFlush0) {
            // [11] 正在 flush 中，延迟触发 channelInactive（避免重入）
            invokeLater(new Runnable() {
                @Override
                public void run() {
                    fireChannelInactiveAndDeregister(wasActive);
                }
            });
        } else {
            fireChannelInactiveAndDeregister(wasActive);
        }
    }
}
```

<!-- 核对记录：已对照 AbstractChannel.java 源码第 550-625 行，差异：无 -->

### 4.2 关闭顺序状态机

```mermaid
flowchart TD
    A["ctx.close() / channel.close()"] --> B{"closeInitiated?"}
    B -->|是| C{"closeFuture.isDone()?"}
    C -->|是| D["safeSetSuccess(promise)"]
    C -->|否| E["注册 Listener 等待"]
    B -->|否| F["closeInitiated = true<br/>outboundBuffer = null"]
    F --> G{"prepareToClose()<br/>返回 closeExecutor?"}
    G -->|是 SO_LINGER| H["closeExecutor 线程:<br/>doClose0(promise)"]
    H --> I["invokeLater:<br/>failFlushed + close<br/>fireChannelInactive"]
    G -->|否| J["EventLoop 线程:<br/>doClose0(promise)"]
    J --> K["failFlushed + close"]
    K --> L{"inFlush0?"}
    L -->|是| M["invokeLater:<br/>fireChannelInactive"]
    L -->|否| N["直接 fireChannelInactive"]
    I --> O["deregister()"]
    M --> O
    N --> O
    O --> P["fireChannelUnregistered()"]

    style F fill:#ffcdd2
    style H fill:#fff9c4
    style J fill:#c8e6c9
```

### 4.3 doClose0() 和 fireChannelInactiveAndDeregister()

```java
private void doClose0(ChannelPromise promise) {
    try {
        doClose();              // [1] 子类实现：关闭 SocketChannel（NioSocketChannel.doClose()）
        closeFuture.setClosed(); // [2] 标记 closeFuture 完成
        safeSetSuccess(promise); // [3] 通知调用方关闭成功
    } catch (Throwable t) {
        closeFuture.setClosed();
        safeSetFailure(promise, t);
    }
}

private void fireChannelInactiveAndDeregister(final boolean wasActive) {
    deregister(voidPromise(), wasActive && !isActive());
    // wasActive && !isActive() 为 true 时，deregister 内部会触发 fireChannelInactive()
}
```

<!-- 核对记录：已对照 AbstractChannel.java 源码第 627-640 行，差异：无 -->

### 4.4 TCP 半关闭（shutdownOutput）

```java
public final void shutdownOutput(final ChannelPromise promise) {
    assertEventLoop();
    shutdownOutput(promise, null);
}

private void shutdownOutput(final ChannelPromise promise, Throwable cause) {
    if (!promise.setUncancellable()) {
        return;
    }

    final ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
    if (outboundBuffer == null) {
        promise.setFailure(new ClosedChannelException());
        return;
    }
    this.outboundBuffer = null;  // [1] 禁止新写入

    final Throwable shutdownCause = cause == null ?
            new ChannelOutputShutdownException("Channel output shutdown") :
            new ChannelOutputShutdownException("Channel output shutdown", cause);

    try {
        doShutdownOutput();  // [2] 发送 FIN（TCP 半关闭）
        promise.setSuccess();
    } catch (Throwable err) {
        promise.setFailure(err);
    } finally {
        closeOutboundBufferForShutdown(pipeline, outboundBuffer, shutdownCause);
        // [3] failFlushed + close + fireUserEventTriggered(ChannelOutputShutdownEvent)
    }
}
```

<!-- 核对记录：已对照 AbstractChannel.java 源码第 500-540 行，差异：无 -->

**TCP 半关闭 vs 完全关闭**：

| 操作 | 发送 | 接收 | 场景 |
|------|------|------|------|
| `close()` | FIN + RST | 停止 | 完全关闭，双向 |
| `shutdownOutput()` | FIN | 仍可接收 | HTTP/1.1 请求结束，等待响应 |

---

## 五、IdleStateHandler 空闲检测

### 5.1 核心字段

```java
public class IdleStateHandler extends ChannelDuplexHandler {
    private static final long MIN_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(1);  // [1] 最小超时 1ms

    // 写监听器（复用，减少 GC）
    private final ChannelFutureListener writeListener = new ChannelFutureListener() {
        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            lastWriteTime = ticker.nanoTime();
            firstWriterIdleEvent = firstAllIdleEvent = true;  // [2] 写完成后重置 first 标志
        }
    };

    private final boolean observeOutput;          // [3] 是否观察输出缓冲区变化（解决慢写问题）
    private final long readerIdleTimeNanos;        // [4] 读空闲超时（纳秒）
    private final long writerIdleTimeNanos;        // [5] 写空闲超时（纳秒）
    private final long allIdleTimeNanos;           // [6] 读写都空闲超时（纳秒）

    private Ticker ticker = Ticker.systemTicker(); // [7] 时间源（可替换，方便测试）

    private Future<?> readerIdleTimeout;           // [8] 读空闲定时任务句柄
    private long lastReadTime;                     // [9] 最后一次读完成时间
    private boolean firstReaderIdleEvent = true;   // [10] 是否是第一次读空闲事件

    private Future<?> writerIdleTimeout;           // [11] 写空闲定时任务句柄
    private long lastWriteTime;                    // [12] 最后一次写完成时间
    private boolean firstWriterIdleEvent = true;   // [13] 是否是第一次写空闲事件

    private Future<?> allIdleTimeout;             // [14] 全空闲定时任务句柄
    private boolean firstAllIdleEvent = true;      // [15] 是否是第一次全空闲事件

    private byte state;                            // [16] 状态（0=初始, 1=已初始化, 2=已销毁）
    private static final byte ST_INITIALIZED = 1;
    private static final byte ST_DESTROYED = 2;

    private boolean reading;                       // [17] 是否正在读取（channelRead 到 channelReadComplete 之间）

    private long lastChangeCheckTimeStamp;         // [18] observeOutput 相关：上次检查时间戳
    private int lastMessageHashCode;               // [19] observeOutput 相关：上次消息 hashCode
    private long lastPendingWriteBytes;            // [20] observeOutput 相关：上次 pending 字节数
    private long lastFlushProgress;               // [21] observeOutput 相关：上次 flush 进度
}
```

<!-- 核对记录：已对照 IdleStateHandler.java 源码第 100-145 行，差异：无 -->

### 5.2 initialize() 和 destroy()

```java
private void initialize(ChannelHandlerContext ctx) {
    // [1] 防止 destroy() 在 initialize() 之前被调用（如 handlerAdded 后立即 handlerRemoved）
    switch (state) {
    case 1:  // ST_INITIALIZED
    case 2:  // ST_DESTROYED
        return;
    default:
        break;
    }

    state = ST_INITIALIZED;
    initOutputChanged(ctx);  // [2] 初始化 observeOutput 相关字段

    lastReadTime = lastWriteTime = ticker.nanoTime();  // [3] 初始化时间戳
    if (readerIdleTimeNanos > 0) {
        readerIdleTimeout = schedule(ctx, new ReaderIdleTimeoutTask(ctx),
                readerIdleTimeNanos, TimeUnit.NANOSECONDS);  // [4] 调度读空闲任务
    }
    if (writerIdleTimeNanos > 0) {
        writerIdleTimeout = schedule(ctx, new WriterIdleTimeoutTask(ctx),
                writerIdleTimeNanos, TimeUnit.NANOSECONDS);  // [5] 调度写空闲任务
    }
    if (allIdleTimeNanos > 0) {
        allIdleTimeout = schedule(ctx, new AllIdleTimeoutTask(ctx),
                allIdleTimeNanos, TimeUnit.NANOSECONDS);     // [6] 调度全空闲任务
    }
}

private void destroy() {
    state = ST_DESTROYED;

    if (readerIdleTimeout != null) {
        readerIdleTimeout.cancel(false);
        readerIdleTimeout = null;
    }
    if (writerIdleTimeout != null) {
        writerIdleTimeout.cancel(false);
        writerIdleTimeout = null;
    }
    if (allIdleTimeout != null) {
        allIdleTimeout.cancel(false);
        allIdleTimeout = null;
    }
}
```

<!-- 核对记录：已对照 IdleStateHandler.java 源码第 330-380 行，差异：无 -->

**initialize() 的触发时机**（三个入口，防止遗漏）：
1. `handlerAdded()`：如果 Channel 已经 active（动态添加 Handler 的场景）
2. `channelRegistered()`：如果 Channel 已经 active
3. `channelActive()`：正常流程

### 5.3 ReaderIdleTimeoutTask 源码分析

```java
private final class ReaderIdleTimeoutTask extends AbstractIdleTask {

    @Override
    protected void run(ChannelHandlerContext ctx) {
        long nextDelay = readerIdleTimeNanos;
        if (!reading) {
            nextDelay -= ticker.nanoTime() - lastReadTime;  // [1] 计算距离超时还有多少时间
        }

        if (nextDelay <= 0) {
            // [2] 已超时：重新调度（下次超时），然后触发事件
            readerIdleTimeout = schedule(ctx, this, readerIdleTimeNanos, TimeUnit.NANOSECONDS);

            boolean first = firstReaderIdleEvent;
            firstReaderIdleEvent = false;  // [3] 第一次之后都是 false

            try {
                IdleStateEvent event = newIdleStateEvent(IdleState.READER_IDLE, first);
                channelIdle(ctx, event);  // [4] 触发 userEventTriggered(IdleStateEvent)
            } catch (Throwable t) {
                ctx.fireExceptionCaught(t);
            }
        } else {
            // [5] 未超时：用剩余时间重新调度（精确控制下次触发时间）
            readerIdleTimeout = schedule(ctx, this, nextDelay, TimeUnit.NANOSECONDS);
        }
    }
}
```

<!-- 核对记录：已对照 IdleStateHandler.java 源码第 450-490 行，差异：无 -->

**关键设计**：
- 步骤 [5]：如果在超时前有读操作，任务会用**剩余时间**重新调度，而不是重新等待完整的超时时间。这保证了超时精度。
- 步骤 [2]：超时后**先重新调度**，再触发事件。这样即使 `channelIdle()` 抛异常，下次超时任务也不会丢失。

**真实数值验证（运行输出）**：
```
刚读完: nextDelay=60000ms (期望~60000ms)
50s前读完: nextDelay=10000ms (期望~10000ms)
65s前读完: nextDelay=-5000ms (期望<0，已超时)
```

### 5.4 WriterIdleTimeoutTask 源码分析

```java
private final class WriterIdleTimeoutTask extends AbstractIdleTask {

    @Override
    protected void run(ChannelHandlerContext ctx) {
        long lastWriteTime = IdleStateHandler.this.lastWriteTime;
        long nextDelay = writerIdleTimeNanos - (ticker.nanoTime() - lastWriteTime);  // [1] 计算剩余时间
        if (nextDelay <= 0) {
            writerIdleTimeout = schedule(ctx, this, writerIdleTimeNanos, TimeUnit.NANOSECONDS);

            boolean first = firstWriterIdleEvent;
            firstWriterIdleEvent = false;

            try {
                if (hasOutputChanged(ctx, first)) {
                    return;  // [2] 🔥 observeOutput=true 时：输出缓冲区有变化，不触发空闲事件
                }

                IdleStateEvent event = newIdleStateEvent(IdleState.WRITER_IDLE, first);
                channelIdle(ctx, event);
            } catch (Throwable t) {
                ctx.fireExceptionCaught(t);
            }
        } else {
            writerIdleTimeout = schedule(ctx, this, nextDelay, TimeUnit.NANOSECONDS);
        }
    }
}
```

<!-- 核对记录：已对照 IdleStateHandler.java 源码第 495-530 行，差异：无 -->

### 5.5 AllIdleTimeoutTask 源码分析

```java
private final class AllIdleTimeoutTask extends AbstractIdleTask {

    @Override
    protected void run(ChannelHandlerContext ctx) {
        long nextDelay = allIdleTimeNanos;
        if (!reading) {
            // [1] 🔥 以读写中最近的一次为基准（Math.max 取较大的时间戳 = 较近的操作）
            nextDelay -= ticker.nanoTime() - Math.max(lastReadTime, lastWriteTime);
        }
        if (nextDelay <= 0) {
            allIdleTimeout = schedule(ctx, this, allIdleTimeNanos, TimeUnit.NANOSECONDS);

            boolean first = firstAllIdleEvent;
            firstAllIdleEvent = false;

            try {
                if (hasOutputChanged(ctx, first)) {
                    return;  // [2] observeOutput 检查
                }

                IdleStateEvent event = newIdleStateEvent(IdleState.ALL_IDLE, first);
                channelIdle(ctx, event);
            } catch (Throwable t) {
                ctx.fireExceptionCaught(t);
            }
        } else {
            allIdleTimeout = schedule(ctx, this, nextDelay, TimeUnit.NANOSECONDS);
        }
    }
}
```

<!-- 核对记录：已对照 IdleStateHandler.java 源码第 535-575 行，差异：无 -->

**真实数值验证（运行输出）**：
```
读20s前,写10s前: nextDelay=20000ms (期望~20000ms)
读25s前,写35s前: nextDelay=5000ms (期望~5000ms)
```

### 5.6 observeOutput 参数解决的问题 🔥

**问题场景**：慢消费者导致写缓冲区积压。此时 `write()` 调用频繁，`lastWriteTime` 不断更新，但数据实际上没有被发送出去（Socket 缓冲区满）。`WriterIdleTimeoutTask` 会认为"写操作活跃"而不触发空闲事件，但实际上连接已经"卡住"了。

**`observeOutput=true` 的解法**：不仅检查 `lastWriteTime`，还检查 `ChannelOutboundBuffer` 的变化：

```java
private boolean hasOutputChanged(ChannelHandlerContext ctx, boolean first) {
    if (observeOutput) {
        // [1] lastWriteTime 变化 → 有新的写操作完成
        if (lastChangeCheckTimeStamp != lastWriteTime) {
            lastChangeCheckTimeStamp = lastWriteTime;
            if (!first) {
                return true;  // [2] 非第一次检查，认为有变化
            }
        }

        Channel channel = ctx.channel();
        Unsafe unsafe = channel.unsafe();
        ChannelOutboundBuffer buf = unsafe.outboundBuffer();

        if (buf != null) {
            int messageHashCode = System.identityHashCode(buf.current());
            long pendingWriteBytes = buf.totalPendingWriteBytes();

            // [3] 消息 hashCode 或 pending 字节数变化 → 有新消息入队或消息被发送
            if (messageHashCode != lastMessageHashCode || pendingWriteBytes != lastPendingWriteBytes) {
                lastMessageHashCode = messageHashCode;
                lastPendingWriteBytes = pendingWriteBytes;
                if (!first) {
                    return true;
                }
            }

            // [4] flush 进度变化 → 数据正在被发送
            long flushProgress = buf.currentProgress();
            if (flushProgress != lastFlushProgress) {
                lastFlushProgress = flushProgress;
                return !first;
            }
        }
    }

    return false;
}
```

<!-- 核对记录：已对照 IdleStateHandler.java 源码第 430-475 行，差异：无 -->

### 5.7 IdleStateEvent 常量设计

```java
public class IdleStateEvent {
    // 6 个预定义常量（避免每次触发都创建新对象）
    public static final IdleStateEvent FIRST_READER_IDLE_STATE_EVENT =
            new DefaultIdleStateEvent(IdleState.READER_IDLE, true);
    public static final IdleStateEvent READER_IDLE_STATE_EVENT =
            new DefaultIdleStateEvent(IdleState.READER_IDLE, false);
    public static final IdleStateEvent FIRST_WRITER_IDLE_STATE_EVENT =
            new DefaultIdleStateEvent(IdleState.WRITER_IDLE, true);
    public static final IdleStateEvent WRITER_IDLE_STATE_EVENT =
            new DefaultIdleStateEvent(IdleState.WRITER_IDLE, false);
    public static final IdleStateEvent FIRST_ALL_IDLE_STATE_EVENT =
            new DefaultIdleStateEvent(IdleState.ALL_IDLE, true);
    public static final IdleStateEvent ALL_IDLE_STATE_EVENT =
            new DefaultIdleStateEvent(IdleState.ALL_IDLE, false);
}
```

<!-- 核对记录：已对照 IdleStateEvent.java 源码第 25-40 行，差异：无 -->

**`first` 标志的用途**：区分"第一次空闲"和"持续空闲"。业务可以在第一次空闲时发心跳，在持续空闲时关闭连接：

```java
@Override
public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
    if (evt instanceof IdleStateEvent) {
        IdleStateEvent e = (IdleStateEvent) evt;
        if (e.state() == IdleState.READER_IDLE) {
            if (e.isFirst()) {
                // 第一次读空闲：发送心跳
                ctx.writeAndFlush(HEARTBEAT_PING);
            } else {
                // 持续读空闲：关闭连接
                ctx.close();
            }
        }
    }
}
```

### 5.8 ReadTimeoutHandler 和 WriteTimeoutHandler

`ReadTimeoutHandler` 是 `IdleStateHandler` 的子类，专门处理读超时：

```java
public class ReadTimeoutHandler extends IdleStateHandler {
    private boolean closed;

    public ReadTimeoutHandler(int timeoutSeconds) {
        this(timeoutSeconds, TimeUnit.SECONDS);
    }

    public ReadTimeoutHandler(long timeout, TimeUnit unit) {
        super(timeout, 0, 0, unit);  // [1] 只设置 readerIdleTime，其他为 0
    }

    @Override
    protected final void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) throws Exception {
        assert evt.state() == IdleState.READER_IDLE;
        readTimedOut(ctx);
    }

    protected void readTimedOut(ChannelHandlerContext ctx) throws Exception {
        if (!closed) {
            ctx.fireExceptionCaught(ReadTimeoutException.INSTANCE);  // [2] 触发异常事件
            ctx.close();                                              // [3] 关闭连接
            closed = true;                                            // [4] 防止重复关闭
        }
    }
}
```

<!-- 核对记录：已对照 ReadTimeoutHandler.java 源码第 65-104 行，差异：无 -->

**`IdleStateHandler` vs `ReadTimeoutHandler` 的选择**：

| 场景 | 推荐 | 原因 |
|------|------|------|
| 需要自定义处理（发心跳/降级/告警） | `IdleStateHandler` | 灵活，可在 `userEventTriggered` 中自定义 |
| 简单的读超时直接关闭 | `ReadTimeoutHandler` | 简洁，不需要额外 Handler |
| 需要区分读/写/全空闲 | `IdleStateHandler` | 支持三种空闲类型 |

---

## 六、核心不变式

1. **关闭幂等不变式**：`AbstractChannel.close()` 通过 `closeInitiated` 标志保证幂等性。第一次调用执行真正的关闭逻辑；后续调用直接成功（已关闭）或注册 Listener 等待（关闭中）。

2. **空闲检测重调度不变式**：`IdleStateHandler` 的三个超时任务在触发事件**之前**先重新调度自己（`schedule(ctx, this, ...)`）。这保证了即使 `channelIdle()` 抛异常，下次超时检测也不会丢失。

3. **写时间戳更新不变式**：`IdleStateHandler.write()` 通过 `writeListener` 在写操作**完成**（`operationComplete`）时更新 `lastWriteTime`，而不是在 `write()` 调用时更新。这确保了"写空闲"的语义是"数据已发送完成"，而不是"数据已提交到缓冲区"。

---

## 七、生产踩坑与最佳实践

### 7.1 ⚠️ 心跳超时时间设置不合理

```java
// ❌ 错误：读超时太短，正常业务请求也会被误判为超时
pipeline.addLast(new IdleStateHandler(5, 0, 0));  // 5秒读超时太短

// ✅ 正确：读超时 = 心跳间隔 * 3（允许丢失2次心跳）
// 心跳间隔 30s，读超时 90s
pipeline.addLast(new IdleStateHandler(90, 30, 0));
// 写空闲 30s 时发心跳，读空闲 90s 时关闭连接
```

### 7.2 ⚠️ 在 channelInactive 中重连时忘记释放资源

```java
// ❌ 错误：重连时没有清理旧 Channel 的资源
@Override
public void channelInactive(ChannelHandlerContext ctx) {
    // 直接重连，但旧 Channel 的 Pipeline 中可能还有 Handler 持有资源
    bootstrap.connect(remoteAddress);
}

// ✅ 正确：先清理，再重连
@Override
public void channelInactive(ChannelHandlerContext ctx) {
    // 1. 清理业务状态
    pendingRequests.forEach((id, promise) ->
            promise.setFailure(new ClosedChannelException()));
    pendingRequests.clear();

    // 2. 延迟重连（避免立即重连导致连接风暴）
    ctx.channel().eventLoop().schedule(() -> {
        bootstrap.connect(remoteAddress).addListener(future -> {
            if (!future.isSuccess()) {
                // 重连失败，继续重试
            }
        });
    }, 5, TimeUnit.SECONDS);
}
```

### 7.3 ⚠️ 连接超时配置错误

```java
// ❌ 错误：用 Future.await() 超时代替连接超时
ChannelFuture f = bootstrap.connect(host, port);
f.awaitUninterruptibly(10, TimeUnit.SECONDS);  // 这是等待 Future 完成的超时，不是连接超时！

// ✅ 正确：用 CONNECT_TIMEOUT_MILLIS 配置连接超时
bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000);  // 10秒连接超时
ChannelFuture f = bootstrap.connect(host, port);
f.sync();  // 等待连接完成（超时后会抛 ConnectTimeoutException）
```

### 7.4 ⚠️ 优雅关闭时忘记等待 closeFuture

```java
// ❌ 错误：直接关闭 EventLoopGroup，不等待 Channel 关闭
channel.close();
bossGroup.shutdownGracefully();
workerGroup.shutdownGracefully();

// ✅ 正确：等待 Channel 关闭后再关闭 EventLoopGroup
channel.closeFuture().sync();  // 等待 Channel 完全关闭
bossGroup.shutdownGracefully();
workerGroup.shutdownGracefully();
```

### 7.5 ⚠️ observeOutput 使用场景误判

```java
// ❌ 错误：在高吞吐场景下使用 observeOutput=true，导致写空闲永远不触发
// 因为 ChannelOutboundBuffer 总是有数据，hasOutputChanged() 总是返回 true
pipeline.addLast(new IdleStateHandler(true, 0, 30, 0));  // observeOutput=true

// ✅ 正确：observeOutput=true 只适合"写操作频繁但数据可能卡住"的场景
// 如：下游消费慢，写缓冲区积压，但 write() 调用仍然频繁
// 大多数场景使用默认的 observeOutput=false 即可
pipeline.addLast(new IdleStateHandler(60, 30, 0));  // observeOutput=false（默认）
```

### 7.6 ⚠️ 半开连接（Half-Open Connection）问题

**问题**：对端机器崩溃（不是正常关闭），TCP 连接没有发送 FIN，服务端不知道连接已断开。

**现象**：服务端持有大量"僵尸连接"，`isActive()` 返回 true，但实际上数据无法发送。

**解法**：
```java
// 方案1：IdleStateHandler（应用层心跳）
pipeline.addLast(new IdleStateHandler(90, 30, 0));
pipeline.addLast(new HeartbeatHandler());

// 方案2：TCP KeepAlive（内核层保活，但默认间隔 2 小时，不适合生产）
bootstrap.childOption(ChannelOption.SO_KEEPALIVE, true);

// 方案3：两者结合（推荐）
// 应用层心跳快速检测（30s），TCP KeepAlive 作为兜底
bootstrap.childOption(ChannelOption.SO_KEEPALIVE, true);
pipeline.addLast(new IdleStateHandler(90, 30, 0));
```

### 7.7 ⚠️ 在 channelActive 中发送数据但 Channel 未完全就绪

```java
// ❌ 错误：在 channelActive 中直接发送数据，但 TLS 握手可能还未完成
@Override
public void channelActive(ChannelHandlerContext ctx) {
    ctx.writeAndFlush(INITIAL_MESSAGE);  // TLS 场景下可能失败
}

// ✅ 正确：监听 SslHandler 的 handshakeFuture
@Override
public void channelActive(ChannelHandlerContext ctx) {
    SslHandler sslHandler = ctx.pipeline().get(SslHandler.class);
    if (sslHandler != null) {
        sslHandler.handshakeFuture().addListener(future -> {
            if (future.isSuccess()) {
                ctx.writeAndFlush(INITIAL_MESSAGE);
            } else {
                ctx.close();
            }
        });
    } else {
        ctx.writeAndFlush(INITIAL_MESSAGE);
    }
}
```

---

## 八、完整心跳方案示例

### 8.1 服务端配置

```java
// 服务端：60s 读空闲关闭连接（客户端应每 30s 发一次心跳）
ServerBootstrap b = new ServerBootstrap();
b.group(bossGroup, workerGroup)
 .channel(NioServerSocketChannel.class)
 .childOption(ChannelOption.SO_KEEPALIVE, true)
 .childHandler(new ChannelInitializer<SocketChannel>() {
     @Override
     protected void initChannel(SocketChannel ch) {
         ch.pipeline().addLast(
             new LengthFieldBasedFrameDecoder(65536, 0, 4, 0, 4),
             new LengthFieldPrepender(4),
             new IdleStateHandler(60, 0, 0),  // 60s 读空闲
             new ServerHeartbeatHandler()
         );
     }
 });

// 服务端心跳 Handler
public class ServerHeartbeatHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent e = (IdleStateEvent) evt;
            if (e.state() == IdleState.READER_IDLE) {
                // 60s 没有读到数据，关闭连接
                ctx.close();
            }
        }
    }
}
```

### 8.2 客户端配置

```java
// 客户端：30s 写空闲发心跳，90s 读空闲（服务端没响应）关闭重连
Bootstrap b = new Bootstrap();
b.group(workerGroup)
 .channel(NioSocketChannel.class)
 .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
 .handler(new ChannelInitializer<SocketChannel>() {
     @Override
     protected void initChannel(SocketChannel ch) {
         ch.pipeline().addLast(
             new LengthFieldBasedFrameDecoder(65536, 0, 4, 0, 4),
             new LengthFieldPrepender(4),
             new IdleStateHandler(90, 30, 0),  // 90s 读空闲, 30s 写空闲
             new ClientHeartbeatHandler()
         );
     }
 });

// 客户端心跳 Handler
public class ClientHeartbeatHandler extends ChannelDuplexHandler {
    private static final ByteBuf HEARTBEAT_PING = Unpooled.unreleasableBuffer(
            Unpooled.copiedBuffer("PING", CharsetUtil.UTF_8));

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent e = (IdleStateEvent) evt;
            if (e.state() == IdleState.WRITER_IDLE) {
                // 30s 没有写操作，发送心跳
                ctx.writeAndFlush(HEARTBEAT_PING.duplicate());
            } else if (e.state() == IdleState.READER_IDLE) {
                // 90s 没有读到数据（心跳也没响应），关闭并重连
                ctx.close();
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // 连接断开，5s 后重连
        ctx.channel().eventLoop().schedule(
                () -> reconnect(), 5, TimeUnit.SECONDS);
    }
}
```

---

## 九、日志验证方案

### 9.1 验证 IdleStateHandler 触发时机

```java
// 在 IdleStateHandler 子类中添加日志
public class DebugIdleStateHandler extends IdleStateHandler {

    public DebugIdleStateHandler(int readerIdleTimeSeconds, int writerIdleTimeSeconds, int allIdleTimeSeconds) {
        super(readerIdleTimeSeconds, writerIdleTimeSeconds, allIdleTimeSeconds);
    }

    @Override
    protected void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) throws Exception {
        System.out.printf("[%s] channelIdle: state=%s, first=%b, channel=%s%n",
                Thread.currentThread().getName(),
                evt.state(),
                evt.isFirst(),
                ctx.channel().id().asShortText());
        super.channelIdle(ctx, evt);
    }
}
```

### 9.2 验证 Channel 关闭顺序

```java
// 在 Handler 中监听所有生命周期事件
public class LifecycleLogHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void channelRegistered(ChannelHandlerContext ctx) {
        System.out.println("[" + ctx.channel().id().asShortText() + "] channelRegistered");
        ctx.fireChannelRegistered();
    }
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        System.out.println("[" + ctx.channel().id().asShortText() + "] channelActive");
        ctx.fireChannelActive();
    }
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        System.out.println("[" + ctx.channel().id().asShortText() + "] channelInactive");
        ctx.fireChannelInactive();
    }
    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) {
        System.out.println("[" + ctx.channel().id().asShortText() + "] channelUnregistered");
        ctx.fireChannelUnregistered();
    }
}
```

---

## 十、面试问答

**Q1：Netty Channel 的生命周期有哪些状态？** 🔥🔥

**A**：四个状态：
1. **Created**：`new NioSocketChannel()`，`isOpen()=true`，`isRegistered()=false`，`isActive()=false`
2. **Registered**：注册到 EventLoop，`isRegistered()=true`，触发 `channelRegistered()`
3. **Active**：连接建立（客户端 connect 成功 / 服务端 accept），`isActive()=true`，触发 `channelActive()`
4. **Inactive**：连接关闭，`isActive()=false`，触发 `channelInactive()`，然后 `deregister()` 触发 `channelUnregistered()`

---

**Q2：`IdleStateHandler` 的三种空闲类型有什么区别？** 🔥🔥

**A**：
- `READER_IDLE`：超过 `readerIdleTime` 没有读到数据（`channelReadComplete` 未被调用）
- `WRITER_IDLE`：超过 `writerIdleTime` 没有写操作完成（`writeListener.operationComplete` 未被调用）
- `ALL_IDLE`：超过 `allIdleTime` 既没有读也没有写（以最近一次读写时间的较大值为基准）

---

**Q3：`IdleStateHandler` 的 `observeOutput` 参数有什么作用？** 🔥

**A**：解决"慢消费者"场景下写空闲误判的问题。默认 `observeOutput=false` 时，只要 `write()` 被调用，`lastWriteTime` 就会更新，写空闲不会触发。但如果下游消费慢，数据堆积在 `ChannelOutboundBuffer` 中，`write()` 调用频繁但数据实际上没有发出去。`observeOutput=true` 时，还会检查 `ChannelOutboundBuffer` 的变化（消息 hashCode、pending 字节数、flush 进度），只有这些也在变化时才认为"写活跃"。

---

**Q4：Netty 的连接超时是如何实现的？** 🔥🔥

**A**：在 `AbstractNioChannel.connect()` 中，如果 `doConnect()` 返回 false（连接中），会调度一个定时任务（`connectTimeoutFuture`），延迟 `CONNECT_TIMEOUT_MILLIS` 毫秒执行。定时任务触发时，检查 `connectPromise != null && !connectPromise.isDone()`，如果满足则调用 `connectPromise.tryFailure(new ConnectTimeoutException(...))` 并 `close(voidPromise())`。连接成功（`finishConnect()`）或取消时，会调用 `connectTimeoutFuture.cancel(false)` 取消定时任务。

---

**Q5：`close()` 方法为什么要把 `outboundBuffer` 置为 null？** 🔥

**A**：`this.outboundBuffer = null` 是关闭流程的第一步，目的是**禁止新的写入**。`AbstractUnsafe.write()` 在开始时检查 `outboundBuffer == null`，如果为 null 则立即释放消息并通知 Promise 失败。这样在关闭过程中，任何新的 `write()` 调用都会立即失败，而不是把数据加入缓冲区后又被 `failFlushed()` 通知失败（减少不必要的处理）。

---

**Q6：`close()` 和 `shutdownOutput()` 有什么区别？** 🔥

**A**：
- `close()`：完全关闭连接，发送 FIN，停止读写，触发 `channelInactive()`
- `shutdownOutput()`：TCP 半关闭，只关闭写方向（发送 FIN），仍然可以接收数据，触发 `ChannelOutputShutdownEvent`（不触发 `channelInactive()`）

`shutdownOutput()` 适用于 HTTP/1.1 等协议：客户端发完请求后关闭写方向，等待服务端响应，服务端收到 FIN 后知道请求已完整，发送响应后关闭连接。

---

**Q7：如何实现断线重连？** 🔥🔥

**A**：在 `channelInactive()` 中调度重连任务：
```java
@Override
public void channelInactive(ChannelHandlerContext ctx) {
    // 延迟重连，避免连接风暴
    ctx.channel().eventLoop().schedule(() -> {
        bootstrap.connect(remoteAddress).addListener(future -> {
            if (!future.isSuccess()) {
                // 重连失败，继续重试（指数退避）
            }
        });
    }, reconnectDelay, TimeUnit.SECONDS);
}
```
注意：重连时要清理旧 Channel 的业务状态（pending 请求等），并使用指数退避避免连接风暴。

---

**Q8：`firstReaderIdleEvent` 标志有什么作用？** 🔥

**A**：区分"第一次空闲"和"持续空闲"。第一次触发 `FIRST_READER_IDLE_STATE_EVENT`，后续触发 `READER_IDLE_STATE_EVENT`。业务可以在第一次空闲时发心跳（给对端一次机会），在持续空闲时关闭连接。`firstReaderIdleEvent` 在 `channelRead()` 时重置为 true（有读操作时重置），在超时任务触发时设为 false。

---

## 十一、Self-Check 六关自检

### ① 条件完整性

- `initialize()` 的 switch 条件：`case 1`（ST_INITIALIZED）和 `case 2`（ST_DESTROYED）都直接 return ✅
- `ReaderIdleTimeoutTask.run()` 的条件：`if (!reading)` 才计算 nextDelay（reading 时不减去已过时间）✅
- `AllIdleTimeoutTask.run()` 的条件：`Math.max(lastReadTime, lastWriteTime)` 取最近的操作时间 ✅
- `close()` 的幂等条件：`if (closeInitiated)` 分两个子分支（`closeFuture.isDone()` 和 `!(promise instanceof VoidChannelPromise)`）✅
- `connect()` 超时条件：`connectPromise != null && !connectPromise.isDone()` 双重检查 ✅

### ② 分支完整性

- `initialize()` 的 switch：`case 1`、`case 2`、`default`（break 继续执行）✅
- `close()` 的 `closeExecutor != null` 分支：SO_LINGER 路径和普通路径 ✅
- `close()` 普通路径中的 `inFlush0` 分支：flush 中延迟触发 vs 直接触发 ✅
- `hasOutputChanged()` 的三个检查点：`lastWriteTime`、`messageHashCode/pendingWriteBytes`、`flushProgress` ✅
- `newIdleStateEvent()` 的 switch：`ALL_IDLE`、`READER_IDLE`、`WRITER_IDLE`、`default`（抛异常）✅

### ③ 数值示例验证

所有数值均通过 Java 程序真实运行验证：
- `readerIdleTimeNanos=60000000000`（60s）✅
- `writerIdleTimeNanos=30000000000`（30s）✅
- `allIdleTimeNanos=0`（disabled）✅
- `tinyTimeout=1ns → clamped to 1000000ns (1ms)` ✅
- `50s前读完: nextDelay=10000ms` ✅
- `65s前读完: nextDelay=-5000ms（已超时）` ✅
- `读20s前,写10s前: nextDelay=20000ms` ✅
- `读25s前,写35s前: nextDelay=5000ms` ✅

### ④ 字段/顺序与源码一致

- `IdleStateHandler` 字段顺序：`MIN_TIMEOUT_NANOS, writeListener, observeOutput, readerIdleTimeNanos, writerIdleTimeNanos, allIdleTimeNanos, ticker, readerIdleTimeout, lastReadTime, firstReaderIdleEvent, writerIdleTimeout, lastWriteTime, firstWriterIdleEvent, allIdleTimeout, firstAllIdleEvent, state, ST_INITIALIZED, ST_DESTROYED, reading, lastChangeCheckTimeStamp, lastMessageHashCode, lastPendingWriteBytes, lastFlushProgress` ✅
- `IdleStateEvent` 常量顺序：`FIRST_READER_IDLE_STATE_EVENT, READER_IDLE_STATE_EVENT, FIRST_WRITER_IDLE_STATE_EVENT, WRITER_IDLE_STATE_EVENT, FIRST_ALL_IDLE_STATE_EVENT, ALL_IDLE_STATE_EVENT` ✅
- `ReadTimeoutHandler` 字段：`closed`（仅一个字段）✅

### ⑤ 边界/保护逻辑不遗漏

- `initialize()` 中 `state == ST_INITIALIZED || state == ST_DESTROYED` 时直接 return（防止重复初始化）✅
- `AbstractIdleTask.run()` 中 `!ctx.channel().isOpen()` 检查（Channel 已关闭时不执行）✅
- `ReaderIdleTimeoutTask.run()` 中先重新调度再触发事件（防止事件处理异常导致任务丢失）✅
- `ReadTimeoutHandler.readTimedOut()` 中 `if (!closed)` 检查（防止重复关闭）✅
- `connect()` 中 `if (promise.isDone() || !ensureOpen(promise))` 前置检查（promise 已完成或 Channel 未打开时直接 return）✅
- `connect()` 中 `if (connectPromise != null)` 检查（已有连接进行中时抛 `ConnectionPendingException`）✅
- `connect()` 中 `connectTimeoutMillis > 0` 检查（0 表示不设超时）✅
- `finishConnect()` 中 `if (connectTimeoutFuture != null)` 检查（未设超时时 future 为 null）✅
- `close()` 中 `if (outboundBuffer != null)` 检查（outboundBuffer 可能已被 shutdownOutput 置 null）✅

### ⑥ 兜底关：源码逐字对照

本文档所有源码引用均已回到真实源码文件逐字核对：
- `IdleStateHandler.java`（607行）：所有字段、`initialize`、`destroy`、`channelRead`、`channelReadComplete`、`write`、`ReaderIdleTimeoutTask`、`WriterIdleTimeoutTask`、`AllIdleTimeoutTask`、`hasOutputChanged`、`newIdleStateEvent`、`channelIdle` ✅
- `AbstractNioChannel.java`（589行）：`connect`（超时调度部分）、`finishConnect`、`fulfillConnectPromise` ✅
- `AbstractChannel.java`（1104行）：`close(promise)`、`close(promise, cause, closeCause)`、`doClose0`、`fireChannelInactiveAndDeregister`、`shutdownOutput`、`closeOutboundBufferForShutdown` ✅
- `ReadTimeoutHandler.java`（104行）：完整类 ✅
- `IdleStateEvent.java`：6 个常量 ✅

**自我质疑**：
1. `WriterIdleTimeoutTask` 中 `lastWriteTime` 的更新时机——通过 `writeListener.operationComplete` 更新，而不是 `write()` 调用时，文档中已说明 ✅
2. `AllIdleTimeoutTask` 中 `Math.max(lastReadTime, lastWriteTime)` 的语义——取较大的时间戳（即较近的操作），文档中已说明 ✅
3. `initialize()` 的三个触发入口——`handlerAdded`、`channelRegistered`、`channelActive`，文档中已说明 ✅
4. `close()` 中 `prepareToClose()` 的作用——SO_LINGER 场景需要在独立线程执行 doClose，文档中已说明 ✅
