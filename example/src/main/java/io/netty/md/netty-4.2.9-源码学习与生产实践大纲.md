## Netty 4.2.9 源码学习与生产实践大纲（聚焦生产 + 面试 + 框架复用）

### 0. 学习目标与边界
- **目标**：建立“会用 + 会调优 + 能讲清源码设计 + 能定位线上问题”的完整能力。
- **当前边界**：只关注生产环境高价值能力、面试高频考点、以及被上层框架复用最多的核心模块。
- **暂不优先**：边缘协议示例、低频实验性功能、纯 demo 型 API。

---

## 1. 本地 Native 库编译状态

### 1.1 已编译成功的 Native 模块 ✅

| 模块 | 状态 | 产物 | 本地库 |
|------|------|------|--------|
| `transport-native-epoll` | ✅ 已编译 | `netty-transport-native-epoll-4.2.9.Final-linux-x86_64.jar` (41KB) | `libnetty_transport_native_epoll_x86_64.so` |
| `transport-native-io_uring` | ✅ 已编译 | `netty-transport-native-io_uring-4.2.9.Final-linux-x86_64.jar` | `libnetty_transport_native_io_uring42_x86_64.so` |

- 编译命令参考：`mvn install -pl transport-native-epoll -am -DskipTests -Dcheckstyle.skip=true -Denforcer.skip=true`
- 产物已安装到本地 Maven 仓库，可直接在代码中使用。

### 1.2 仍未编译的 Native 模块（暂不影响学习）

| 模块 | 原因 | 影响 |
|------|------|------|
| `transport-native-kqueue` | 需要 macOS/BSD 环境 | 仅 macOS 需要，Linux 不影响 |
| `transport-native-unix-common-tests` | 测试模块 | 不影响 |
| `transport-native-epoll-tests` | 测试模块 | 不影响 |
| `transport-native-kqueue-tests` | 测试模块 | 不影响 |

### 1.3 验证 Native 可用性
```java
// Epoll 验证
System.out.println("Epoll available: " + Epoll.isAvailable());
// io_uring 验证
System.out.println("IOUring available: " + IOUring.isAvailable());
```

### 1.4 学习策略（更新）
- **策略 A（推荐）**：先用 Java NIO 完整走通源码主线，再对比 Epoll / io_uring 差异分支。
- **策略 B**：并行学习 NIO + Epoll + io_uring，直接在 Linux 环境做性能对比。
- 因为 Epoll 和 io_uring 已编译成功，**建议直接采用策略 B**，获得最完整的学习体验。

---

## 2. 源码学习总路线（四阶段）

### 阶段一：建立全局地图（1~2 天）
- **目标**：先知道“每个包解决什么问题”，避免陷入细节。
- **重点模块**：
  - `buffer`：内存模型与池化（ByteBuf / PooledByteBufAllocator）
  - `channel`：抽象传输层、事件流转
  - `transport`：NIO 核心实现
  - `handler`：编解码与业务处理
  - `resolver`：DNS
  - `codec` / `codec-http` / `codec-http2`：协议编解码
- **输出物**：一张“连接建立 -> IO -> 编解码 -> 业务 -> 回写”的调用链流程图。

### 阶段二：生产主链路深挖（7~10 天）
- **目标**：把最重要的调用链从“会背”变成“会断点 + 会解释”。
- **必读主线**：
  1. `ServerBootstrap` / `Bootstrap` 启动流程
  2. `NioEventLoopGroup` 与 `NioEventLoop` 线程模型
  3. `Channel` 生命周期：register、active、read、write、flush、close
  4. `ChannelPipeline` 责任链传播（inbound / outbound）
  5. `ByteBuf` 引用计数 + 内存池分配与回收
  6. `ChannelOutboundBuffer` + 写水位线 + Flush 机制
- **输出物**：
  - 一份“单次请求从 Socket 到业务 Handler 再到回包”的时序图
  - 一份“线上内存泄漏排查清单”

### 阶段三：高性能与稳定性专项（5~7 天）
- **目标**：聚焦生产问题（延迟抖动、OOM、连接风暴、积压）。
- **专题**：
  - 粘包拆包：`LengthFieldBasedFrameDecoder` 等
  - 背压：高低水位、`autoRead`、批量写策略
  - 零拷贝：`CompositeByteBuf`、`FileRegion`、`sendfile`
  - Idle 与心跳：`IdleStateHandler`
  - TLS：`SslHandler`、握手与证书链路
  - Native transport 对比 NIO 的收益边界
- **输出物**：
  - 一份“吞吐优先 vs 延迟优先”参数模板
  - 一份“服务端稳态压测 checklist”

### 阶段四：框架复用与面试打磨（3~5 天）
- **目标**：把源码认知迁移到框架与面试表达。
- **框架映射**：
  - Dubbo：基于 Netty 的 RPC 传输层、连接管理、心跳
  - gRPC Java：底层基于 Netty（HTTP/2 + TLS）
  - Reactor Netty（Spring WebFlux）：响应式网络层
  - RocketMQ 等中间件：Remoting 层常见基于 Netty
- **输出物**：
  - 一份“框架怎么复用 Netty 的能力地图”
  - 一份“面试问答模板（5分钟/15分钟两种粒度）”

---

## 3. 必学源码专题（按优先级）

### 3.1 线程模型与事件循环（最高优先级）
- **要回答的问题**：
  - 为什么单个 `EventLoop` 能处理多个 `Channel`？
  - 任务队列如何与 IO 事件并存（定时任务、普通任务）？
  - `boss` / `worker` 分工如何影响连接接入能力？
- **面试高频点**：Reactor 模型、线程切换、串行化执行保障。

### 3.2 Pipeline 与 Handler 机制
- **要回答的问题**：
  - 入站/出站事件如何传播？
  - 为什么可以灵活插拔编解码器？
  - Handler 的 sharable 与线程安全边界是什么？
- **面试高频点**：责任链模式、事件传播方向、异常处理链路。

### 3.3 ByteBuf 与内存池
- **要回答的问题**：
  - 为什么不用 JDK ByteBuffer 直接做全部事情？
  - 堆内/堆外内存如何取舍？
  - 引用计数什么时候最容易泄漏？
- **面试高频点**：池化分配、碎片化、泄漏检测等级。

### 3.4 编解码与半包粘包
- **要回答的问题**：
  - TCP 粘包拆包本质是什么？
  - 定长/分隔符/长度字段协议的取舍？
  - 解码失败如何做容错与限流？

### 3.5 写路径与背压
- **要回答的问题**：
  - `write` 与 `flush` 为什么分离？
  - 高低水位如何防止下游慢导致 OOM？
  - 什么时候应该暂时关闭 `autoRead`？

### 3.6 连接生命周期与故障处理
- **要回答的问题**：
  - 连接建立、断开、重连、半开连接如何识别？
  - 超时控制（连接超时、读超时、写超时）怎么配？
  - 异常关闭与优雅关闭如何区分？

### 3.7 TLS / HTTP2（框架复用重点）
- **要回答的问题**：
  - gRPC 为什么强依赖 HTTP/2 与 TLS 相关能力？
  - OpenSSL 与 JDK SSL provider 的主要差异是什么？

### 3.8 Native Transport 深入（Epoll + io_uring）⭐ 新增

#### 3.8.1 Epoll Transport 源码专题
- **核心类与调用链**：
  - `EpollEventLoopGroup` → `EpollEventLoop`：对比 `NioEventLoop`，理解 epoll_wait 替代 Selector.select()
  - `EpollServerSocketChannel` / `EpollSocketChannel`：JNI 直接操作 fd，跳过 JDK Channel 抽象
  - `Native.java`（JNI 入口）→ `netty_epoll_native.c`：JNI 到 Linux 系统调用的桥梁
- **要回答的问题**：
  - Epoll 的 Edge-Triggered（ET）vs Level-Triggered（LT），Netty 用的哪种？为什么？
  - 为什么 Epoll Transport 比 NIO 性能好？差异体现在哪几个环节？
  - `EpollEventLoop.run()` 与 `NioEventLoop.run()` 的核心差异是什么？
  - JNI 调用 `epoll_create`、`epoll_ctl`、`epoll_wait` 的时机分别在哪？
- **源码阅读路径**：
  ```
  transport-native-epoll/src/main/java/io/netty/channel/epoll/
  ├── Epoll.java                    -- 可用性检测入口
  ├── EpollEventLoopGroup.java      -- 线程组
  ├── EpollEventLoop.java           -- ⭐核心事件循环（对比 NioEventLoop）
  ├── EpollServerSocketChannel.java -- 服务端 Channel
  ├── EpollSocketChannel.java       -- 客户端 Channel
  ├── AbstractEpollChannel.java     -- Channel 抽象基类
  └── Native.java                   -- JNI 方法声明
  transport-native-epoll/src/main/c/
  └── netty_epoll_native.c          -- C 层系统调用封装
  ```
- **面试高频点**：
  - Epoll 的 ET/LT 模式选择及其对读写循环的影响
  - JNI 调用开销 vs JDK NIO 的 Selector 开销对比
  - `EPOLLET | EPOLLRDHUP` 标志的作用

#### 3.8.2 io_uring Transport 源码专题（Linux 新一代异步 IO）
- **核心类与调用链**：
  - `IOUringEventLoopGroup` → `IOUringEventLoop`：基于 io_uring 的事件循环
  - `IOUringServerSocketChannel` / `IOUringSocketChannel`：基于 io_uring 的 Channel
  - `IOUringSubmissionQueue` / `IOUringCompletionQueue`：SQ/CQ 环形缓冲区的 Java 映射
  - `RingBuffer`：SQ + CQ 的统一管理
- **要回答的问题**：
  - io_uring 与 epoll 的本质区别是什么？（提交队列/完成队列 vs 就绪通知）
  - io_uring 为什么被称为「真正的异步 IO」？与 epoll 的「就绪通知 + 同步读写」有何不同？
  - SQ（Submission Queue）和 CQ（Completion Queue）的环形缓冲区设计为什么高效？
  - io_uring 的 `IORING_SETUP_SQPOLL` 模式是什么？Netty 是否使用？
  - io_uring 在什么场景下比 epoll 有明显优势？（高连接数 + 高 IOPS）
  - io_uring 目前的局限性和内核版本要求是什么？
- **源码阅读路径**：
  ```
  transport-native-io_uring/src/main/java/io/netty/channel/uring/
  ├── IOUring.java                       -- 可用性检测入口
  ├── IOUringEventLoopGroup.java         -- 线程组
  ├── IOUringEventLoop.java              -- ⭐核心事件循环
  ├── IOUringServerSocketChannel.java    -- 服务端 Channel
  ├── IOUringSocketChannel.java          -- 客户端 Channel
  ├── IOUringSubmissionQueue.java        -- ⭐提交队列（SQ）
  ├── IOUringCompletionQueue.java        -- ⭐完成队列（CQ）
  ├── RingBuffer.java                    -- SQ + CQ 统一管理
  └── Native.java                        -- JNI 方法声明
  transport-native-io_uring/src/main/c/
  └── netty_io_uring_native.c            -- C 层 io_uring 系统调用封装
  ```
- **面试高频点**：
  - io_uring 的 SQ/CQ 环形缓冲区原理
  - io_uring vs epoll 的系统调用次数对比（批量提交 vs 逐个通知）
  - io_uring 的内核版本依赖（5.1+ 基础，5.6+ 推荐）
  - Netty 中 io_uring 实现的成熟度与生产就绪程度

#### 3.8.3 三种 Transport 对比总结（NIO vs Epoll vs io_uring）

| 维度 | NIO（Java） | Epoll（Native） | io_uring（Native） |
|------|------------|----------------|-------------------|
| 平台 | 跨平台 | Linux only | Linux 5.1+ |
| IO 模型 | 多路复用（select/poll） | 多路复用（epoll） | 异步提交/完成队列 |
| 触发模式 | LT | ET（Edge-Triggered） | 无（异步回调） |
| 系统调用开销 | 高（JDK 抽象层） | 中（JNI 直调） | 低（批量提交，零拷贝共享内存） |
| 适用场景 | 开发/测试/跨平台 | Linux 生产（主流） | 超高并发/IOPS 密集型 |
| 成熟度 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐（快速发展中） |
| GC 压力 | 较高 | 较低 | 最低 |

### 3.9 Future 与 Promise 异步模型（15-Future与Promise异步模型）
- **核心源码**：
  - `DefaultPromise`(30KB)：核心状态机，Listener 链通知、死锁检测、await/sync 阻塞语义
  - `Future` / `Promise` 接口：Netty 异步编程的顶层抽象
  - `ChannelFuture` / `ChannelPromise`：Channel I/O 操作的异步返回值
  - `DefaultFutureListeners`：Listener 数组扩容与通知机制
  - `PromiseCombiner`：多 Future 聚合编排
  - `CompleteFuture` / `SucceededFuture` / `FailedFuture`：轻量级常量 Future
- **要回答的问题**：
  - `DefaultPromise` 的状态机有哪些状态？如何做到线程安全的状态流转？
  - `addListener()` 如果 Promise 已完成，Listener 何时被执行？在哪个线程？
  - `await()` 与 `sync()` 的区别是什么？为什么 `sync()` 会抛异常而 `await()` 不会？
  - 为什么 Netty 不直接使用 JDK 的 `CompletableFuture`？
  - `PromiseCombiner` 在什么场景使用？如何保证所有子 Future 完成后通知？
- **面试高频点** 🔥：Promise 状态机、Listener 通知线程、死锁检测机制

### 3.10 并发工具箱（16-并发工具箱-时间轮-Recycler-FastThreadLocal）
- **核心源码**：
  - `HashedWheelTimer`(34KB)：时间轮算法，驱动超时检测（IdleStateHandler/连接超时）
  - `Recycler`(27KB)：对象回收池（ThreadLocal Stack + WeakOrderQueue 跨线程回收）
  - `FastThreadLocal`(11KB) + `InternalThreadLocalMap`(13KB)：数组替代 HashMap 的高性能 ThreadLocal
  - `FastThreadLocalThread`(9.6KB)：专用线程类，配合 FastThreadLocal 使用
  - `ObjectPool`：对象池抽象接口
- **要回答的问题**：
  - `HashedWheelTimer` 的时间轮格子数和 tick 间隔如何影响精度和性能？
  - `Recycler` 的 Stack 和 WeakOrderQueue 如何解决跨线程回收问题？
  - `FastThreadLocal` 为什么比 JDK `ThreadLocal` 快？底层的数组 vs HashMap 设计差异？
  - 时间轮的溢出任务（延迟超过一轮）如何处理？
  - `Recycler` 的最大容量限制（maxCapacity）和回收频率（ratio）如何防止内存膨胀？
- **面试高频点** 🔥：时间轮算法原理、对象池跨线程回收、FastThreadLocal 数组索引 vs HashMap

### 3.11 引用计数与平台适配层（17-引用计数与平台适配层）
- **核心源码**：
  - `ReferenceCounted` 接口：引用计数顶层抽象
  - `AbstractReferenceCounted` / `RefCnt`(16KB)：CAS 实现的引用计数核心（Unsafe/VarHandle/AtomicUpdater 三条路径）
  - `ReferenceCountUtil`(8KB)：引用计数工具方法（safeRelease 等）
  - `ResourceLeakDetector`(29KB)：内存泄漏检测器（采样 + 弱引用 + PhantomReference）
  - `PlatformDependent`(71KB 大文件)：平台适配层——Unsafe 封装、Direct Memory 管理、MPSC 队列选型
  - `PlatformDependent0`(47KB)：底层 Unsafe 操作封装
  - jctools MPSC 队列（shaded）：EventLoop 任务队列的底层数据结构
- **要回答的问题**：
  - `RefCnt` 的 CAS 自旋是如何实现的？为什么有三条路径（Unsafe/VarHandle/AtomicUpdater）？
  - `ResourceLeakDetector` 的四个检测等级（DISABLED/SIMPLE/ADVANCED/PARANOID）有什么区别？
  - `PlatformDependent` 如何选择 MPSC 队列实现？jctools vs JDK ConcurrentLinkedQueue？
  - 为什么 Netty 要 shade jctools 而不是直接依赖？
  - Unsafe 在 Netty 中有哪些关键用途（内存操作、CAS、直接内存分配）？
- **面试高频点** 🔥：引用计数 CAS 实现、泄漏检测等级、PlatformDependent 的 Unsafe 封装

### 3.12 4.2 新架构：IoHandler 与弹性线程（18-4.2新架构-IoHandler与弹性线程）
- **核心源码**：
  - `IoHandler` / `IoHandlerFactory` 接口：**4.2 最大的架构重构**，将 I/O 处理从 EventLoop 解耦为独立 SPI
  - `IoHandle` / `IoEvent` / `IoOps`：I/O 事件的统一抽象
  - `IoRegistration`：Channel 注册到 IoHandler 的凭证
  - `SingleThreadIoEventLoop`(13.6KB)：新的 EventLoop 基类，内部委托 IoHandler
  - `MultiThreadIoEventLoopGroup`(10.8KB)：新的 EventLoopGroup 基类
  - `NioIoHandler`(31KB)：NIO 的 IoHandler 实现（原 NioEventLoop 核心逻辑迁移至此）
  - `EpollIoHandler` / `IOUringIoHandler`：Native Transport 的 IoHandler 实现
  - `AutoScalingEventExecutorChooserFactory`(20KB)：**4.2 新功能**——基于负载的 EventLoop 线程动态伸缩
  - `DefaultEventExecutorChooserFactory`：传统的 PowerOfTwo / Generic 轮询选择
- **要回答的问题**：
  - 为什么 4.2 要把 I/O 处理从 EventLoop 中解耦成 IoHandler？解决了什么问题？
  - `IoHandler` SPI 模型如何让 NIO/Epoll/io_uring 实现可插拔？
  - `SingleThreadIoEventLoop` 与旧版 `SingleThreadEventLoop` 的核心区别是什么？
  - `AutoScalingEventExecutorChooserFactory` 如何检测 EventLoop 的负载并做 scaleUp/scaleDown？
  - 从 4.1 升级到 4.2 的 EventLoop 架构变化，对用户代码有什么影响？
- **面试高频点** 🔥：IoHandler SPI 设计思想、EventLoop 架构演进、弹性线程伸缩策略

### 3.13 自适应内存分配器 AdaptiveAllocator（19-自适应内存分配器AdaptiveAllocator）
- **核心源码**：
  - `AdaptiveByteBufAllocator`：自适应分配器入口
  - `AdaptivePoolingAllocator`(73KB 大文件)：**4.2 新增的反代际假说内存分配器**，基于 Magazine + Histogram 的自适应 chunk 策略
- **要回答的问题**：
  - 什么是「反代际假说」？为什么传统 PooledByteBufAllocator 的代际假说在网络场景中不成立？
  - Magazine 架构是什么？每个线程的 Magazine 如何管理 chunk？
  - Histogram 如何统计分配大小的频率分布并指导 chunk 大小选择？
  - `AdaptivePoolingAllocator` 与 `PooledByteBufAllocator` 的核心设计差异和性能对比？
  - 什么场景下应该选择 Adaptive 而不是 Pooled？
- **面试高频点** 🔥：反代际假说设计思想、Magazine/Histogram 架构、与 Pooled 分配器的对比

---

## 4. 生产环境参数与调优主线

### 4.1 启动参数与 ChannelOption（核心关注）
- `SO_BACKLOG`：连接排队长度
- `SO_REUSEADDR`：端口复用语义
- `TCP_NODELAY`：Nagle 相关
- `SO_KEEPALIVE`：内核保活（注意与业务心跳互补）
- `WRITE_BUFFER_WATER_MARK`：背压阈值
- `ALLOCATOR`：内存分配器策略

### 4.2 稳定性保护
- 限制单连接最大帧长度，防止大包攻击
- 设置读写超时与空闲检测，避免僵尸连接
- 控制每个连接的待发送积压（防止慢消费者拖垮服务）

### 4.3 可观测性
- 关注指标：连接数、事件循环队列长度、P99 延迟、写队列积压、GC 时间
- 关键日志：握手失败、解码异常、主动关闭原因、泄漏检测告警

---

## 5. 面试高频问题清单（建议逐题做“源码证据化”）

### 5.1 原理类
- Netty 为什么高性能？
- Reactor 线程模型有哪些形态？
- `ByteBuf` 相比 `ByteBuffer` 的核心优势？

### 5.2 实战类
- 如何解决 TCP 粘包拆包？
- 如何避免内存泄漏与 OOM？
- 如何做连接保活与断线重连？

### 5.3 调优类
- 如何选择 `boss/worker` 线程数？
- 什么时候使用 epoll/kqueue？
- 线上延迟抖动时你会看哪些指标与日志？
- Epoll 的 ET 模式和 LT 模式有什么区别？Netty 为什么选择 ET？
- io_uring 相比 epoll 有什么优势？什么时候该用 io_uring？
- 三种 Transport（NIO/Epoll/io_uring）在不同并发量下的性能拐点在哪？

### 5.4 异步模型与并发工具类（⭐ 新增高频）
- `DefaultPromise` 的状态机有几种状态？Listener 通知的线程安全如何保证？
- Netty 的 `Future/Promise` 与 JDK `CompletableFuture` 的区别与联系？
- `HashedWheelTimer` 时间轮的精度由什么决定？为什么不用 `ScheduledExecutorService`？
- `Recycler` 如何实现跨线程回收？WeakOrderQueue 的设计动机是什么？
- `FastThreadLocal` 为什么比 JDK `ThreadLocal` 快？
- 引用计数的 CAS 实现为什么有三条路径？
- `ResourceLeakDetector` 的四个等级在生产中怎么选？

### 5.5 4.2 新特性专项（⭐ 新增高频）
- Netty 4.2 的 `IoHandler` 抽象层解决了什么问题？与 4.1 的 EventLoop 架构有何区别？
- `AutoScalingEventExecutorChooserFactory` 的弹性伸缩策略是什么？什么时候触发 scaleUp？
- `AdaptivePoolingAllocator` 的「反代际假说」是什么意思？什么场景下比 Pooled 更优？

### 5.6 框架关联类
- Dubbo/gRPC 为什么选择 Netty？
- Spring WebFlux 与 Netty 的关系是什么？
- 如果替换传输层，代价最大的是哪几块？

### 5.7 Native Transport 专项
- Netty 的 Epoll Transport 和 JDK NIO 有什么本质区别？（不是简单的 epoll vs select，而是整个 Channel 实现的差异）
- Epoll 的 ET 模式下，如果一次 read 没读完会怎样？Netty 如何处理？
- io_uring 的 SQ/CQ 机制是什么？为什么说它是真正的异步 IO？
- Netty 中 io_uring Transport 目前适合用在生产环境吗？有什么注意事项？
- 从 NIO 切换到 Epoll Transport 需要改哪些代码？（答：几乎只需换 EventLoopGroup 和 Channel 类型）
- 三种 Transport 分别在什么量级的并发下能体现差异？

---

## 6. 被其他框架复用最多的核心能力（必须会）

### 6.1 传输与事件驱动内核
- 多路复用 + 事件循环 + 任务调度的一体化线程模型
- **Native Transport 层**：Epoll / io_uring 提供的 Linux 原生高性能传输（被高吞吐框架如 gRPC、Envoy proxy 等直接使用）

### 6.2 Pipeline 可扩展链路
- 统一协议处理、鉴权、限流、日志、业务处理的可插拔机制

### 6.3 高性能内存与缓冲管理
- `ByteBuf` 池化、零拷贝、引用计数

### 6.4 协议编解码基础设施
- HTTP/1.1、HTTP/2、WebSocket、自定义二进制协议

### 6.5 TLS 与安全通信能力
- `SslHandler`、证书、ALPN（尤其对 gRPC 场景关键）

---

## 7. 建议的学习节奏（可执行）

### 第 1 周（打基础）
- 读通启动流程、线程模型、Pipeline 事件传播
- 用一个最小 Echo + 自定义协议 demo 验证调用链
- 分别用 NIO / Epoll / io_uring 运行同一个 EchoServer，感受 Transport 切换的无缝性

### 第 2 周（进阶）
- 深挖 `ByteBuf`、内存池、写路径与背压
- 压测并观察参数变化（吞吐、延迟、内存）
- 对比断点 `NioEventLoop.run()` vs `EpollEventLoop.run()` vs `IOUringEventLoop.run()` 的核心循环差异

### 第 3 周（生产化）
- 加入 TLS、心跳、超时、限流、优雅关闭
- 输出一份“可上线配置模板”和故障排查手册

### 第 4 周（面试与框架）
- 对照 Dubbo / gRPC / Reactor Netty 的调用路径
- 形成 20~30 个高频问答的“源码证据”版本

---

## 8. 你的下一步（立即可做）
- ~~先确认 native 能力是否可用（epoll/kqueue/OpenSSL）~~ ✅ Epoll 和 io_uring 已编译成功！
- 验证可用性：运行 `Epoll.isAvailable()` 和 `IOUring.isAvailable()` 确认返回 true。
- 然后从"阶段二主链路"开始，按 `ServerBootstrap -> EventLoop -> Pipeline -> ByteBuf -> write/flush` 顺序断点。
- 在学习 EventLoop 时，**同步对比三种实现**：`NioEventLoop` → `EpollEventLoop` → `IOUringEventLoop`。
- 每学完一个专题，产出"1 页笔记 + 1 个可运行 demo（NIO + Epoll + io_uring 三版本）+ 3 个面试问答"。

---

## 9. 学习产出标准（检验是否真的学会）
- **能画图**：画出请求处理时序图。
- **能解释**：对每个关键设计说出“为什么这样设计”。
- **能定位**：给你一个线上现象（高延迟/OOM/连接抖动）能快速给出排查路径。
- **能迁移**：能说明 Dubbo / gRPC / WebFlux 分别用了 Netty 的哪几层能力。
