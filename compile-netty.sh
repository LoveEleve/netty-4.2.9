#!/bin/bash

# Netty 4.2.9 编译脚本
# 用于解决常见的编译问题

set -e

echo "=========================================="
echo "Netty 4.2.9 编译脚本"
echo "=========================================="

# 检查 Java 版本
echo "检查 Java 版本..."
java -version

# 设置 Maven 选项
export MAVEN_OPTS="-Xmx2048m -XX:MaxPermSize=512m"

# 编译选项
COMPILE_MODE=${1:-"skip-native"}

case $COMPILE_MODE in
  "full")
    echo "模式: 完整编译（包括 native 模块）"
    echo "注意: 需要安装 autoconf、automake、libtool、gcc 等工具"
    ./mvnw clean install -DskipTests
    ;;
    
  "skip-native")
    echo "模式: 跳过 Native 模块编译（推荐用于学习源码）"
    ./mvnw clean install -DskipTests \
      -pl '!transport-native-epoll' \
      -pl '!transport-native-kqueue' \
      -pl '!transport-native-io_uring' \
      -pl '!transport-native-unix-common-tests' \
      -pl '!codec-native-quic' \
      -pl '!resolver-dns-native-macos'
    ;;
    
  "core-only")
    echo "模式: 只编译核心模块"
    ./mvnw clean install -DskipTests \
      -pl common,buffer,transport,codec,codec-http,codec-http2,handler
    ;;
    
  "compile-only")
    echo "模式: 只编译不安装（跳过 native 模块）"
    ./mvnw clean compile -DskipTests \
      -pl '!transport-native-epoll' \
      -pl '!transport-native-kqueue' \
      -pl '!transport-native-io_uring' \
      -pl '!transport-native-unix-common-tests' \
      -pl '!codec-native-quic' \
      -pl '!resolver-dns-native-macos'
    ;;
    
  *)
    echo "未知模式: $COMPILE_MODE"
    echo ""
    echo "使用方法: $0 [模式]"
    echo ""
    echo "可用模式:"
    echo "  full         - 完整编译（包括 native 模块，需要编译工具）"
    echo "  skip-native  - 跳过 native 模块（推荐，默认）"
    echo "  core-only    - 只编译核心模块"
    echo "  compile-only - 只编译不安装"
    echo ""
    echo "示例:"
    echo "  $0 skip-native"
    exit 1
    ;;
esac

echo ""
echo "=========================================="
echo "编译完成！"
echo "=========================================="
