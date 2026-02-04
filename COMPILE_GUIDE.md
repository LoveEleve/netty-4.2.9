# Netty 4.2.9 编译指南

## 📋 目录
- [常见问题](#常见问题)
- [编译方案](#编译方案)
- [Native 模块说明](#native-模块说明)
- [故障排除](#故障排除)

---

## 🔍 常见问题

### 1. SSL 握手失败
**错误信息**: `Received fatal alert: handshake_failure`

**原因**: Maven 中央仓库要求 TLS 1.2+，旧版本 Java 可能不支持

**解决方案**:
```bash
# 方案 A: 升级 Java 到 11+ (推荐)
# 方案 B: 配置 Maven 使用镜像仓库（如阿里云）
```

### 2. Native 模块编译失败
**原因**: Native 模块需要特定平台和编译工具

**哪些是 Native 模块**:
- `transport-native-epoll` - Linux 专用，需要 epoll 支持
- `transport-native-kqueue` - macOS/BSD 专用
- `transport-native-io_uring` - Linux 5.1+ 专用
- `transport-native-unix-common` - Unix 通用基础
- `codec-native-quic` - QUIC 协议支持
- `resolver-dns-native-macos` - macOS DNS 解析

**解决方案**: 跳过 native 模块编译（见下方编译方案）

---

## 🚀 编译方案

### 方案 1: 使用编译脚本（推荐）

我已经为你创建了 `compile-netty.sh` 脚本：

```bash
# 跳过 native 模块编译（推荐用于学习源码）
./compile-netty.sh skip-native

# 只编译核心模块
./compile-netty.sh core-only

# 只编译不安装
./compile-netty.sh compile-only

# 完整编译（需要编译工具）
./compile-netty.sh full
```

### 方案 2: 手动命令

#### 2.1 跳过 Native 模块
```bash
./mvnw clean install -DskipTests \
  -pl '!transport-native-epoll' \
  -pl '!transport-native-kqueue' \
  -pl '!transport-native-io_uring' \
  -pl '!transport-native-unix-common-tests' \
  -pl '!codec-native-quic' \
  -pl '!resolver-dns-native-macos'
```

#### 2.2 只编译特定模块
```bash
# 只编译核心模块
./mvnw clean install -DskipTests \
  -pl common,buffer,transport,codec,codec-http,handler

# 编译某个模块及其依赖
./mvnw clean install -DskipTests -pl codec-http -am
```

#### 2.3 在 IDE 中导入
```bash
# 生成 IDEA 项目文件
./mvnw idea:idea

# 生成 Eclipse 项目文件
./mvnw eclipse:eclipse
```

### 方案 3: 配置 Maven 镜像（解决下载问题）

如果遇到依赖下载慢或失败，配置阿里云镜像：

创建或编辑 `~/.m2/settings.xml`:
```xml
<settings>
  <mirrors>
    <mirror>
      <id>aliyun</id>
      <mirrorOf>central</mirrorOf>
      <name>Aliyun Maven</name>
      <url>https://maven.aliyun.com/repository/public</url>
    </mirror>
  </mirrors>
</settings>
```

---

## 🔧 Native 模块说明

### 为什么有 Native 模块？

Netty 的 native 模块提供了平台特定的高性能实现：

1. **transport-native-epoll** (Linux)
   - 使用 Linux epoll 系统调用
   - 比 NIO 性能更好
   - 需要: gcc, autoconf, automake, libtool

2. **transport-native-kqueue** (macOS/BSD)
   - 使用 kqueue 系统调用
   - macOS 上的高性能实现

3. **transport-native-io_uring** (Linux 5.1+)
   - 使用最新的 io_uring 接口
   - 最高性能的 I/O 实现

### 是否需要编译 Native 模块？

**不需要，如果你**:
- ✅ 只是学习 Netty 源码
- ✅ 使用 NIO 传输层就够了
- ✅ 在 Windows 上开发

**需要，如果你**:
- ❌ 需要在生产环境获得最佳性能
- ❌ 需要使用 epoll/kqueue 特性
- ❌ 需要运行完整的测试套件

### 如何编译 Native 模块？

如果确实需要编译 native 模块，需要安装编译工具：

**Linux (Ubuntu/Debian)**:
```bash
sudo apt-get install -y \
  autoconf automake libtool make gcc \
  libssl-dev
```

**Linux (CentOS/RHEL)**:
```bash
sudo yum install -y \
  autoconf automake libtool make gcc \
  openssl-devel
```

**macOS**:
```bash
brew install autoconf automake libtool
```

然后执行完整编译：
```bash
./mvnw clean install -DskipTests
```

---

## 🐛 故障排除

### 问题 1: 内存不足
```bash
# 增加 Maven 内存
export MAVEN_OPTS="-Xmx2048m -XX:MaxPermSize=512m"
./mvnw clean install -DskipTests
```

### 问题 2: 编译某个模块失败
```bash
# 跳过该模块继续编译
./mvnw clean install -DskipTests -pl '!失败的模块名'

# 或者只编译成功的模块
./mvnw clean install -DskipTests -rf :从某个模块继续
```

### 问题 3: 测试失败
```bash
# 跳过所有测试
./mvnw clean install -DskipTests

# 或者
./mvnw clean install -Dmaven.test.skip=true
```

### 问题 4: 依赖下载失败
```bash
# 清理本地仓库缓存
rm -rf ~/.m2/repository/io/netty

# 使用阿里云镜像（见上方配置）

# 或者使用代理
./mvnw clean install -DskipTests \
  -Dhttp.proxyHost=代理地址 \
  -Dhttp.proxyPort=代理端口
```

### 问题 5: 查看详细错误信息
```bash
# 显示详细日志
./mvnw clean install -DskipTests -X

# 显示错误堆栈
./mvnw clean install -DskipTests -e
```

---

## 📚 推荐的学习流程

1. **先跳过 native 模块编译**
   ```bash
   ./compile-netty.sh skip-native
   ```

2. **在 IDE 中导入项目**
   - IntelliJ IDEA: File → Open → 选择 pom.xml
   - Eclipse: Import → Maven → Existing Maven Projects

3. **从核心模块开始阅读**
   - `common` - 通用工具类
   - `buffer` - ByteBuf 实现
   - `transport` - 传输层抽象
   - `codec` - 编解码器
   - `handler` - 处理器

4. **运行示例代码**
   ```bash
   cd example
   # 查看可用示例
   ls -la src/main/java/io/netty/example/
   ```

5. **需要时再编译 native 模块**
   - 当你需要研究 epoll/kqueue 实现时
   - 当你需要性能测试时

---

## 💡 小贴士

1. **使用 Maven Wrapper**: 项目自带 `./mvnw`，无需安装 Maven

2. **并行编译**: 加快编译速度
   ```bash
   ./mvnw clean install -DskipTests -T 4
   ```

3. **只编译不安装**: 节省时间
   ```bash
   ./mvnw clean compile -DskipTests
   ```

4. **查看模块依赖关系**:
   ```bash
   ./mvnw dependency:tree -pl common
   ```

5. **生成源码 jar**: 方便在 IDE 中查看
   ```bash
   ./mvnw source:jar -DskipTests
   ```

---

## 📖 相关资源

- [Netty 官方文档](https://netty.io/wiki/)
- [Netty GitHub](https://github.com/netty/netty)
- [Netty 用户指南](https://netty.io/wiki/user-guide.html)
- [Maven 官方文档](https://maven.apache.org/guides/)

---

**祝你学习愉快！** 🎉
