#!/bin/bash
# ============================================================
# K3s 流水线基础镜像一键预热脚本
# 在 K3s 节点上执行一次，之后流水线完全不依赖外网
#
# 两阶段:
#   1. 流水线 Job 容器镜像 → K3s containerd (已有则跳过)
#   2. 基础镜像 → /opt/kaniko-base-images/ (Docker V2 tar)
#      Kaniko 直接从宿主机 tar 文件加载, 不需要 Registry
#
# 用法: sudo bash prewarm-images.sh
# ============================================================
set -e

CTR="ctr -a /run/k3s/containerd/containerd.sock -n k8s.io"

# ============================================================
# 第一部分: 流水线 Job 容器镜像 (导入 containerd)
# ============================================================
JOB_IMAGES=(
    "docker.io/library/alpine:3.19|registry.cn-hangzhou.aliyuncs.com/library/alpine:3.19"
    "docker.io/library/maven:3.9-eclipse-temurin-17|docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-17|docker.xuanyuan.me/library/maven:3.9-eclipse-temurin-17"
    "registry.aliyuncs.com/kaniko-project/executor:latest|registry.aliyuncs.com/kaniko-project/executor:latest"
    "docker.io/rancher/k3s:latest|registry.cn-hangzhou.aliyuncs.com/rancher/k3s:v1.28.4-k3s2|docker.m.daocloud.io/rancher/k3s:latest"
)

# ============================================================
# 第二部分: Dockerfile FROM 基础镜像 (导出到宿主机目录)
# ============================================================
BASE_IMAGES=(
    "eclipse-temurin:17-jdk-jammy"
    "maven:3.9-eclipse-temurin-17"
)

TEMURIN_LOCAL_NAMES=(
    "docker.io/library/eclipse-temurin:17-jdk-jammy"
    "eclipse-temurin:17-jdk-jammy"
    "library/eclipse-temurin:17-jdk-jammy"
)
TEMURIN_REMOTE_SOURCES=(
    "docker.m.daocloud.io/library/eclipse-temurin:17-jdk-jammy"
    "docker.xuanyuan.me/library/eclipse-temurin:17-jdk-jammy"
)

MAVEN_LOCAL_NAMES=(
    "docker.io/library/maven:3.9-eclipse-temurin-17"
    "maven:3.9-eclipse-temurin-17"
    "library/maven:3.9-eclipse-temurin-17"
)
MAVEN_REMOTE_SOURCES=(
    "docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-17"
    "docker.xuanyuan.me/library/maven:3.9-eclipse-temurin-17"
)

# 基础镜像导出目录
BASE_DIR="/opt/kaniko-base-images"

# 通用: 从多源拉取并 tag
pull_with_fallback() {
    local TARGET="$1"; shift
    for SOURCE in "$@"; do
        echo "        尝试: $SOURCE"
        if $CTR images pull "$SOURCE" > /dev/null 2>&1; then
            [ "$SOURCE" != "$TARGET" ] && $CTR images tag "$SOURCE" "$TARGET" 2>/dev/null || true
            echo "        [OK]"
            return 0
        fi
        echo "        [FAIL] 不可用"
    done
    return 1
}

echo "========================================================"
echo " K3s 流水线镜像预热 (离线模式, 无需 Registry)"
echo "========================================================"
echo ""

if ! $CTR images ls > /dev/null 2>&1; then
    echo "[ERROR] 无法访问 K3s containerd, 请用 sudo 执行"
    exit 1
fi

# ──────────── 阶段1: Job 镜像 ────────────
echo "===== 阶段1: 流水线 Job 镜像 → containerd ====="
echo ""

FAILED1=0
IDX=0
TOTAL1=${#JOB_IMAGES[@]}
for ENTRY in "${JOB_IMAGES[@]}"; do
    IFS='|' read -ra P <<< "$ENTRY"
    TARGET="${P[0]}"
    IDX=$((IDX + 1))
    echo "[$IDX/$TOTAL1] $TARGET"

    if $CTR images ls -q | grep -q "^${TARGET}$"; then
        echo "        [SKIP] 已存在"
    else
        SOURCES=("${P[@]:1}")
        if ! pull_with_fallback "$TARGET" "${SOURCES[@]}"; then
            echo "        [FAIL] 全部失败!"
            FAILED1=$((FAILED1 + 1))
        fi
    fi
    echo ""
done
echo "阶段1: 成功 $((TOTAL1 - FAILED1))/$TOTAL1"
echo ""

# ──────────── 阶段2: 基础镜像 → 宿主机 tar 文件 ────────────
echo "===== 阶段2: 基础镜像 → $BASE_DIR (Docker tar) ====="
echo ""
echo "[策略] 从 containerd 导出 Docker 格式 tar, Kaniko 通过 HostPath 直接加载"
echo ""

mkdir -p "$BASE_DIR"

FAILED2=0
IDX=0
TOTAL2=${#BASE_IMAGES[@]}

# 通用导出函数
export_base_image() {
    local ORIGINAL="$1"
    local -n LOCAL_NAMES_REF="$2"
    local -n REMOTE_SOURCES_REF="$3"
    local FUZZY_KEY1="$4"
    local FUZZY_KEY2="$5"

    # tar 文件名: eclipse-temurin_17-jdk-jammy.tar
    local TAR_NAME=$(echo "$ORIGINAL" | tr ':/' '_').tar
    local TAR_PATH="$BASE_DIR/$TAR_NAME"
    IDX=$((IDX + 1))
    echo "[$IDX/$TOTAL2] $ORIGINAL → $TAR_PATH"

    # 1) 检查 tar 是否已存在
    if [ -f "$TAR_PATH" ] && [ -s "$TAR_PATH" ]; then
        echo "        [SKIP] tar 文件已存在 ($(du -h "$TAR_PATH" | cut -f1))"
        echo ""
        return 0
    fi

    # 2) 在 containerd 中查找镜像
    local FOUND_LOCAL=""
    echo "        查找 containerd 本地镜像..."
    for NAME in "${LOCAL_NAMES_REF[@]}"; do
        if $CTR images ls -q | grep -q "${NAME}"; then
            FOUND_LOCAL="$NAME"
            echo "        [OK] 找到本地镜像: $NAME"
            break
        fi
    done

    # 模糊匹配兜底
    if [ -z "$FOUND_LOCAL" ] && [ -n "$FUZZY_KEY1" ]; then
        local FUZZY=$($CTR images ls -q | grep "$FUZZY_KEY1" | grep "$FUZZY_KEY2" | head -1)
        if [ -n "$FUZZY" ]; then
            FOUND_LOCAL="$FUZZY"
            echo "        [OK] 模糊匹配到本地镜像: $FUZZY"
        fi
    fi

    # 3) 本地没有则外网拉取
    if [ -z "$FOUND_LOCAL" ]; then
        echo "        containerd 中未找到, 尝试从外网拉取..."
        for SRC in "${REMOTE_SOURCES_REF[@]}"; do
            echo "        拉取: $SRC"
            if $CTR images pull "$SRC" > /dev/null 2>&1; then
                FOUND_LOCAL="$SRC"
                echo "        [OK] 拉取成功"
                break
            fi
            echo "        [FAIL] 不可用"
        done
    fi

    if [ -z "$FOUND_LOCAL" ]; then
        echo "        [FAIL] 全部失败! 无法获取 $ORIGINAL"
        FAILED2=$((FAILED2 + 1))
        echo ""
        return 1
    fi

    # 4) 导出为 Docker 格式 tar
    echo "        导出为 Docker tar (--platform linux/amd64)..."
    if $CTR images export --platform linux/amd64 "$TAR_PATH" "$FOUND_LOCAL" 2>/dev/null; then
        echo "        [OK] 导出成功 ($(du -h "$TAR_PATH" | cut -f1))"
    else
        echo "        [FAIL] 导出失败"
        rm -f "$TAR_PATH"
        FAILED2=$((FAILED2 + 1))
    fi
    echo ""
}

export_base_image "eclipse-temurin:17-jdk-jammy" TEMURIN_LOCAL_NAMES TEMURIN_REMOTE_SOURCES "eclipse-temurin" "17-jdk-jammy"
export_base_image "maven:3.9-eclipse-temurin-17" MAVEN_LOCAL_NAMES MAVEN_REMOTE_SOURCES "maven" "3.9-eclipse-temurin-17"
echo "阶段2: 成功 $((TOTAL2 - FAILED2))/$TOTAL2"
echo ""

# ──────────── 验证 ────────────
echo "========================================================"
echo " 最终验证"
echo "========================================================"
echo ""
echo "Job 镜像 (containerd):"
for ENTRY in "${JOB_IMAGES[@]}"; do
    IFS='|' read -ra P <<< "$ENTRY"
    T="${P[0]}"
    if $CTR images ls -q | grep -q "^${T}$"; then echo "  [OK] $T"; else echo "  [!!] $T"; fi
done
echo ""
echo "基础镜像 ($BASE_DIR):"
for ORIGINAL in "${BASE_IMAGES[@]}"; do
    TAR_NAME=$(echo "$ORIGINAL" | tr ':/' '_').tar
    TAR_PATH="$BASE_DIR/$TAR_NAME"
    if [ -f "$TAR_PATH" ] && [ -s "$TAR_PATH" ]; then
        echo "  [OK] $TAR_PATH ($(du -h "$TAR_PATH" | cut -f1))"
    else
        echo "  [!!] $TAR_PATH (不存在)"
    fi
done

TOTAL_FAIL=$((FAILED1 + FAILED2))
if [ $TOTAL_FAIL -gt 0 ]; then
    echo ""
    echo "手动补救:"
    echo "  # 在有 Docker 的机器上:"
    echo "  docker pull eclipse-temurin:17-jdk-jammy"
    echo "  docker pull maven:3.9-eclipse-temurin-17"
    echo "  docker save eclipse-temurin:17-jdk-jammy -o temurin.tar"
    echo "  docker save maven:3.9-eclipse-temurin-17 -o maven.tar"
    echo "  scp temurin.tar maven.tar root@<K3s节点>:/root/"
    echo ""
    echo "  # 在 K3s 节点上:"
    echo "  k3s ctr images import temurin.tar"
    echo "  k3s ctr images import maven.tar"
    echo "  # 然后重新执行本脚本"
fi

echo ""
echo "[INFO] 完成! 基础镜像已导出到 $BASE_DIR"
echo "[INFO] 流水线将通过 HostPath 挂载直接使用, 无需 Registry"
