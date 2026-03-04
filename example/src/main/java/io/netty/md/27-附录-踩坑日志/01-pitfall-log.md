# 附录：Netty 生产踩坑日志 ⚠️

> **定位**：汇总散落在 11+ 个章节中的全部生产踩坑点，按严重级别和模块分类，每条有统一编号、根因分析、代码示例和排查手段。
>
> **使用方式**：
> - 🔴 **P0（数据丢失/OOM/崩溃）**：上线前必须逐条检查
> - 🟡 **P1（性能劣化/资源浪费）**：性能调优时参考
> - 🟢 **P2（不规范/潜在风险）**：代码审查时参考

---

## 快速索引

| 编号 | 严重级别 | 模块 | 问题 | 章节出处 |
|------|---------|------|------|---------|
| PIT-001 | 🔴 P0 | 写路径 | 只 write 不 flush 导致数据不发送 | 第08章 §9.1 |
| PIT-002 | 🔴 P0 | 写路径 | 不检查 isWritable() 导致 OOM | 第08章 §9.2 |
| PIT-003 | 🔴 P0 | 引用计数 | ByteBuf 忘记 release 导致内存泄漏 | 第18章、第24章 |
| PIT-004 | 🔴 P0 | 编解码 | maxFrameLength 太大导致 OOM（大包攻击） | 第07章 §9.2 |
| PIT-005 | 🔴 P0 | Pipeline | exceptionCaught 未处理导致异常吞没 | 第05章 |
| PIT-006 | 🔴 P0 | 编解码 | encode() 中忘记 release 中间 ByteBuf | 第07章 §9.3 |
| PIT-007 | 🔴 P0 | EventLoop | 在 EventLoop 线程 sync()/await() 导致死锁 | 第16章 |
| PIT-008 | 🔴 P0 | EventLoop | taskQueue 无限增长导致 OOM | 第03章 |
| PIT-009 | 🔴 P0 | TLS | OPENSSL_REFCNT 的 SslContext 忘记 release | 第21章 |
| PIT-010 | 🔴 P0 | TLS | SslContext destroy() 后 SSLEngine 仍在使用导致 JVM Crash | 第21章 |
| PIT-011 | 🟡 P1 | 线程模型 | 容器中 availableProcessors() 返回宿主机核数 | 第03章、第26章 Q29 |
| PIT-012 | 🟡 P1 | 连接管理 | SO_BACKLOG 太小导致连接拒绝 | 第10章、第26章 Q30 |
| PIT-013 | 🟡 P1 | 写路径 | 水位线设置不合理导致频繁抖动 | 第08章 §9.3 |
| PIT-014 | 🟡 P1 | 写路径 | writeSpinCount 设置不当 | 第08章 §9.4 |
| PIT-015 | 🟡 P1 | 心跳 | 心跳超时时间太短，正常请求被误判 | 第10章 §7.1、第11章 §10 |
| PIT-016 | 🟡 P1 | 心跳 | IdleStateHandler 不是 @Sharable 却被共享 | 第11章 §10.1 |
| PIT-017 | 🟡 P1 | 心跳 | observeOutput 默认 false 导致写积压误判 | 第11章 §10.3 |
| PIT-018 | 🟡 P1 | 编解码 | decode() 消费数据但没产出消息导致死循环 | 第07章 §9.1 |
| PIT-019 | 🟡 P1 | 编解码 | ByteToMessageDecoder 标注 @Sharable | 第07章 §9.4 |
| PIT-020 | 🟡 P1 | 内存池 | Subpage ByteBuf 忘记 release，numAvail 不增 | 第06章 04篇 |
| PIT-021 | 🟡 P1 | 内存池 | PoolThreadCache 内存泄漏（非 FastThread） | 第06章 05篇 |
| PIT-022 | 🟡 P1 | 连接管理 | channelInactive 中重连时忘记释放资源 | 第10章 §7.2 |
| PIT-023 | 🟡 P1 | 连接管理 | 半开连接（对端崩溃）导致僵尸连接 | 第10章 §7.6 |
| PIT-024 | 🟡 P1 | 连接管理 | 优雅关闭时忘记等待 closeFuture | 第10章 §7.4 |
| PIT-025 | 🟡 P1 | 并发工具 | 每连接创建 HashedWheelTimer 导致线程爆炸 | 第17章 §1.8 |
| PIT-026 | 🟡 P1 | Transport | 非池化堆内 ByteBuf + Epoll/io_uring 仍触发 copy | 第09章 |
| PIT-027 | 🟡 P1 | HTTP/2 | 流控窗口耗尽导致请求卡住 | 第22章 02篇 |
| PIT-028 | 🟡 P1 | HTTP/2 | ENHANCE_YOUR_CALM 被对端限流 | 第22章 01篇 |
| PIT-029 | 🟡 P1 | ByteBuf | discardReadBytes() 高频调用触发内存拷贝 | 第06章 01篇 |
| PIT-030 | 🟢 P2 | 写路径 | 在非 EventLoop 线程调用 write 的额外开销 | 第08章 §9.5 |
| PIT-031 | 🟢 P2 | 心跳 | 心跳包格式与业务包不一致导致解码失败 | 第11章 §10.2 |
| PIT-032 | 🟢 P2 | 连接管理 | 连接超时用 await() 代替 CONNECT_TIMEOUT_MILLIS | 第10章 §7.3 |
| PIT-033 | 🟢 P2 | 连接管理 | channelActive 中发数据但 TLS 握手未完成 | 第10章 §7.7 |
| PIT-034 | 🟢 P2 | 并发工具 | Recycler 重复回收抛异常 | 第17章 §3.8 |
| PIT-035 | 🟢 P2 | 并发工具 | FastThreadLocal 在普通线程降级为 JDK ThreadLocal | 第17章 §2.6 |
| PIT-036 | 🟢 P2 | 内存池 | SizeClasses 误以为分配大小等于请求大小 | 第06章 02篇 |
| PIT-037 | 🟢 P2 | 内存池 | freeSweepAllocationThreshold 调优不当 | 第06章 05篇 |
| PIT-038 | 🟢 P2 | 泄漏检测 | PARANOID 模式用于生产导致吞吐量下降50%+ | 第24章 |
| PIT-039 | 🟢 P2 | 4.2新特性 | AdaptiveAllocator 仍是实验性 API | 第20章 §16.4 |
| PIT-040 | 🟢 P2 | io_uring | 误把"支持"当"启用"（SUPPORTED vs ENABLED） | 第13章 |
| PIT-041 | 🟢 P2 | 框架复用 | 业务逻辑在 EventLoop 线程执行导致阻塞 | 第25章 §8.3 |

---

## 一、🔴 P0 级（数据丢失 / OOM / 崩溃）

> **上线前必须逐条检查，每条都可能直接引发生产事故。**

### PIT-001：只 write 不 flush 导致数据不发送

| 项目 | 内容 |
|------|------|
| **严重级别** | 🔴 P0 |
| **现象** | 客户端收不到响应，连接看起来正常但数据"卡住"了 |
| **根因** | `write()` 只把消息加入 `ChannelOutboundBuffer`（内存队列），不触发系统调用。必须 `flush()` 才会真正写入 Socket |
| **出处** | [第08章 §9.1](../08-写路径与背压/01-write-path-and-backpressure.md) |

```java
// ❌ 错误：数据永远不会发送
ctx.write(response1);
ctx.write(response2);
// 忘记 flush！

// ✅ 正确方案1：批量 write + 一次 flush（推荐，减少 syscall）
ctx.write(response1);
ctx.write(response2);
ctx.flush();

// ✅ 正确方案2：直接 writeAndFlush
ctx.writeAndFlush(response);
```

**排查手段**：`netstat -s | grep "segments send out"` 观察发送量是否为 0；或在 `HeadContext.write()` 打断点确认消息是否到达。

---

### PIT-002：不检查 isWritable() 导致 OOM

| 项目 | 内容 |
|------|------|
| **严重级别** | 🔴 P0 |
| **现象** | 服务进程内存持续飙升，最终 OOM 崩溃 |
| **根因** | 下游消费慢时，`write()` 的数据堆积在 `ChannelOutboundBuffer` 中，`totalPendingSize` 无限增长 |
| **出处** | [第08章 §9.2](../08-写路径与背压/01-write-path-and-backpressure.md)、[第04章](../04-Channel与Unsafe/01-channel-hierarchy-and-data-structures.md) |

```java
// ❌ 错误：不检查 isWritable，疯狂 write
@Override
public void channelRead(ChannelHandlerContext ctx, Object msg) {
    ctx.writeAndFlush(processMsg(msg));  // 下游慢时无限堆积
}

// ✅ 正确：检查 isWritable + 监听 writabilityChanged
@Override
public void channelRead(ChannelHandlerContext ctx, Object msg) {
    if (ctx.channel().isWritable()) {
        ctx.writeAndFlush(processMsg(msg));
    } else {
        ReferenceCountUtil.release(msg);
        ctx.channel().config().setAutoRead(false);  // 暂停读取，背压传递
    }
}

@Override
public void channelWritabilityChanged(ChannelHandlerContext ctx) {
    if (ctx.channel().isWritable()) {
        ctx.channel().config().setAutoRead(true);   // 恢复读取
    }
}
```

**排查手段**：`jmap -histo:live <pid> | grep ChannelOutboundBuffer` 查看 Entry 数量；监控 `channel.unsafe().outboundBuffer().totalPendingSize()`。

---

### PIT-003：ByteBuf 忘记 release 导致内存泄漏

| 项目 | 内容 |
|------|------|
| **严重级别** | 🔴 P0 |
| **现象** | 堆外内存持续增长，`-XX:MaxDirectMemorySize` 耗尽后抛 `OutOfDirectMemoryError` |
| **根因** | Netty 的 `ByteBuf` 使用引用计数管理内存，`retain()` 加 1，`release()` 减 1，减到 0 时回收。忘记 release 则永不回收 |
| **出处** | [第18章](../18-引用计数与平台适配层/01-refcnt-and-platform.md)、[第24章](../24-内存泄漏排查/01-memory-leak-detection.md) |

**三种典型泄漏场景**：
```java
// 场景1：Handler 处理了消息但没 release 也没向后传播
@Override
public void channelRead(ChannelHandlerContext ctx, Object msg) {
    ByteBuf buf = (ByteBuf) msg;
    process(buf);
    // ❌ 既没有 ctx.fireChannelRead(msg)，也没有 buf.release()
}

// 场景2：ctx.write(buf) 后又手动 release（write 已经接管了 release 责任）
ctx.write(buf);
buf.release();  // ❌ 双重释放，可能导致其他线程读到已回收的内存

// 场景3：异常路径没有 release
try {
    ByteBuf buf = ctx.alloc().buffer(1024);
    encode(msg, buf);
    ctx.write(buf);
} catch (Exception e) {
    // ❌ buf 可能已分配但未 write，泄漏了！
}

// ✅ 正确：try-finally + 明确的 release 责任
ByteBuf buf = ctx.alloc().buffer(1024);
try {
    encode(msg, buf);
    ctx.write(buf);
    buf = null;  // write 接管了 release 责任
} finally {
    if (buf != null) buf.release();
}
```

**排查手段**：
```bash
# 开启泄漏检测（测试环境用 PARANOID，生产用 SIMPLE）
-Dio.netty.leakDetection.level=ADVANCED
-Dio.netty.leakDetection.targetRecords=20

# 看日志中的 LEAK 告警
grep "LEAK" app.log
```

**核心原则**：**最后使用者负责 release**。如果你处理了消息不再向后传播，就必须 release；如果向后传播了，下游负责。

---

### PIT-004：maxFrameLength 太大导致 OOM（大包攻击）

| 项目 | 内容 |
|------|------|
| **严重级别** | 🔴 P0 |
| **现象** | 恶意客户端发送超大帧，服务端分配巨大 ByteBuf 后 OOM |
| **根因** | `LengthFieldBasedFrameDecoder` 的 `maxFrameLength` 设为 `Integer.MAX_VALUE`，不限制帧大小 |
| **出处** | [第07章 §9.2](../07-编解码与粘包拆包/01-codec-and-framing.md) |

```java
// ❌ 错误：不限制帧大小
new LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 0, 4, 0, 4)

// ✅ 正确：根据业务最大消息大小设置合理上限
new LengthFieldBasedFrameDecoder(10 * 1024 * 1024, 0, 4, 0, 4)  // 最大 10MB
```

---

### PIT-005：exceptionCaught 未处理导致异常吞没

| 项目 | 内容 |
|------|------|
| **严重级别** | 🔴 P0 |
| **现象** | 业务逻辑出错但无任何日志，问题很难排查 |
| **根因** | 异常沿 `next` 方向传播到 `TailContext`，只打印一行 warn 日志后释放资源，很容易被忽略 |
| **出处** | [第05章](../05-Pipeline与Handler机制/01-pipeline-handler-mechanism.md) |

```java
// ✅ 正确：在 Pipeline 末尾添加统一异常处理 Handler
pipeline.addLast(new ChannelInboundHandlerAdapter() {
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Channel {} exception: ", ctx.channel().remoteAddress(), cause);
        ctx.close();  // 出错后关闭连接
    }
});
```

---

### PIT-006：encode() 中忘记 release 中间 ByteBuf

| 项目 | 内容 |
|------|------|
| **严重级别** | 🔴 P0 |
| **现象** | 堆外内存缓慢增长，泄漏检测报告指向编码器 |
| **根因** | 编码器中创建了临时 ByteBuf 写入 out，但忘记 release 临时 ByteBuf |
| **出处** | [第07章 §9.3](../07-编解码与粘包拆包/01-codec-and-framing.md) |

```java
// ❌ 错误
protected void encode(ChannelHandlerContext ctx, MyMessage msg, ByteBuf out) {
    ByteBuf header = ctx.alloc().buffer(8);
    header.writeLong(msg.getTimestamp());
    out.writeBytes(header);  // header 的数据拷贝到 out
    // ❌ header 没有 release！
}

// ✅ 正确
protected void encode(ChannelHandlerContext ctx, MyMessage msg, ByteBuf out) {
    ByteBuf header = ctx.alloc().buffer(8);
    try {
        header.writeLong(msg.getTimestamp());
        out.writeBytes(header);
    } finally {
        header.release();  // 必须 release
    }
}
```

---

### PIT-007：在 EventLoop 线程 sync()/await() 导致死锁

| 项目 | 内容 |
|------|------|
| **严重级别** | 🔴 P0 |
| **现象** | 连接无响应，`BlockingOperationException` 异常 |
| **根因** | EventLoop 是单线程，在其中阻塞等待自己完成的 IO 操作 = 死锁。Netty 通过 `checkDeadLock()` 检测并抛异常 |
| **出处** | [第16章](../16-Future与Promise异步模型/01-future-and-promise.md) |

```java
// ❌ 错误：在 Handler 中阻塞等待
@Override
public void channelRead(ChannelHandlerContext ctx, Object msg) {
    ChannelFuture f = ctx.writeAndFlush(response);
    f.sync();  // 💀 BlockingOperationException！
}

// ✅ 正确：使用 addListener 回调
@Override
public void channelRead(ChannelHandlerContext ctx, Object msg) {
    ctx.writeAndFlush(response).addListener(future -> {
        if (!future.isSuccess()) {
            log.error("Write failed", future.cause());
            ctx.close();
        }
    });
}
```

---

### PIT-008：taskQueue 无限增长导致 OOM

| 项目 | 内容 |
|------|------|
| **严重级别** | 🔴 P0 |
| **现象** | EventLoop 的 taskQueue 堆积百万级任务，堆内存耗尽 |
| **根因** | 业务线程向 EventLoop 提交任务的速度远超处理速度，Mpsc 队列默认无上限 |
| **出处** | [第03章](../03-线程模型-EventLoop/01-eventloop-hierarchy-and-creation.md) |

```java
// ❌ 错误：高频提交任务不做限流
for (int i = 0; i < 1_000_000; i++) {
    channel.eventLoop().execute(() -> doSomething());  // 任务堆积
}

// ✅ 正确方案1：检查队列大小
if (channel.eventLoop().pendingTasks() < 10000) {
    channel.eventLoop().execute(() -> doSomething());
} else {
    // 降级处理
}

// ✅ 正确方案2：使用 RejectedExecutionHandler
// 通过系统属性控制队列大小：-Dio.netty.eventLoop.maxPendingTasks=16384
```

---

### PIT-009：OPENSSL_REFCNT 的 SslContext 忘记 release

| 项目 | 内容 |
|------|------|
| **严重级别** | 🔴 P0 |
| **现象** | native 内存持续增长，Java 堆看不到泄漏 |
| **根因** | `OPENSSL_REFCNT` 模式下 `SslContext` 是引用计数对象，必须手动 release |
| **出处** | [第21章](../21-TLS与安全通信/01-tls-and-ssl-handler.md) |

```java
// ❌ 错误：使用 OPENSSL_REFCNT 但忘记 release
SslContext sslCtx = SslContextBuilder.forServer(cert, key)
    .sslProvider(SslProvider.OPENSSL_REFCNT).build();
// sslCtx 永远不被 release → native 内存泄漏

// ✅ 正确方案1：使用 OPENSSL（自动管理，推荐）
SslContext sslCtx = SslContextBuilder.forServer(cert, key)
    .sslProvider(SslProvider.OPENSSL).build();

// ✅ 正确方案2：如果必须用 OPENSSL_REFCNT，在关闭时 release
ReferenceCountedOpenSslContext refCtx = (ReferenceCountedOpenSslContext) sslCtx;
// ... 使用完毕后
refCtx.release();
```

---

### PIT-010：SslContext destroy() 后 SSLEngine 仍在使用导致 JVM Crash

| 项目 | 内容 |
|------|------|
| **严重级别** | 🔴 P0 |
| **现象** | JVM 直接崩溃（SIGSEGV），hs_err 日志指向 native 代码 |
| **根因** | `ctx` 是 native 指针，`destroy()` 后指针失效。如果还有 `SSLEngine` 在使用该指针，会导致 native 内存越界访问 |
| **出处** | [第21章](../21-TLS与安全通信/01-tls-and-ssl-handler.md) |

**防御措施**：确保所有使用 `SslContext` 创建的 `SSLEngine`（即所有连接的 `SslHandler`）都关闭后，再调用 `SslContext.close()`。使用 `shutdownGracefully()` 优雅关闭而非直接 destroy。

---

## 二、🟡 P1 级（性能劣化 / 资源浪费）

> **性能调优时必须参考，可能导致延迟飙升、资源浪费或功能异常。**

### PIT-011：容器中 availableProcessors() 返回宿主机核数

| 项目 | 内容 |
|------|------|
| **严重级别** | 🟡 P1 |
| **现象** | K8s Pod 限制 4 核，但创建了 128 个 EventLoop 线程（宿主机 64 核 × 2） |
| **根因** | `Runtime.getRuntime().availableProcessors()` 在老版本 JDK 容器中返回宿主机核数 |
| **出处** | [第03章](../03-线程模型-EventLoop/01-eventloop-hierarchy-and-creation.md)、[面试 Q29](../26-面试高频问答/01-interview-faq.md) |

```java
// ✅ 方案1：启动参数显式设置
// -Dio.netty.availableProcessors=4

// ✅ 方案2：代码中设置（必须在创建 EventLoopGroup 之前）
NettyRuntime.setAvailableProcessors(4);

// ✅ 方案3：JDK 10+ 使用容器感知参数
// -XX:ActiveProcessorCount=4
```

---

### PIT-012：SO_BACKLOG 太小导致连接拒绝

| 项目 | 内容 |
|------|------|
| **严重级别** | 🟡 P1 |
| **现象** | 高并发时客户端收到 `Connection refused`，服务端日志无明显异常 |
| **根因** | `SO_BACKLOG` 默认 128，实际 backlog = `min(SO_BACKLOG, /proc/sys/net/core/somaxconn)` |
| **出处** | [第10章](../10-连接生命周期与故障处理/01-connection-lifecycle-and-fault-handling.md)、[面试 Q30](../26-面试高频问答/01-interview-faq.md) |

```java
// ✅ Netty 配置
serverBootstrap.option(ChannelOption.SO_BACKLOG, 1024);

// ✅ 同时调整系统参数（否则 SO_BACKLOG 被 somaxconn 截断）
// sysctl -w net.core.somaxconn=1024
// sysctl -w net.ipv4.tcp_max_syn_backlog=2048
```

**排查手段**：
```bash
ss -lnt | grep <port>     # 查看 Send-Q（= backlog 上限）和 Recv-Q（= 当前积压）
netstat -s | grep overflow  # 查看 ACCEPT 队列溢出计数
```

---

### PIT-013：水位线设置不合理导致频繁抖动

| 项目 | 内容 |
|------|------|
| **严重级别** | 🟡 P1 |
| **现象** | `channelWritabilityChanged` 频繁触发，吞吐量下降 |
| **根因** | 高/低水位线差距太小（如 1KB/2KB），在临界值附近频繁切换可写状态 |
| **出处** | [第08章 §9.3](../08-写路径与背压/01-write-path-and-backpressure.md) |

```java
// ❌ 水位线太小
new WriteBufferWaterMark(1024, 2048);  // 1KB/2KB 太小

// ✅ 高吞吐场景
new WriteBufferWaterMark(512 * 1024, 1024 * 1024);  // 512KB/1MB

// ✅ 低延迟场景
new WriteBufferWaterMark(32 * 1024, 64 * 1024);  // 默认值
```

---

### PIT-014：writeSpinCount 设置不当

| 项目 | 内容 |
|------|------|
| **严重级别** | 🟡 P1 |
| **现象** | 少连接场景吞吐低（设太小）或多连接场景延迟高（设太大） |
| **根因** | `writeSpinCount` 控制每次 flush 最多尝试写几轮，默认 16 |
| **出处** | [第08章 §9.4](../08-写路径与背压/01-write-path-and-backpressure.md) |

```java
// 高吞吐（少连接）：可增大
bootstrap.childOption(ChannelOption.WRITE_SPIN_COUNT, 32);

// 高并发（多连接）：保持默认或减小
bootstrap.childOption(ChannelOption.WRITE_SPIN_COUNT, 8);
```

---

### PIT-015：心跳超时时间太短导致正常请求被误判

| 项目 | 内容 |
|------|------|
| **严重级别** | 🟡 P1 |
| **现象** | 正常连接被 IdleStateHandler 误判为超时并关闭 |
| **根因** | 读超时时间太短（如 5 秒），在 GC 停顿或网络抖动时触发 |
| **出处** | [第10章 §7.1](../10-连接生命周期与故障处理/01-connection-lifecycle-and-fault-handling.md)、[第11章 §10](../11-心跳与空闲检测/01-heartbeat-and-idle-detection.md) |

```java
// ❌ 读超时太短
new IdleStateHandler(5, 0, 0);  // 5秒，GC 一次就误判

// ✅ 正确：读超时 = 心跳间隔 × 3（允许丢失 2 次心跳）
new IdleStateHandler(90, 30, 0);
// 写空闲 30s 时发心跳，读空闲 90s 时关闭连接
```

---

### PIT-016：IdleStateHandler 不是 @Sharable 却被共享

| 项目 | 内容 |
|------|------|
| **严重级别** | 🟡 P1 |
| **现象** | 心跳检测时好时坏，多个连接的超时行为互相干扰 |
| **根因** | `lastReadTime`、`lastWriteTime` 等字段是实例变量，多 Channel 共享会互相覆盖 |
| **出处** | [第11章 §10.1](../11-心跳与空闲检测/01-heartbeat-and-idle-detection.md) |

```java
// ❌ 错误：共享同一个实例
IdleStateHandler shared = new IdleStateHandler(60, 30, 0);
bootstrap.childHandler(ch -> ch.pipeline().addLast(shared));

// ✅ 正确：每个 Channel 创建独立实例
bootstrap.childHandler(ch -> ch.pipeline().addLast(new IdleStateHandler(60, 30, 0)));
```

---

### PIT-017：observeOutput 默认 false 导致写积压误判

| 项目 | 内容 |
|------|------|
| **严重级别** | 🟡 P1 |
| **现象** | outboundBuffer 积压 100MB，但写空闲永远不触发 |
| **根因** | `write()` 调用更新了 `lastWriteTime`，但数据实际没发出去。`observeOutput=false` 时不检查真实输出 |
| **出处** | [第11章 §10.3](../11-心跳与空闲检测/01-heartbeat-and-idle-detection.md) |

```java
// ✅ 需要检测"写了但没发出去"场景时，开启 observeOutput
new IdleStateHandler(true, 60, 30, 0, TimeUnit.SECONDS);
//                   ↑ observeOutput=true
```

---

### PIT-018：decode() 消费了数据但没产出消息导致死循环

| 项目 | 内容 |
|------|------|
| **严重级别** | 🟡 P1 |
| **现象** | CPU 飙升，`ByteToMessageDecoder.callDecode()` 无限循环 |
| **根因** | `decode()` 移动了 `readerIndex`（消费了数据）但没向 `out` 添加消息，`callDecode` 认为有进展继续循环 |
| **出处** | [第07章 §9.1](../07-编解码与粘包拆包/01-codec-and-framing.md) |

```java
// ❌ 错误：消费了数据但没产出
int magic = in.readInt();  // 消费了 4 字节
if (magic != 0xCAFEBABE) return;  // 没产出，没回退 → 死循环

// ✅ 正确：先 mark 再 read，失败时 reset
in.markReaderIndex();
int magic = in.readInt();
if (magic != 0xCAFEBABE) {
    in.resetReaderIndex();  // 回退
    throw new CorruptedFrameException("Invalid magic");
}
```

---

### PIT-019：ByteToMessageDecoder 标注 @Sharable

| 项目 | 内容 |
|------|------|
| **严重级别** | 🟡 P1 |
| **现象** | 构造时直接抛 `IllegalStateException` |
| **根因** | `ByteToMessageDecoder` 有 `cumulation` 状态字段，构造函数中检查并禁止 @Sharable |
| **出处** | [第07章 §9.4](../07-编解码与粘包拆包/01-codec-and-framing.md) |

```java
// ❌ 编译通过但运行时抛异常
@Sharable
public class MyDecoder extends ByteToMessageDecoder { ... }

// ✅ 如果需要共享，使用 MessageToMessageDecoder（无状态）
// 或者每个 Pipeline 创建独立实例
```

---

### PIT-020：Subpage ByteBuf 忘记 release，numAvail 不增

| 项目 | 内容 |
|------|------|
| **严重级别** | 🟡 P1 |
| **现象** | Arena 不断创建新 Subpage，老 Subpage 无法回收 |
| **根因** | `PooledByteBuf.release()` 未调用，Subpage 的 `numAvail` 不增加，永远标记为"已分配" |
| **出处** | [第06章 04篇](../06-ByteBuf与内存池/04-pool-subpage.md) |

---

### PIT-021：非 FastThreadLocalThread 导致 PoolThreadCache 内存泄漏

| 项目 | 内容 |
|------|------|
| **严重级别** | 🟡 P1 |
| **现象** | 使用普通 JDK 线程调用 Netty 分配/释放，Arena 内存使用量持续增长 |
| **根因** | 非 `FastThreadLocalThread` 时 `FastThreadLocal.onRemoval()` 不会被调用，`PoolThreadCache.free()` 不执行 |
| **出处** | [第06章 05篇](../06-ByteBuf与内存池/05-pool-thread-cache-and-recycler.md) |

```java
// ✅ 方案1：使用 DefaultThreadFactory 创建线程（推荐）
ExecutorService pool = Executors.newFixedThreadPool(4, new DefaultThreadFactory("biz"));

// ✅ 方案2：定期手动 trim
PooledByteBufAllocator.DEFAULT.trimCurrentThreadCache();
```

---

### PIT-022：channelInactive 中重连时忘记释放资源

| 项目 | 内容 |
|------|------|
| **严重级别** | 🟡 P1 |
| **现象** | 每次断线重连后老 Channel 的 pending 请求 Promise 永远不完成 |
| **根因** | 重连前没有清理旧 Channel 的业务状态（pending 请求、定时任务等） |
| **出处** | [第10章 §7.2](../10-连接生命周期与故障处理/01-connection-lifecycle-and-fault-handling.md) |

```java
// ✅ 正确：先清理，再延迟重连
@Override
public void channelInactive(ChannelHandlerContext ctx) {
    // 1. 清理业务状态
    pendingRequests.forEach((id, promise) ->
            promise.setFailure(new ClosedChannelException()));
    pendingRequests.clear();

    // 2. 延迟重连（避免连接风暴）
    ctx.channel().eventLoop().schedule(() -> {
        bootstrap.connect(remoteAddress);
    }, 5, TimeUnit.SECONDS);
}
```

---

### PIT-023：半开连接导致僵尸连接

| 项目 | 内容 |
|------|------|
| **严重级别** | 🟡 P1 |
| **现象** | 对端崩溃后服务端持有大量 `isActive()=true` 但数据无法发送的"僵尸连接" |
| **根因** | 对端机器崩溃未发送 FIN，TCP 连接没有正常关闭 |
| **出处** | [第10章 §7.6](../10-连接生命周期与故障处理/01-connection-lifecycle-and-fault-handling.md) |

```java
// ✅ 推荐方案：应用层心跳 + TCP KeepAlive 双保险
bootstrap.childOption(ChannelOption.SO_KEEPALIVE, true);
pipeline.addLast(new IdleStateHandler(90, 30, 0));
pipeline.addLast(new HeartbeatHandler());
```

---

### PIT-024：优雅关闭时忘记等待 closeFuture

| 项目 | 内容 |
|------|------|
| **严重级别** | 🟡 P1 |
| **现象** | 进程退出时部分请求丢失，客户端收到 RST |
| **根因** | 直接关闭 EventLoopGroup，不等待 Channel 关闭完毕 |
| **出处** | [第10章 §7.4](../10-连接生命周期与故障处理/01-connection-lifecycle-and-fault-handling.md) |

```java
// ✅ 正确顺序
channel.closeFuture().sync();           // 1. 等待 Channel 关闭
bossGroup.shutdownGracefully().sync();  // 2. 关闭 boss
workerGroup.shutdownGracefully().sync();// 3. 关闭 worker
```

---

### PIT-025：每连接创建 HashedWheelTimer 导致线程爆炸

| 项目 | 内容 |
|------|------|
| **严重级别** | 🟡 P1 |
| **现象** | 线程数随连接数线性增长，超过 64 个实例时 Netty 打印 ERROR 告警 |
| **根因** | 每个 `HashedWheelTimer` 实例创建一个后台 Worker 线程 |
| **出处** | [第17章 §1.8](../17-并发工具箱-时间轮-Recycler-FastThreadLocal/01-concurrent-utils.md) |

```java
// ❌ 每连接一个 Timer
ch.pipeline().addLast(new IdleStateHandler(new HashedWheelTimer(), 60, 0, 0, TimeUnit.SECONDS));

// ✅ 全局共享一个 Timer
private static final HashedWheelTimer TIMER = new HashedWheelTimer();
ch.pipeline().addLast(new IdleStateHandler(TIMER, 60, 0, 0, TimeUnit.SECONDS));
```

---

### PIT-026：非池化堆内 ByteBuf + Epoll/io_uring 仍触发 copy

| 项目 | 内容 |
|------|------|
| **严重级别** | 🟡 P1 |
| **现象** | 使用 Epoll 但性能不如预期，存在额外的内存拷贝 |
| **根因** | Epoll 的 `writev` 需要直接内存地址，堆内 ByteBuf 必须先拷贝到堆外 |
| **出处** | [第09章](../09-写路径与背压机制-三种Transport对比/01-write-path-comparison-nio-epoll-iouring.md) |

```java
// ✅ 确保使用 PooledByteBufAllocator（默认分配堆外内存）
bootstrap.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
```

---

### PIT-027：HTTP/2 流控窗口耗尽导致请求卡住

| 项目 | 内容 |
|------|------|
| **严重级别** | 🟡 P1 |
| **现象** | gRPC 流式调用卡住不返回 |
| **根因** | 应用层不及时 consume bytes，本地流控窗口降为 0，对端无法发送数据 |
| **出处** | [第22章 02篇](../22-HTTP2编解码/02-http2-connection-stream-flowcontrol.md) |

---

### PIT-028：ENHANCE_YOUR_CALM 被对端限流

| 项目 | 内容 |
|------|------|
| **严重级别** | 🟡 P1 |
| **现象** | gRPC 收到 `ENHANCE_YOUR_CALM`（0xB）错误码 |
| **根因** | 客户端发送过多 RST_STREAM 或频繁重建 Stream，服务端认为行为异常 |
| **出处** | [第22章 01篇](../22-HTTP2编解码/01-http2-protocol-and-frame.md) |

---

### PIT-029：discardReadBytes() 高频调用触发内存拷贝

| 项目 | 内容 |
|------|------|
| **严重级别** | 🟡 P1 |
| **现象** | CPU 使用率偏高，热点在 `System.arraycopy` |
| **根因** | `discardReadBytes()` 触发内存拷贝（`setBytes`），高频调用开销大 |
| **出处** | [第06章 01篇](../06-ByteBuf与内存池/01-bytebuf-and-memory-pool.md) |

```java
// ❌ 每次 channelRead 都 discard
buf.discardReadBytes();  // 每次都触发 arraycopy

// ✅ 使用 discardSomeReadBytes()（只在 readerIndex 超过容量一半时才压缩）
buf.discardSomeReadBytes();
```

---

## 三、🟢 P2 级（不规范 / 潜在风险）

> **代码审查时参考，短期不会出事但长期有隐患。**

### PIT-030：在非 EventLoop 线程调用 write 的额外开销

| 项目 | 内容 |
|------|------|
| **严重级别** | 🟢 P2 |
| **根因** | 虽然 Netty 会自动提交到 EventLoop 执行，但封装 task + 入队 + wakeup 有额外开销 |
| **出处** | [第08章 §9.5](../08-写路径与背压/01-write-path-and-backpressure.md) |

**建议**：热路径上尽量在 `channelRead()` 等已在 EventLoop 线程的回调中写入。

---

### PIT-031：心跳包格式与业务包不一致导致解码失败

| 项目 | 内容 |
|------|------|
| **严重级别** | 🟢 P2 |
| **根因** | 心跳包绕过了 Decoder 的协议格式，导致 Decoder 解码失败 |
| **出处** | [第11章 §10.2](../11-心跳与空闲检测/01-heartbeat-and-idle-detection.md) |

**建议**：心跳包必须符合协议格式（加长度头 + 类型标识），与业务包走同一个 Decoder。

---

### PIT-032：连接超时用 await() 代替 CONNECT_TIMEOUT_MILLIS

| 项目 | 内容 |
|------|------|
| **严重级别** | 🟢 P2 |
| **根因** | `future.await(timeout)` 是等待 Future 完成的超时，不是 TCP 连接超时。TCP 层面可能已经在 SYN 重传 |
| **出处** | [第10章 §7.3](../10-连接生命周期与故障处理/01-connection-lifecycle-and-fault-handling.md) |

```java
// ✅ 正确：用 CONNECT_TIMEOUT_MILLIS
bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000);
```

---

### PIT-033：channelActive 中发数据但 TLS 握手未完成

| 项目 | 内容 |
|------|------|
| **严重级别** | 🟢 P2 |
| **根因** | TLS 场景下 `channelActive` 触发时 TLS 握手可能还未完成，此时写数据会失败 |
| **出处** | [第10章 §7.7](../10-连接生命周期与故障处理/01-connection-lifecycle-and-fault-handling.md) |

**建议**：TLS 场景下应监听 `SslHandshakeCompletionEvent`，确认握手成功后再发数据。

---

### PIT-034：Recycler 重复回收抛异常

| 项目 | 内容 |
|------|------|
| **严重级别** | 🟢 P2 |
| **根因** | `GuardedLocalPool` 检测到重复 recycle 时抛 `IllegalStateException` |
| **出处** | [第17章 §3.8](../17-并发工具箱-时间轮-Recycler-FastThreadLocal/01-concurrent-utils.md) |

```java
// ❌ 重复回收
obj.recycle();
obj.recycle();  // IllegalStateException: Object has been recycled already.
```

---

### PIT-035：FastThreadLocal 在普通线程降级为 JDK ThreadLocal

| 项目 | 内容 |
|------|------|
| **严重级别** | 🟢 P2 |
| **根因** | 非 `FastThreadLocalThread` 时退化为 JDK ThreadLocal + HashMap 查找，失去 O(1) 数组访问优势 |
| **出处** | [第17章 §2.6](../17-并发工具箱-时间轮-Recycler-FastThreadLocal/01-concurrent-utils.md) |

**建议**：使用 `DefaultThreadFactory` 创建线程，自动使用 `FastThreadLocalThread`。

---

### PIT-036：误以为 Netty 分配的内存大小等于请求大小

| 项目 | 内容 |
|------|------|
| **严重级别** | 🟢 P2 |
| **根因** | SizeClasses 会将请求大小对齐到最近的档位（如请求 100B 实际分配 112B），碎片率最大 12.5% |
| **出处** | [第06章 02篇](../06-ByteBuf与内存池/02-size-classes.md) |

---

### PIT-037：freeSweepAllocationThreshold 调优不当

| 项目 | 内容 |
|------|------|
| **严重级别** | 🟢 P2 |
| **根因** | 默认 8192，分配频率极高时可增大减少 trim 频率；内存敏感时减小更积极归还冷缓存 |
| **出处** | [第06章 05篇](../06-ByteBuf与内存池/05-pool-thread-cache-and-recycler.md) |

---

### PIT-038：PARANOID 模式用于生产导致吞吐量下降 50%+

| 项目 | 内容 |
|------|------|
| **严重级别** | 🟢 P2 |
| **根因** | `PARANOID` 对每个 ByteBuf 都创建 `DefaultResourceLeak`，开销极大 |
| **出处** | [第24章](../24-内存泄漏排查/01-memory-leak-detection.md) |

```java
// ❌ 生产环境用 PARANOID
-Dio.netty.leakDetection.level=PARANOID

// ✅ 生产用 SIMPLE（默认），开发/测试用 ADVANCED 或 PARANOID
-Dio.netty.leakDetection.level=SIMPLE
```

---

### PIT-039：AdaptiveAllocator 仍是实验性 API

| 项目 | 内容 |
|------|------|
| **严重级别** | 🟢 P2 |
| **根因** | 4.2 新引入的 `AdaptivePoolingAllocator` 虽然是新默认值，但仍标记为实验性，生产稳定性待验证 |
| **出处** | [第20章 §16.4](../20-自适应内存分配器AdaptiveAllocator/01-adaptive-pooling-allocator.md) |

**建议**：如果追求稳定性，可显式指定 `PooledByteBufAllocator.DEFAULT`。

---

### PIT-040：io_uring 误把"支持"当"启用"

| 项目 | 内容 |
|------|------|
| **严重级别** | 🟢 P2 |
| **根因** | `IoUring.isAvailable()` = 内核支持；但实际启用还需要 Netty 的 native 库加载成功。两层语义不同 |
| **出处** | [第13章](../13-Native-Transport-io_uring/01-native-transport-io_uring.md) |

---

### PIT-041：业务逻辑在 EventLoop 线程执行导致阻塞

| 项目 | 内容 |
|------|------|
| **严重级别** | 🟢 P2 |
| **根因** | 在 `channelRead` 中执行数据库查询、RPC 调用等耗时操作，阻塞 EventLoop |
| **出处** | [第25章 §8.3](../25-框架复用-Dubbo-gRPC-WebFlux/01-framework-reuse.md) |

```java
// ❌ 错误：在 EventLoop 中执行阻塞操作
@Override
public void channelRead(ChannelHandlerContext ctx, Object msg) {
    String result = database.query(msg);  // 💀 阻塞 EventLoop
    ctx.writeAndFlush(result);
}

// ✅ 正确：提交到业务线程池
@Override
public void channelRead(ChannelHandlerContext ctx, Object msg) {
    bizExecutor.execute(() -> {
        String result = database.query(msg);
        ctx.writeAndFlush(result);  // Netty 自动提交到 EventLoop
    });
}
```

---

## 四、上线前检查清单 ✅

> 把以下检查项加入 CI/CD Pipeline 或 Code Review Checklist。

### 🔴 P0 必检项（上线前 100% 覆盖）

- [ ] **PIT-001**：所有 `write()` 后都有配对的 `flush()` 或使用 `writeAndFlush()`
- [ ] **PIT-002**：所有写入路径都检查了 `isWritable()` 或监听了 `channelWritabilityChanged`
- [ ] **PIT-003**：所有 `ByteBuf` 的 `retain/release` 配对正确，使用 `ADVANCED` 级别跑过测试
- [ ] **PIT-004**：`LengthFieldBasedFrameDecoder` 设置了合理的 `maxFrameLength`
- [ ] **PIT-005**：Pipeline 末尾有统一的异常处理 Handler
- [ ] **PIT-006**：编码器中的临时 ByteBuf 在 finally 块中 release
- [ ] **PIT-007**：Handler 中没有 `sync()/await()` 调用
- [ ] **PIT-008**：评估了 taskQueue 堆积风险，高频场景有限流措施
- [ ] **PIT-009**：TLS 使用 `OPENSSL` 而非 `OPENSSL_REFCNT`（或确保正确 release）
- [ ] **PIT-010**：关闭顺序正确——先关连接，再关 SslContext

### 🟡 P1 建议检项（上线前尽量覆盖）

- [ ] **PIT-011**：容器环境设置了 `io.netty.availableProcessors` 或 `ActiveProcessorCount`
- [ ] **PIT-012**：`SO_BACKLOG` 设置 ≥ 1024，同步调整了 `somaxconn`
- [ ] **PIT-013**：`WriteBufferWaterMark` 根据消息大小和场景调整
- [ ] **PIT-015**：心跳超时 ≥ 心跳间隔 × 3
- [ ] **PIT-016**：`IdleStateHandler` 每个 Channel 独立实例
- [ ] **PIT-023**：同时配置了应用层心跳 + TCP KeepAlive
- [ ] **PIT-025**：`HashedWheelTimer` 全局共享而非每连接创建

### 🟢 P2 审查项（Code Review 时关注）

- [ ] **PIT-030**：热路径写入在 EventLoop 回调中执行
- [ ] **PIT-031**：心跳包格式符合协议规范
- [ ] **PIT-038**：生产环境泄漏检测级别为 `SIMPLE`
- [ ] **PIT-041**：耗时业务逻辑提交到独立线程池

---

## 五、快速排查命令表

| 场景 | 命令 | 说明 |
|------|------|------|
| 内存泄漏 | `grep "LEAK" app.log` | 查找 ResourceLeakDetector 告警 |
| 堆外内存 | `jcmd <pid> VM.native_memory summary` | 查看 native 内存分配 |
| 连接积压 | `ss -lnt \| grep <port>` | 查看 backlog 队列（Send-Q/Recv-Q） |
| ACCEPT 溢出 | `netstat -s \| grep overflow` | 查看队列溢出计数 |
| 线程堆栈 | `jstack <pid> \| grep -A 20 "nioEventLoop"` | 查看 EventLoop 在做什么 |
| ByteBuf 泄漏 | `jmap -histo:live <pid> \| grep -i bytebuf` | 查看 ByteBuf 对象数量 |
| 写缓冲积压 | 监控 `channel.unsafe().outboundBuffer().totalPendingSize()` | 查看 ChannelOutboundBuffer 大小 |
| TCP 状态 | `ss -tn state established \| wc -l` | 查看活跃连接数 |
| EventLoop 任务堆积 | 监控 `eventLoop.pendingTasks()` | 查看任务队列深度 |
| GC 停顿 | `-XX:+PrintGCApplicationStoppedTime` 或 GC 日志 | 判断心跳超时是否由 GC 引起 |
