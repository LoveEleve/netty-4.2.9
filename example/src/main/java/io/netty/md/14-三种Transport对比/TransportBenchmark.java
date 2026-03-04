package io.netty.md.ch13;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 三种 Transport 横向对比基准测试：NIO vs Epoll vs io_uring
 *
 * 测试场景：
 *   场景1：Echo Ping-Pong 延迟（单连接，1字节，测 RTT）
 *   场景2：多连接并发吞吐（N 个连接同时发消息，服务端计数，测单向吞吐）
 *   场景3：大报文传输吞吐（单连接，大 ByteBuf，测带宽）
 *
 * 运行方式：
 *   直接 javac + java 运行，无需 JMH 框架
 *   自动检测平台可用性，跳过不支持的 Transport
 *
 * 注意：
 *   - 这是简化版基准测试，用于教学目的，帮助直观理解三种 Transport 的性能差异
 *   - 生产级基准测试请使用 JMH，参见本文件末尾的说明
 *   - 结果受 CPU、内核版本、JDK 版本、系统负载等因素影响
 */
public class TransportBenchmark {

    // ===== 测试参数 =====
    static final int WARMUP_ROUNDS   = 2_000;    // 预热轮次
    static final int PINGPONG_ROUNDS = 10_000;   // Ping-Pong 测试轮次
    static final int CONCURRENT_CONNS = 5;        // 并发连接数
    static final int CONCURRENT_MSGS = 1_000;    // 每连接消息数
    static final int LARGE_MSG_SIZE  = 64 * 1024; // 大报文大小 64KB
    static final int LARGE_MSG_COUNT = 1_000;     // 大报文发送次数

    // ===== Transport 配置 =====
    static class TransportConfig {
        final String name;
        final IoHandlerFactory ioHandlerFactory;
        final Class<? extends ServerSocketChannel> serverChannelClass;
        final Class<? extends SocketChannel> clientChannelClass;

        @SuppressWarnings("unchecked")
        TransportConfig(String name, IoHandlerFactory factory,
                        Class<?> serverClass, Class<?> clientClass) {
            this.name = name;
            this.ioHandlerFactory = factory;
            this.serverChannelClass = (Class<? extends ServerSocketChannel>) serverClass;
            this.clientChannelClass = (Class<? extends SocketChannel>) clientClass;
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("==========================================================");
        System.out.println("  Netty 4.2.9 Transport Benchmark: NIO vs Epoll vs io_uring");
        System.out.println("==========================================================");
        System.out.println();

        // 检测可用 Transport
        List<TransportConfig> transports = detectTransports();
        if (transports.isEmpty()) {
            System.err.println("没有可用的 Transport，退出");
            return;
        }
        System.out.println("检测到 " + transports.size() + " 种可用 Transport：");
        for (TransportConfig t : transports) {
            System.out.println("  [OK] " + t.name);
        }
        System.out.println();

        // 收集结果
        Map<String, Map<String, String>> results = new LinkedHashMap<>();

        // ===== 场景1：Echo Ping-Pong 延迟 =====
        System.out.println("------------------------------------------------------------");
        System.out.println("场景1：Echo Ping-Pong 延迟（单连接，1字节，" + PINGPONG_ROUNDS + " 轮）");
        System.out.println("------------------------------------------------------------");
        for (TransportConfig tc : transports) {
            Map<String, String> r = results.computeIfAbsent(tc.name, k -> new LinkedHashMap<>());
            double avgLatencyUs = benchPingPong(tc);
            r.put("ping-pong延迟", String.format("%.1f us", avgLatencyUs));
            r.put("ping-pong-ops", String.format("%.0f", 1_000_000.0 / avgLatencyUs));
            System.out.printf("  %-12s 平均延迟: %.1f us  (%.0f ops/s)%n",
                    tc.name, avgLatencyUs, 1_000_000.0 / avgLatencyUs);
        }
        System.out.println();

        // ===== 场景2：多连接并发吞吐（单向发送，服务端计数）=====
        System.out.println("------------------------------------------------------------");
        System.out.printf("场景2：多连接单向吞吐（%d 连接，每连接 %d 条消息，32字节）%n",
                CONCURRENT_CONNS, CONCURRENT_MSGS);
        System.out.println("------------------------------------------------------------");
        for (TransportConfig tc : transports) {
            Map<String, String> r = results.computeIfAbsent(tc.name, k -> new LinkedHashMap<>());
            double throughput = benchConcurrent(tc);
            r.put("并发吞吐", String.format("%.0f msg/s", throughput));
            System.out.printf("  %-12s 吞吐: %.0f msg/s%n", tc.name, throughput);
        }
        System.out.println();

        // ===== 场景3：大报文传输吞吐 =====
        System.out.println("------------------------------------------------------------");
        System.out.printf("场景3：大报文传输（单连接，%dKB x %d 次）%n",
                LARGE_MSG_SIZE / 1024, LARGE_MSG_COUNT);
        System.out.println("------------------------------------------------------------");
        for (TransportConfig tc : transports) {
            Map<String, String> r = results.computeIfAbsent(tc.name, k -> new LinkedHashMap<>());
            double mbps = benchLargeMessage(tc);
            r.put("大报文吞吐", String.format("%.1f MB/s", mbps));
            System.out.printf("  %-12s 吞吐: %.1f MB/s%n", tc.name, mbps);
        }
        System.out.println();

        // ===== 汇总表格 =====
        printSummaryTable(transports, results);
    }

    // ==================== 场景1：Ping-Pong 延迟 ====================

    static double benchPingPong(TransportConfig tc) throws Exception {
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, tc.ioHandlerFactory);
        try {
            // Echo Server：原样回写
            Channel server = new ServerBootstrap()
                    .group(group)
                    .channel(tc.serverChannelClass)
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                    ctx.writeAndFlush(msg, ctx.voidPromise());
                                }
                            });
                        }
                    })
                    .bind(0).sync().channel();

            InetSocketAddress addr = (InetSocketAddress) server.localAddress();

            // 客户端：每收到一个 echo 就 countDown
            final CountDownLatch[] latch = {new CountDownLatch(1)};
            Channel client = new Bootstrap()
                    .group(group)
                    .channel(tc.clientChannelClass)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                    ((ByteBuf) msg).release();
                                    latch[0].countDown();
                                }
                            });
                        }
                    })
                    .connect(addr).sync().channel();

            ByteBuf payload = Unpooled.directBuffer(1).writeByte('x');

            // 预热
            for (int i = 0; i < WARMUP_ROUNDS; i++) {
                latch[0] = new CountDownLatch(1);
                client.writeAndFlush(payload.retainedSlice());
                latch[0].await(5, TimeUnit.SECONDS);
            }

            // 正式测试：发一条等一条回来，测 RTT
            long start = System.nanoTime();
            for (int i = 0; i < PINGPONG_ROUNDS; i++) {
                latch[0] = new CountDownLatch(1);
                client.writeAndFlush(payload.retainedSlice());
                latch[0].await(5, TimeUnit.SECONDS);
            }
            long elapsed = System.nanoTime() - start;

            payload.release();
            client.close().sync();
            server.close().sync();

            return (double) elapsed / PINGPONG_ROUNDS / 1000.0; // 纳秒 -> 微秒
        } finally {
            group.shutdownGracefully(0, 0, TimeUnit.SECONDS).sync();
        }
    }

    // ==================== 场景2：多连接单向吞吐 ====================

    /**
     * 改为单向发送模式（不 echo），避免 echo 模式下的同步等待瓶颈。
     * 服务端只负责接收和计数，客户端全速发送。
     */
    static double benchConcurrent(TransportConfig tc) throws Exception {
        EventLoopGroup bossGroup = new MultiThreadIoEventLoopGroup(1, tc.ioHandlerFactory);
        int workers = Math.min(Runtime.getRuntime().availableProcessors(), 4);
        EventLoopGroup serverWorkerGroup = new MultiThreadIoEventLoopGroup(workers, tc.ioHandlerFactory);
        EventLoopGroup clientGroup = new MultiThreadIoEventLoopGroup(workers, tc.ioHandlerFactory);
        try {
            final long totalExpected = (long) CONCURRENT_CONNS * CONCURRENT_MSGS;
            final AtomicLong serverReceived = new AtomicLong(0);
            final CountDownLatch doneLatch = new CountDownLatch(1);

            // 服务端：接收消息，计数达到 totalExpected 后通知完成
            Channel server = new ServerBootstrap()
                    .group(bossGroup, serverWorkerGroup)
                    .channel(tc.serverChannelClass)
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                    ((ByteBuf) msg).release();
                                    if (serverReceived.incrementAndGet() >= totalExpected) {
                                        doneLatch.countDown();
                                    }
                                }
                            });
                        }
                    })
                    .bind(0).sync().channel();

            InetSocketAddress addr = (InetSocketAddress) server.localAddress();

            // 创建 N 个客户端连接
            List<Channel> clients = new ArrayList<>();
            for (int c = 0; c < CONCURRENT_CONNS; c++) {
                Channel client = new Bootstrap()
                        .group(clientGroup)
                        .channel(tc.clientChannelClass)
                        .handler(new ChannelInitializer<Channel>() {
                            @Override
                            protected void initChannel(Channel ch) {
                                ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
                            }
                        })
                        .connect(addr).sync().channel();
                clients.add(client);
            }

            ByteBuf payload = Unpooled.directBuffer(32);
            payload.writeBytes(new byte[32]);

            // 预热：每连接发 200 条（批量 flush）
            for (int i = 0; i < 200; i++) {
                for (Channel client : clients) {
                    client.write(payload.retainedSlice());
                }
                if (i % 10 == 9) {
                    for (Channel client : clients) { client.flush(); }
                }
            }
            for (Channel client : clients) { client.flush(); }
            // 等预热消息被处理完
            Thread.sleep(500);
            serverReceived.set(0);

            // 正式测试：write 积累 + 每 BATCH_SIZE 条 flush 一次
            final int BATCH_SIZE = 50;
            long start = System.nanoTime();
            for (int i = 0; i < CONCURRENT_MSGS; i++) {
                for (Channel client : clients) {
                    client.write(payload.retainedSlice());
                }
                if (i % BATCH_SIZE == BATCH_SIZE - 1) {
                    for (Channel client : clients) { client.flush(); }
                }
            }
            for (Channel client : clients) { client.flush(); }
            doneLatch.await(60, TimeUnit.SECONDS);
            long elapsed = System.nanoTime() - start;

            payload.release();
            for (Channel client : clients) {
                client.close().sync();
            }
            server.close().sync();

            return totalExpected / (elapsed / 1_000_000_000.0);
        } finally {
            clientGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS).sync();
            serverWorkerGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS).sync();
            bossGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS).sync();
        }
    }

    // ==================== 场景3：大报文传输 ====================

    static double benchLargeMessage(TransportConfig tc) throws Exception {
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(2, tc.ioHandlerFactory);
        try {
            final AtomicLong totalBytesReceived = new AtomicLong(0);
            final CountDownLatch doneLatch = new CountDownLatch(1);
            final long expectedBytes = (long) LARGE_MSG_SIZE * LARGE_MSG_COUNT;

            Channel server = new ServerBootstrap()
                    .group(group)
                    .channel(tc.serverChannelClass)
                    .childOption(ChannelOption.SO_RCVBUF, 256 * 1024)
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                    ByteBuf buf = (ByteBuf) msg;
                                    long total = totalBytesReceived.addAndGet(buf.readableBytes());
                                    buf.release();
                                    if (total >= expectedBytes) {
                                        doneLatch.countDown();
                                    }
                                }
                            });
                        }
                    })
                    .bind(0).sync().channel();

            InetSocketAddress addr = (InetSocketAddress) server.localAddress();

            Channel client = new Bootstrap()
                    .group(group)
                    .channel(tc.clientChannelClass)
                    .option(ChannelOption.SO_SNDBUF, 256 * 1024)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
                        }
                    })
                    .connect(addr).sync().channel();

            // 准备大报文
            byte[] data = new byte[LARGE_MSG_SIZE];
            for (int i = 0; i < data.length; i++) data[i] = (byte) (i & 0xFF);
            ByteBuf payload = Unpooled.directBuffer(LARGE_MSG_SIZE);
            payload.writeBytes(data);

            // 预热
            for (int i = 0; i < 100; i++) {
                client.writeAndFlush(payload.retainedSlice()).sync();
            }
            Thread.sleep(200);
            totalBytesReceived.set(0);

            // 正式测试：write 积累 + 每 50 条 flush 一次
            long start = System.nanoTime();
            for (int i = 0; i < LARGE_MSG_COUNT; i++) {
                client.write(payload.retainedSlice());
                if (i % 50 == 49) {
                    client.flush();
                }
            }
            client.flush();
            doneLatch.await(60, TimeUnit.SECONDS);
            long elapsed = System.nanoTime() - start;

            payload.release();
            client.close().sync();
            server.close().sync();

            double seconds = elapsed / 1_000_000_000.0;
            return (expectedBytes / 1024.0 / 1024.0) / seconds;
        } finally {
            group.shutdownGracefully(0, 0, TimeUnit.SECONDS).sync();
        }
    }

    // ==================== Transport 自动检测 ====================

    static List<TransportConfig> detectTransports() {
        List<TransportConfig> list = new ArrayList<>();

        // NIO（始终可用）
        list.add(new TransportConfig("NIO",
                NioIoHandler.newFactory(),
                NioServerSocketChannel.class,
                NioSocketChannel.class));

        // Epoll（Linux only）
        try {
            Class<?> epollClass = Class.forName("io.netty.channel.epoll.Epoll");
            boolean available = (Boolean) epollClass.getMethod("isAvailable").invoke(null);
            if (available) {
                Class<?> handlerClass = Class.forName("io.netty.channel.epoll.EpollIoHandler");
                IoHandlerFactory factory = (IoHandlerFactory) handlerClass
                        .getMethod("newFactory").invoke(null);
                Class<?> serverCh = Class.forName("io.netty.channel.epoll.EpollServerSocketChannel");
                Class<?> clientCh = Class.forName("io.netty.channel.epoll.EpollSocketChannel");
                list.add(new TransportConfig("Epoll", factory, serverCh, clientCh));
            }
        } catch (Exception e) {
            System.out.println("  Epoll 不可用: " + e.getMessage());
        }

        // io_uring（Linux 5.1+ only）
        try {
            Class<?> uringClass = Class.forName("io.netty.channel.uring.IoUring");
            boolean available = (Boolean) uringClass.getMethod("isAvailable").invoke(null);
            if (available) {
                Class<?> handlerClass = Class.forName("io.netty.channel.uring.IoUringIoHandler");
                IoHandlerFactory factory = (IoHandlerFactory) handlerClass
                        .getMethod("newFactory").invoke(null);
                Class<?> serverCh = Class.forName("io.netty.channel.uring.IoUringServerSocketChannel");
                Class<?> clientCh = Class.forName("io.netty.channel.uring.IoUringSocketChannel");
                list.add(new TransportConfig("io_uring", factory, serverCh, clientCh));
            }
        } catch (Exception e) {
            System.out.println("  io_uring 不可用: " + e.getMessage());
        }

        return list;
    }

    // ==================== 汇总表格 ====================

    static void printSummaryTable(List<TransportConfig> transports,
                                  Map<String, Map<String, String>> results) {
        System.out.println("============================================================");
        System.out.println("                      性能对比汇总表");
        System.out.println("------------------------------------------------------------");
        System.out.printf("%-12s | %-16s | %-16s | %-14s%n",
                "Transport", "Ping-Pong延迟", "并发吞吐(msg/s)", "大报文(MB/s)");
        System.out.println("------------------------------------------------------------");
        for (TransportConfig tc : transports) {
            Map<String, String> r = results.get(tc.name);
            System.out.printf("%-12s | %-16s | %-16s | %-14s%n",
                    tc.name,
                    r.getOrDefault("ping-pong延迟", "N/A"),
                    r.getOrDefault("并发吞吐", "N/A"),
                    r.getOrDefault("大报文吞吐", "N/A"));
        }
        System.out.println("============================================================");
        System.out.println();
        System.out.println("测试环境：");
        System.out.println("  OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        System.out.println("  JDK: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        System.out.println("  CPU cores: " + Runtime.getRuntime().availableProcessors());
        System.out.println();
        System.out.println("注意事项：");
        System.out.println("  1. 这是简化版基准测试，仅供教学对比，非严格性能评测");
        System.out.println("  2. 结果受CPU/内核版本/JDK/系统负载/容器限制等因素影响");
        System.out.println("  3. 严格测试请使用JMH框架，确保JIT预热、GC控制等");
        System.out.println("  4. io_uring的优势在高IOPS+高并发场景下更明显");
        System.out.println("  5. Loopback网络下延迟差异被放大，真实网络中差距更小");
    }
}
