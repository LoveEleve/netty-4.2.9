# 第23章：生产调优与参数模板

<!-- 核心源码文件：
  transport/src/main/java/io/netty/channel/DefaultChannelConfig.java
  transport/src/main/java/io/netty/channel/WriteBufferWaterMark.java
  transport/src/main/java/io/netty/channel/socket/DefaultServerSocketChannelConfig.java
  buffer/src/main/java/io/netty/buffer/PooledByteBufAllocator.java
  common/src/main/java/io/netty/util/NetUtil.java
-->

## 1. 为什么需要调优？默认值的设计哲学

Netty 的默认参数遵循一个原则：**在通用场景下安全，但不一定最优**。

```
默认值的设计目标：
┌─────────────────────────────────────────────────────────────┐
│  1. 安全优先：不会因为默认值导致 OOM 或数据丢失              │
│  2. 通用性：在 1C1G 到 64C256G 的机器上都能跑起来           │
│  3. 保守：宁可性能差一点，也不要因为激进参数导致故障         │
└─────────────────────────────────────────────────────────────┘
```

**调优的本质**：根据你的业务特征（消息大小、并发量、延迟要求、内存预算），把"通用安全"的默认值调整为"场景最优"的值。

---

## 2. 参数全景图：五大维度

```mermaid
mindmap
  root((Netty 调优))
    线程模型
      boss 线程数
      worker 线程数
      EventLoop 绑定策略
    网络层参数
      SO_BACKLOG
      SO_REUSEADDR
      TCP_NODELAY
      SO_KEEPALIVE
      SO_SNDBUF / SO_RCVBUF
    内存与缓冲
      ALLOCATOR 分配器选择
      WRITE_BUFFER_WATER_MARK
      RecvByteBufAllocator
      maxMessagesPerRead
    超时与保活
      CONNECT_TIMEOUT_MILLIS
      IdleStateHandler
      SslHandler 握手超时
    可观测性
      ResourceLeakDetector 等级
      日志级别
      JFR 事件
```

---

## 3. 线程模型调优

### 3.1 boss/worker 线程数


```java
// 默认值：CPU * 2（由 NettyRuntime.availableProcessors() * 2 决定）
EventLoopGroup bossGroup   = new NioEventLoopGroup(1);   // ⭐ boss 通常只需要 1 个
EventLoopGroup workerGroup = new NioEventLoopGroup();    // 默认 CPU * 2
```

**boss 线程数**：

| 场景 | 推荐值 | 原因 |
|------|--------|------|
| 单端口监听 | **1** | accept 是串行的，1 个线程足够；多了浪费 |
| 多端口监听（如 HTTP + HTTPS + gRPC） | **端口数** | 每个端口一个 boss，避免 accept 竞争 |
| 极高连接速率（>10万/s） | **2~4** | 单线程 accept 可能成为瓶颈 |

**worker 线程数**：

| 场景 | 推荐值 | 原因 |
|------|--------|------|
| IO 密集型（转发代理、网关） | **CPU * 2**（默认） | IO 等待多，多线程提高利用率 |
| CPU 密集型（加解密、压缩） | **CPU 核数** | 避免线程切换开销 |
| 混合型（业务逻辑在 EventLoop 中） | **CPU * 2 ~ CPU * 4** | 根据压测结果调整 |
| 业务逻辑在独立线程池 | **CPU * 2**（默认） | EventLoop 只做 IO，不做业务 |

> 🔥 **面试常考**：为什么 boss 线程通常只需要 1 个？
> **答**：`accept()` 系统调用是串行的——同一时刻只有一个线程能从 ServerSocket 的 accept 队列取连接。多个 boss 线程会竞争同一个 ServerSocket，反而增加锁竞争。只有在极高连接速率（>10万/s）时，单线程 accept 才会成为瓶颈。

### 3.2 Epoll vs NIO 的线程数差异

```java
// Linux 生产环境推荐：使用 Epoll Transport
EventLoopGroup bossGroup   = new EpollEventLoopGroup(1);
EventLoopGroup workerGroup = new EpollEventLoopGroup();  // 同样 CPU * 2

// io_uring（实验性，Linux 5.1+）
EventLoopGroup bossGroup   = new IOUringEventLoopGroup(1);
EventLoopGroup workerGroup = new IOUringEventLoopGroup();
```

> ⚠️ **生产踩坑**：从 NIO 切换到 Epoll 只需要换 `EventLoopGroup` 和 `Channel` 类型，其他代码不变。但切换前必须确认 `Epoll.isAvailable()` 返回 true，否则会抛出 `UnsatisfiedLinkError`。

### 3.3 4.2 新特性：弹性线程（AutoScaling）

```java
// 4.2 新增：基于负载的 EventLoop 动态伸缩
// 构造函数签名：MultiThreadIoEventLoopGroup(int nThreads, Executor executor,
//                                           EventExecutorChooserFactory chooserFactory,
//                                           IoHandlerFactory ioHandlerFactory)
EventLoopGroup workerGroup = new MultiThreadIoEventLoopGroup(
    0,                                                    // nThreads=0 表示使用默认值（CPU * 2）
    (Executor) null,                                      // executor=null 表示使用默认线程工厂
    AutoScalingEventExecutorChooserFactory.INSTANCE,      // 弹性伸缩选择器
    NioIoHandler.newFactory()                             // IoHandler 工厂
);
```

**适用场景**：流量波动大（白天高峰 / 夜间低谷），避免夜间空转浪费 CPU。

---

## 4. 网络层参数调优

### 4.1 SO_BACKLOG：连接排队长度


```java
// 默认值：读取 /proc/sys/net/core/somaxconn
// Netty 代码中各平台的硬编码默认值（文件不存在时使用）：
//   Linux：4096（非 Windows/macOS 统一使用 4096，再读文件覆盖）
//   Windows：200
//   macOS：128
// 实际生效值 = /proc/sys/net/core/somaxconn 文件的值（如果文件存在）
serverBootstrap.option(ChannelOption.SO_BACKLOG, 1024);
```

**SO_BACKLOG 的含义**：

```
TCP 三次握手完成后，连接进入 accept 队列（全连接队列）
SO_BACKLOG 控制这个队列的最大长度

连接风暴时：
  accept 队列满 → 新连接被丢弃（Linux 默认）或 RST（取决于 tcp_abort_on_overflow）
```

**调优建议**：

| 场景 | 推荐值 | 原因 |
|------|--------|------|
| 普通服务 | **1024** | 比 Linux 旧默认值（128）大，应对短暂连接风暴 |
| 高并发网关 | **4096~8192** | 连接速率高，队列需要更大缓冲 |
| 微服务内部调用 | **512** | 连接数可控，不需要太大 |

> ⚠️ **生产踩坑**：`SO_BACKLOG` 只是 Netty 的建议值，实际生效值受 `/proc/sys/net/core/somaxconn` 限制（取两者的最小值）。如果 `somaxconn=128` 而你设置了 `SO_BACKLOG=1024`，实际只有 128。**必须同时调整系统参数**：
> ```bash
> echo 4096 > /proc/sys/net/core/somaxconn
> echo 4096 > /proc/sys/net/ipv4/tcp_max_syn_backlog
> ```

### 4.2 TCP_NODELAY：禁用 Nagle 算法

```java
// 默认值：false（即 Nagle 算法开启）
// 推荐：几乎所有场景都应该设置为 true
serverBootstrap.childOption(ChannelOption.TCP_NODELAY, true);
```

**Nagle 算法的问题**：

```
Nagle 算法：小包合并发送（等待 ACK 或凑够 MSS 再发）
问题：在请求-响应模式下，会增加 40ms 延迟（与 TCP Delayed ACK 叠加）

典型场景：
  客户端发送 HTTP 请求头（小包）
  Nagle 等待 ACK 才发送请求体
  服务端 Delayed ACK 等 40ms 才发 ACK
  → 额外 40ms 延迟！
```

> 🔥 **面试常考**：什么时候不应该设置 `TCP_NODELAY=true`？
> **答**：批量数据传输场景（如文件传输、大数据流），Nagle 算法可以减少小包数量，提高网络利用率。但对于 RPC、HTTP 等请求-响应模式，必须设置 `TCP_NODELAY=true`。

### 4.3 SO_REUSEADDR：端口复用

```java
// 默认值：false
// 推荐：服务端必须设置为 true
serverBootstrap.option(ChannelOption.SO_REUSEADDR, true);
```

**作用**：允许在 `TIME_WAIT` 状态的端口上重新绑定。服务重启时，如果不设置，可能遇到 `Address already in use` 错误（需要等待 2MSL = 60~120s）。

### 4.4 SO_KEEPALIVE：TCP 保活

```java
// 默认值：false
// 注意：这是 TCP 层的保活，不是业务层心跳
serverBootstrap.childOption(ChannelOption.SO_KEEPALIVE, true);
```

**TCP 保活 vs 业务心跳**：

| 维度 | TCP SO_KEEPALIVE | 业务层心跳（IdleStateHandler） |
|------|-----------------|-------------------------------|
| 检测间隔 | 默认 2 小时（由内核参数控制） | 可配置（通常 30s~60s） |
| 检测粒度 | TCP 连接是否存活 | 业务是否正常（可携带业务数据） |
| 可控性 | 依赖内核参数 | 完全可控 |
| 推荐 | 作为兜底 | ✅ 生产首选 |

> ⚠️ **生产踩坑**：`SO_KEEPALIVE` 的默认检测间隔是 2 小时（`tcp_keepalive_time`），对于需要快速检测死连接的场景完全不够用。**必须配合 `IdleStateHandler` 使用业务层心跳**。

### 4.5 SO_SNDBUF / SO_RCVBUF：发送/接收缓冲区

```java
// 默认值：由操作系统决定（通常 4KB~128KB）
// 高吞吐场景调大
serverBootstrap.childOption(ChannelOption.SO_SNDBUF, 256 * 1024);  // 256KB
serverBootstrap.childOption(ChannelOption.SO_RCVBUF, 256 * 1024);  // 256KB
```

> ⚠️ **生产踩坑**：设置 `SO_SNDBUF` 后，Netty 的 `WriteBufferWaterMark` 也应该相应调大，否则 TCP 发送缓冲区大了，但 Netty 的写队列水位线还是 64KB，会频繁触发背压。

---

## 5. 内存与缓冲调优

### 5.1 ALLOCATOR：内存分配器选择


```java
// 默认值：ByteBufAllocator.DEFAULT
// = PooledByteBufAllocator（堆外内存）
// 可选值：
//   PooledByteBufAllocator.DEFAULT    - 传统池化分配器（稳定，生产主流）
//   new AdaptiveByteBufAllocator()    - 自适应分配器（4.2 新增，实验性）
//   UnpooledByteBufAllocator.DEFAULT  - 非池化（调试用，生产不推荐）

// 方式1：全局设置（影响所有 Channel）
serverBootstrap.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);

// 方式2：系统属性（影响 ByteBufAllocator.DEFAULT）
// -Dio.netty.allocator.type=pooled    （默认）
// -Dio.netty.allocator.type=unpooled
// -Dio.netty.allocator.type=adaptive  （4.2 新增）
```

**三种分配器对比**：

| 分配器 | 适用场景 | 优点 | 缺点 |
|--------|---------|------|------|
| `PooledByteBufAllocator` | 生产环境（默认） | 稳定、成熟、GC 压力低 | 内存碎片化，固定 Chunk 大小 |
| `AdaptiveByteBufAllocator` | 网络 IO 密集型（4.2 实验性） | 自适应 Chunk 大小，碎片化更低 | `@UnstableApi`，API 可能变化 |
| `UnpooledByteBufAllocator` | 调试、低并发 | 简单，无内存泄漏风险 | 每次分配都 GC，高并发下 GC 压力大 |

**PooledByteBufAllocator 关键参数**：


```
-Dio.netty.allocator.numHeapArenas=N    # 堆内 Arena 数量，默认 min(CPU*2, maxMemory/chunkSize/2/3)
-Dio.netty.allocator.numDirectArenas=N  # 堆外 Arena 数量，同上
-Dio.netty.allocator.pageSize=8192      # Page 大小，默认 8KB
-Dio.netty.allocator.maxOrder=9         # Chunk = pageSize << maxOrder = 8KB << 9 = 4MB
-Dio.netty.allocator.smallCacheSize=256 # ThreadCache 小对象缓存数量，默认 256
-Dio.netty.allocator.normalCacheSize=64 # ThreadCache 普通对象缓存数量，默认 64
-Dio.netty.allocator.maxCachedBufferCapacity=32768  # ThreadCache 最大缓存 ByteBuf 大小，默认 32KB
```

**调优建议**：

```bash
# 高并发场景：增大 Arena 数量，减少竞争
-Dio.netty.allocator.numDirectArenas=32

# 大消息场景（平均消息 > 32KB）：增大 maxOrder，让 Chunk 更大
-Dio.netty.allocator.maxOrder=11  # Chunk = 8KB << 11 = 16MB

# 内存紧张场景：减小 ThreadCache，降低内存占用
-Dio.netty.allocator.smallCacheSize=64
-Dio.netty.allocator.normalCacheSize=16
```

### 5.2 WRITE_BUFFER_WATER_MARK：写水位线（背压核心）


```java
// 默认值：low=32KB，high=64KB
// 含义：
//   写队列积压 > 64KB → Channel.isWritable() = false（触发背压）
//   写队列积压 < 32KB → Channel.isWritable() = true（恢复可写）

// 调优：根据消息大小和下游处理速度调整
serverBootstrap.childOption(
    ChannelOption.WRITE_BUFFER_WATER_MARK,
    new WriteBufferWaterMark(
        512 * 1024,   // low：512KB
        1024 * 1024   // high：1MB
    )
);
```

**水位线调优原则**：

```
high = 单次最大消息大小 × 预期积压消息数
low  = high / 2

示例：
  消息大小 = 64KB，允许积压 16 条
  → high = 64KB × 16 = 1MB
  → low  = 512KB
```

**背压使用模式**：

```java
// 正确的背压处理模式
@Override
public void channelWritabilityChanged(ChannelHandlerContext ctx) {
    if (ctx.channel().isWritable()) {
        // 恢复可写，重新开始读取上游数据
        upstream.resumeRead();
    } else {
        // 写队列积压，暂停读取上游数据（防止 OOM）
        upstream.pauseRead();
    }
}
```

> 🔥 **面试常考**：水位线设置太小会怎样？太大会怎样？
> - **太小**：频繁触发背压，吞吐量下降，CPU 在 `isWritable()` 检查上浪费
> - **太大**：积压过多数据，内存占用高，极端情况下 OOM

### 5.3 RecvByteBufAllocator：接收缓冲区分配策略

```java
// 默认值：AdaptiveRecvByteBufAllocator（自适应，初始 2048，最小 64，最大 65536）
// 调优：固定大小（消息大小非常稳定时）
serverBootstrap.childOption(
    ChannelOption.RECVBUF_ALLOCATOR,
    new FixedRecvByteBufAllocator(65536)  // 固定 64KB
);

// 或调整自适应分配器的范围
serverBootstrap.childOption(
    ChannelOption.RECVBUF_ALLOCATOR,
    new AdaptiveRecvByteBufAllocator(512, 4096, 65536)  // min=512, initial=4096, max=64KB
);
```

**自适应分配器的扩缩容策略**（委托给 `AdaptiveCalculator`）：
- **扩容**：`size >= nextSize` → 跳 4 档扩容（`INDEX_INCREMENT = 4`，激进）
- **缩容**：`size <= SIZE_TABLE[max(0, index - INDEX_DECREMENT)]` 连续满足 **2 次** → 缩 1 档（`INDEX_DECREMENT = 1`，保守）

> ⚠️ **生产踩坑**：如果消息大小变化剧烈（如混合小包和大包），`AdaptiveRecvByteBufAllocator` 会频繁扩缩容，导致内存分配抖动。此时应该设置合理的 `max` 值，或使用 `FixedRecvByteBufAllocator`。

---

## 6. 超时与保活调优

### 6.1 CONNECT_TIMEOUT_MILLIS：连接超时


```java
// 默认值：30000ms（30s）
// 客户端连接超时
bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000);  // 3s
```

**调优建议**：

| 场景 | 推荐值 | 原因 |
|------|--------|------|
| 内网 RPC | **1000~3000ms** | 内网延迟低，超时应该短 |
| 跨机房 | **5000~10000ms** | 跨机房延迟高，需要更长超时 |
| 公网 | **10000~30000ms** | 公网不稳定，保守设置 |

### 6.2 IdleStateHandler：空闲检测与心跳

```java
// 标准心跳配置
pipeline.addLast(new IdleStateHandler(
    60,   // readerIdleTime：60s 没收到数据 → READER_IDLE
    30,   // writerIdleTime：30s 没发送数据 → WRITER_IDLE
    0,    // allIdleTime：0 = 禁用
    TimeUnit.SECONDS
));
pipeline.addLast(new HeartbeatHandler());

// HeartbeatHandler 示例
public class HeartbeatHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent e = (IdleStateEvent) evt;
            if (e.state() == IdleState.WRITER_IDLE) {
                // 30s 没发数据，主动发心跳
                ctx.writeAndFlush(HEARTBEAT_PING);
            } else if (e.state() == IdleState.READER_IDLE) {
                // 60s 没收到数据，认为连接已死，关闭
                ctx.close();
            }
        }
    }
}
```

**心跳参数设计原则**：

```
readerIdleTime = writerIdleTime × 2（给对端两次心跳机会）
writerIdleTime = 业务超时 / 3（确保在业务超时前发出心跳）

示例：业务超时 90s
  writerIdleTime = 30s（每 30s 发一次心跳）
  readerIdleTime = 60s（60s 没收到任何数据则关闭）
```

### 6.3 SslHandler 超时配置

```java
SslHandler sslHandler = sslCtx.newHandler(ch.alloc());
sslHandler.setHandshakeTimeoutMillis(5000);          // 握手超时 5s（默认 10s）
sslHandler.setCloseNotifyFlushTimeoutMillis(1000);   // close_notify 发送超时 1s（默认 3s）
sslHandler.setCloseNotifyReadTimeoutMillis(1000);    // 等待对端 close_notify 超时 1s（默认 0）
```

---

## 7. 两套生产配置模板

### 7.1 吞吐优先模板（高并发网关/代理）

**场景特征**：
- 连接数：10万+
- 消息大小：1KB~64KB
- 延迟要求：P99 < 10ms
- 内存预算：充足（16GB+）

```java
public class ThroughputFirstServer {

    public static void main(String[] args) throws Exception {
        // ⭐ 使用 Epoll（Linux 生产环境）
        EventLoopGroup bossGroup   = new EpollEventLoopGroup(1);
        EventLoopGroup workerGroup = new EpollEventLoopGroup();  // CPU * 2

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
             .channel(EpollServerSocketChannel.class)

             // ===== 服务端 Channel 参数 =====
             .option(ChannelOption.SO_BACKLOG, 4096)          // 大 backlog，应对连接风暴
             .option(ChannelOption.SO_REUSEADDR, true)        // 快速重启

             // ===== 子 Channel 参数 =====
             .childOption(ChannelOption.TCP_NODELAY, true)    // 禁用 Nagle，降低延迟
             .childOption(ChannelOption.SO_KEEPALIVE, false)  // 用业务心跳代替 TCP 保活
             .childOption(ChannelOption.SO_SNDBUF, 256 * 1024)   // 256KB 发送缓冲
             .childOption(ChannelOption.SO_RCVBUF, 256 * 1024)   // 256KB 接收缓冲

             // ===== 内存分配器 =====
             .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)

             // ===== 写水位线（背压）=====
             .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                 new WriteBufferWaterMark(512 * 1024, 1024 * 1024))  // low=512KB, high=1MB

             // ===== 接收缓冲区分配策略 =====
             .childOption(ChannelOption.RECVBUF_ALLOCATOR,
                 new AdaptiveRecvByteBufAllocator(1024, 8192, 65536))  // min=1KB, init=8KB, max=64KB

             // ===== 自动读取 =====
             .childOption(ChannelOption.AUTO_READ, true)

             .childHandler(new ChannelInitializer<EpollSocketChannel>() {
                 @Override
                 protected void initChannel(EpollSocketChannel ch) {
                     ChannelPipeline p = ch.pipeline();
                     // 心跳检测
                     p.addLast(new IdleStateHandler(60, 30, 0, TimeUnit.SECONDS));
                     // 帧解码（根据协议选择）
                     p.addLast(new LengthFieldBasedFrameDecoder(
                         65536,  // maxFrameLength
                         0, 4,   // lengthFieldOffset, lengthFieldLength
                         0, 4    // lengthAdjustment, initialBytesToStrip
                     ));
                     // 业务 Handler
                     p.addLast(new BusinessHandler());
                 }
             });

            ChannelFuture f = b.bind(8080).sync();
            f.channel().closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
```

**JVM 参数（吞吐优先）**：

```bash
# 堆内存：给 JVM 足够的堆，但不要太大（GC 停顿）
-Xms4g -Xmx4g

# 直接内存：Netty 堆外内存上限
-XX:MaxDirectMemorySize=8g

# GC：G1GC，平衡吞吐和延迟
-XX:+UseG1GC
-XX:MaxGCPauseMillis=100
-XX:G1HeapRegionSize=16m

# Netty 内存池参数
-Dio.netty.allocator.numDirectArenas=32
-Dio.netty.allocator.maxOrder=11

# 泄漏检测（生产关闭，性能影响大）
-Dio.netty.leakDetection.level=DISABLED
```

### 7.2 延迟优先模板（RPC 框架/实时通信）

**场景特征**：
- 连接数：1万~10万
- 消息大小：100B~4KB
- 延迟要求：P99 < 1ms
- 内存预算：适中（8GB）

```java
public class LatencyFirstServer {

    public static void main(String[] args) throws Exception {
        // ⭐ 使用 Epoll（Linux 生产环境）
        EventLoopGroup bossGroup   = new EpollEventLoopGroup(1);
        // 延迟优先：worker 线程数 = CPU 核数（避免线程切换）
        EventLoopGroup workerGroup = new EpollEventLoopGroup(
            Runtime.getRuntime().availableProcessors()
        );

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
             .channel(EpollServerSocketChannel.class)

             // ===== 服务端 Channel 参数 =====
             .option(ChannelOption.SO_BACKLOG, 1024)
             .option(ChannelOption.SO_REUSEADDR, true)

             // ===== 子 Channel 参数 =====
             .childOption(ChannelOption.TCP_NODELAY, true)    // ⭐ 延迟优先必须设置
             .childOption(ChannelOption.SO_KEEPALIVE, false)

             // ===== 内存分配器 =====
             .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)

             // ===== 写水位线（延迟优先：水位线小，积压少）=====
             .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                 new WriteBufferWaterMark(32 * 1024, 64 * 1024))  // 默认值，不积压

             // ===== 接收缓冲区（小消息：初始值小）=====
             .childOption(ChannelOption.RECVBUF_ALLOCATOR,
                 new AdaptiveRecvByteBufAllocator(64, 1024, 8192))  // min=64B, init=1KB, max=8KB

             .childHandler(new ChannelInitializer<EpollSocketChannel>() {
                 @Override
                 protected void initChannel(EpollSocketChannel ch) {
                     ChannelPipeline p = ch.pipeline();
                     // 心跳检测（延迟优先：更短的超时）
                     p.addLast(new IdleStateHandler(30, 15, 0, TimeUnit.SECONDS));
                     // 帧解码
                     p.addLast(new LengthFieldBasedFrameDecoder(8192, 0, 4, 0, 4));
                     // 业务 Handler（⭐ 延迟优先：业务逻辑必须在 EventLoop 中快速完成，或立即提交到业务线程池）
                     p.addLast(new BusinessHandler());
                 }
             });

            ChannelFuture f = b.bind(8080).sync();
            f.channel().closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
```

**JVM 参数（延迟优先）**：

```bash
# 堆内存：适中，减少 GC 停顿
-Xms2g -Xmx2g

# 直接内存
-XX:MaxDirectMemorySize=4g

# GC：ZGC（低延迟，JDK 15+）或 G1GC
-XX:+UseZGC                    # JDK 15+，亚毫秒级 GC 停顿
# 或
-XX:+UseG1GC
-XX:MaxGCPauseMillis=10        # 目标 GC 停顿 < 10ms

# Netty 内存池参数（延迟优先：小 Chunk，快速分配）
-Dio.netty.allocator.numDirectArenas=16
-Dio.netty.allocator.maxOrder=9   # 默认值，Chunk=4MB

# 泄漏检测（生产关闭）
-Dio.netty.leakDetection.level=DISABLED
```

---

## 8. 两套模板参数对比

| 参数 | 默认值 | 吞吐优先 | 延迟优先 | 说明 |
|------|--------|---------|---------|------|
| `SO_BACKLOG` | `SOMAXCONN`（Linux 默认 4096，实际读 `/proc/sys/net/core/somaxconn`） | 4096 | 1024 | 吞吐优先需要更大队列 |
| `TCP_NODELAY` | false | **true** | **true** | 两者都必须开启 |
| `SO_SNDBUF` | OS 默认（~128KB） | 256KB | 默认 | 吞吐优先需要更大缓冲 |
| `SO_RCVBUF` | OS 默认（~128KB） | 256KB | 默认 | 同上 |
| `WRITE_BUFFER_WATER_MARK` | low=32KB, high=64KB | low=512KB, high=1MB | 默认 | 吞吐优先允许更多积压 |
| `RecvByteBufAllocator` | Adaptive(64,2048,65536) | Adaptive(1KB,8KB,64KB) | Adaptive(64B,1KB,8KB) | 根据消息大小调整 |
| worker 线程数 | CPU * 2 | CPU * 2 | **CPU 核数** | 延迟优先减少线程切换 |
| `IdleStateHandler` reader | 无 | 60s | 30s | 延迟优先更快检测死连接 |
| `IdleStateHandler` writer | 无 | 30s | 15s | 延迟优先更频繁心跳 |
| GC 策略 | - | G1GC（100ms 停顿目标） | ZGC（亚毫秒） | 延迟优先需要低停顿 GC |
| 泄漏检测 | SIMPLE | DISABLED | DISABLED | 生产关闭，性能影响大 |

---

## 9. 可观测性：你必须监控的指标

### 9.1 EventLoop 健康度

```java
// 监控 EventLoop 队列积压
EventLoopGroup workerGroup = new NioEventLoopGroup();
for (EventExecutor executor : workerGroup) {
    if (executor instanceof SingleThreadEventExecutor) {
        SingleThreadEventExecutor singleThread = (SingleThreadEventExecutor) executor;
        int pendingTasks = singleThread.pendingTasks();
        // ⚠️ 告警阈值：pendingTasks > 1000 说明 EventLoop 过载
        if (pendingTasks > 1000) {
            logger.warn("EventLoop overloaded, pendingTasks={}", pendingTasks);
        }
    }
}
```

**关键指标**：

| 指标 | 正常范围 | 告警阈值 | 含义 |
|------|---------|---------|------|
| EventLoop pendingTasks | 0~100 | > 1000 | EventLoop 过载，任务积压 |
| EventLoop ioRatio | 50%（默认） | - | IO 时间占比，可调整 |
| 连接数 | 业务决定 | 接近 `ulimit -n` | 文件描述符耗尽风险 |

### 9.2 内存健康度

```java
// 监控 PooledByteBufAllocator 内存使用
PooledByteBufAllocator allocator = PooledByteBufAllocator.DEFAULT;
PooledByteBufAllocatorMetric metric = allocator.metric();

long usedHeapMemory   = metric.usedHeapMemory();    // 已使用堆内内存
long usedDirectMemory = metric.usedDirectMemory();  // 已使用堆外内存
int numHeapArenas     = metric.numHeapArenas();     // 堆内 Arena 数量
int numDirectArenas   = metric.numDirectArenas();   // 堆外 Arena 数量

// ⚠️ 告警：堆外内存持续增长（可能泄漏）
// ⚠️ 告警：usedDirectMemory > MaxDirectMemorySize * 0.8
```

### 9.3 写队列积压

```java
// 监控 Channel 写队列积压
Channel channel = ...;
ChannelOutboundBuffer outboundBuffer = channel.unsafe().outboundBuffer();
if (outboundBuffer != null) {
    long totalPendingBytes = outboundBuffer.totalPendingWriteBytes();
    // ⚠️ 告警：totalPendingBytes > highWaterMark * 10（严重积压）
}
```

### 9.4 泄漏检测配置

```bash
# 开发环境：PARANOID（每次分配都检测）
-Dio.netty.leakDetection.level=PARANOID

# 测试环境：ADVANCED（采样 + 详细堆栈）
-Dio.netty.leakDetection.level=ADVANCED

# 预发布环境：SIMPLE（采样，只报告泄漏，不记录堆栈）
-Dio.netty.leakDetection.level=SIMPLE

# 生产环境：DISABLED（关闭，避免性能影响）
-Dio.netty.leakDetection.level=DISABLED
```

> ⚠️ **生产踩坑**：`PARANOID` 模式下，每次 `ByteBuf` 分配都会创建一个 `PhantomReference`，GC 压力极大，吞吐量可能下降 50%+。**生产环境必须关闭**。

---

## 10. 优雅关闭

```java
// 标准优雅关闭流程
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    // 1. 停止接受新连接（关闭 boss）
    bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);

    // 2. 等待 worker 处理完已有请求（最多等 30s）
    workerGroup.shutdownGracefully(2, 30, TimeUnit.SECONDS);

    // 3. 等待关闭完成
    try {
        bossGroup.terminationFuture().sync();
        workerGroup.terminationFuture().sync();
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
}));
```

**`shutdownGracefully(quietPeriod, timeout, unit)` 参数含义**：


| 参数 | 含义 | 推荐值 |
|------|------|--------|
| `quietPeriod` | 静默期：这段时间内没有新任务提交，才认为可以关闭 | boss: 0s，worker: 2s |
| `timeout` | 最大等待时间：超过此时间强制关闭 | boss: 5s，worker: 30s |

> ⚠️ **生产踩坑**：如果 `quietPeriod=0`，EventLoop 会立即停止接受新任务，可能导致正在处理的请求被中断。`quietPeriod=2s` 可以给正在处理的请求一个完成的机会。

---

## 11. 常见生产问题排查清单

### 11.1 高延迟排查

```
症状：P99 延迟突然升高

排查步骤：
1. 检查 EventLoop pendingTasks 是否积压
   → 如果积压：业务逻辑是否在 EventLoop 中执行了耗时操作？
   → 解决：把耗时操作提交到独立线程池

2. 检查 GC 停顿时间
   → 如果 GC 停顿 > 100ms：调整 GC 策略（ZGC/G1GC）
   → 检查是否有大量 ByteBuf 泄漏导致 GC 频繁

3. 检查 TCP 层延迟
   → TCP_NODELAY 是否开启？
   → 是否有 Nagle + Delayed ACK 叠加问题？

4. 检查写队列积压
   → Channel.isWritable() 是否频繁变为 false？
   → 下游处理速度是否跟不上？
```

### 11.2 OOM 排查

```
症状：堆外内存 OOM（java.lang.OutOfMemoryError: Direct buffer memory）

排查步骤：
1. 开启泄漏检测
   -Dio.netty.leakDetection.level=ADVANCED
   → 查看日志中的 "LEAK: ByteBuf.release() was not called" 告警

2. 检查 ByteBuf 使用
   → 是否有 ByteBuf 没有调用 release()？
   → 是否在 Handler 中直接 return 而没有 release 入参？

3. 检查 PooledByteBufAllocator 指标
   → usedDirectMemory 是否持续增长？
   → 是否有 Arena 的 allocations 远大于 deallocations？

4. 检查 ChannelOutboundBuffer
   → 是否有大量连接的写队列积压？
   → 是否有慢消费者导致写队列无限增长？
```

### 11.3 连接数耗尽排查

```
症状：新连接无法建立（Connection refused 或 Too many open files）

排查步骤：
1. 检查文件描述符限制
   ulimit -n  → 如果 < 65535，需要调大
   /etc/security/limits.conf:
     * soft nofile 1048576
     * hard nofile 1048576

2. 检查 TIME_WAIT 连接数
   ss -s | grep TIME-WAIT
   → 如果大量 TIME_WAIT：开启 SO_REUSEADDR，调整 tcp_tw_reuse

3. 检查 accept 队列是否满
   netstat -s | grep "SYNs to LISTEN"
   → 如果有丢弃：增大 SO_BACKLOG 和 somaxconn

4. 检查是否有连接泄漏
   → 是否有连接没有正确关闭？
   → IdleStateHandler 是否正确配置？
```

---

## 12. 核心不变式（Invariant）

1. **EventLoop 不能阻塞**：任何在 EventLoop 线程中执行的操作，必须在微秒级完成。数据库查询、HTTP 调用、文件 IO 等耗时操作必须提交到独立线程池。

2. **ByteBuf 必须成对 release**：每次 `allocate()` 必须对应一次 `release()`。在 Handler 中，如果不把 ByteBuf 传递给下一个 Handler，必须手动 `release()`。

3. **写操作必须在 EventLoop 线程中执行**：`ctx.write()` 和 `ctx.writeAndFlush()` 可以在任意线程调用，但实际写操作会被提交到 EventLoop 线程执行。如果在 EventLoop 线程中调用，是同步的；在其他线程调用，是异步的（提交任务）。

---

## 13. 完整调优决策树

```mermaid
flowchart TD
    A[开始调优] --> B{业务类型?}
    B -->|高并发网关/代理| C[吞吐优先模板]
    B -->|RPC/实时通信| D[延迟优先模板]
    B -->|混合型| E[根据压测结果调整]

    C --> C1[SO_BACKLOG=4096]
    C --> C2[WRITE_BUFFER_WATER_MARK high=1MB]
    C --> C3[worker=CPU*2]
    C --> C4[G1GC MaxGCPause=100ms]

    D --> D1[SO_BACKLOG=1024]
    D --> D2[WRITE_BUFFER_WATER_MARK 默认]
    D --> D3[worker=CPU核数]
    D --> D4[ZGC 亚毫秒停顿]

    C1 & C2 & C3 & C4 --> F[压测验证]
    D1 & D2 & D3 & D4 --> F

    F --> G{P99 延迟达标?}
    G -->|否| H{EventLoop 积压?}
    H -->|是| I[业务逻辑移出 EventLoop]
    H -->|否| J{GC 停顿?}
    J -->|是| K[调整 GC 策略]
    J -->|否| L[检查网络层参数]
    G -->|是| M{吞吐达标?}
    M -->|否| N{内存分配瓶颈?}
    N -->|是| O[增大 Arena 数量]
    N -->|否| P[增大 worker 线程数]
    M -->|是| Q[✅ 调优完成]
```

---

## 14. 参数速查表（生产 Checklist）

| 参数 | 默认值 | 生产推荐 | 必须设置 |
|------|--------|---------|---------|
| `SO_BACKLOG` | `SOMAXCONN`（Linux 默认 4096，实际读 `/proc/sys/net/core/somaxconn`） | 1024~4096 | ✅ |
| `SO_REUSEADDR` | false | **true** | ✅ |
| `TCP_NODELAY` | false | **true** | ✅ |
| `SO_KEEPALIVE` | false | false（用业务心跳） | - |
| `ALLOCATOR` | `PooledByteBufAllocator.DEFAULT` | 保持默认 | - |
| `WRITE_BUFFER_WATER_MARK` | low=32KB, high=64KB | 根据消息大小调整 | ✅ |
| `CONNECT_TIMEOUT_MILLIS` | 30000ms | 1000~5000ms | ✅（客户端） |
| `IdleStateHandler` | 无 | reader=60s, writer=30s | ✅ |
| `leakDetection.level` | SIMPLE | **DISABLED**（生产） | ✅ |
| boss 线程数 | CPU * 2 | **1** | ✅ |
| worker 线程数 | CPU * 2 | CPU * 2（吞吐）/ CPU（延迟） | - |
| Transport | NIO | **Epoll**（Linux） | ✅（Linux） |


---

## 附录：核对清单

> 以下为文档编写过程中的源码核对记录，供审计追溯使用。

1. 核对记录：已对照 NioEventLoopGroup 构造函数，默认值 = CPU * 2，差异：无
2. 核对记录：已对照 DefaultServerSocketChannelConfig.java 第45行 `private volatile int backlog = NetUtil.SOMAXCONN`，差异：无
3. 核对记录：已对照 NetUtil.java SoMaxConnAction，Linux kernel > 5.4 默认 4096，差异：无
4. 核对记录：已对照 DefaultChannelConfig.java 第60行 `private volatile ByteBufAllocator allocator = ByteBufAllocator.DEFAULT`，差异：无
5. 核对记录：已对照 PooledByteBufAllocator.java 第68-175行静态初始化块，差异：无
6. 核对记录：已对照 WriteBufferWaterMark.java 第37-38行，DEFAULT_LOW=32*1024，DEFAULT_HIGH=64*1024，差异：无
7. 核对记录：已对照 DefaultChannelConfig.java 第64行 `private static final int DEFAULT_CONNECT_TIMEOUT = 30000`，差异：无
8. 核对记录：已对照 EventExecutorGroup.shutdownGracefully() 方法签名，差异：无
9. 核对记录：已对照 DefaultChannelConfig.java、WriteBufferWaterMark.java、DefaultServerSocketChannelConfig.java、NetUtil.java、PooledByteBufAllocator.java 全文，所有默认值与源码完全一致，差异：无

