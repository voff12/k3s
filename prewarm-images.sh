#!/bin/bash
# ============================================================
# K3s 流水线基础镜像一键预热脚本
# 在 K3s 节点上执行一次，之后流水线完全不依赖外网
#
# 三阶段:
#   1. 流水线 Job 容器镜像 → K3s containerd (已有则跳过)
#   2. 部署本地 Registry (localhost:5000)
#   3. 基础镜像 → localhost:5000 (优先从 containerd 本地推送)
#
# 用法: sudo bash prewarm-images.sh
# ============================================================
set -e

CTR="ctr -a /run/k3s/containerd/containerd.sock -n k8s.io"
KUBECTL="kubectl"

# ============================================================
# 第一部分: 流水线 Job 容器镜像 (导入 containerd)
# ============================================================
JOB_IMAGES=(
    "docker.io/library/alpine:3.19|registry.cn-hangzhou.aliyuncs.com/library/alpine:3.19"
    "docker.io/library/maven:3.9-eclipse-temurin-17|docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-17|docker.xuanyuan.me/library/maven:3.9-eclipse-temurin-17"
    "registry.aliyuncs.com/kaniko-project/executor:latest|registry.aliyuncs.com/kaniko-project/executor:latest"
    "docker.io/rancher/k3s:latest|registry.cn-hangzhou.aliyuncs.com/rancher/k3s:v1.28.4-k3s2|docker.m.daocloud.io/rancher/k3s:latest"
    "docker.io/library/registry:2|registry.cn-hangzhou.aliyuncs.com/library/registry:2|docker.m.daocloud.io/library/registry:2"
)

# ============================================================
# 第二部分: Dockerfile FROM 基础镜像 (推到 localhost:5000)
# 格式: "原始名|containerd中可能的tag1|containerd中可能的tag2|外网源1|外网源2"
# 优先从 containerd 本地已有镜像推送，全部没有才走外网
# ============================================================
BASE_IMAGES=(
    "eclipse-temurin:17-jdk-jammy"
)

# containerd 中可能的镜像名 (docker save/import 后的名字可能不同)
TEMURIN_LOCAL_NAMES=(
    "docker.io/library/eclipse-temurin:17-jdk-jammy"
    "eclipse-temurin:17-jdk-jammy"
    "library/eclipse-temurin:17-jdk-jammy"
)
TEMURIN_REMOTE_SOURCES=(
    "docker.m.daocloud.io/library/eclipse-temurin:17-jdk-jammy"
    "docker.xuanyuan.me/library/eclipse-temurin:17-jdk-jammy"
)

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
echo " K3s 流水线镜像预热 (离线模式)"
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

# ──────────── 阶段2: 本地 Registry ────────────
echo "===== 阶段2: 本地 Registry (localhost:5000) ====="
echo ""

if curl -s http://localhost:5000/v2/ > /dev/null 2>&1; then
    echo "[SKIP] Registry 已运行"
else
    echo "[INFO] 部署本地 Registry..."
    cat <<'YAML' | $KUBECTL apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: local-registry
  namespace: default
  labels:
    app: local-registry
spec:
  replicas: 1
  selector:
    matchLabels:
      app: local-registry
  template:
    metadata:
      labels:
        app: local-registry
    spec:
      hostNetwork: true
      containers:
        - name: registry
          image: registry:2
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: 5000
              hostPort: 5000
          volumeMounts:
            - name: data
              mountPath: /var/lib/registry
          env:
            - name: REGISTRY_STORAGE_DELETE_ENABLED
              value: "true"
      volumes:
        - name: data
          hostPath:
            path: /opt/local-registry
            type: DirectoryOrCreate
YAML

    echo "[INFO] 等待 Registry 就绪..."
    for i in $(seq 1 60); do
        if curl -s http://localhost:5000/v2/ > /dev/null 2>&1; then
            echo "[OK] Registry 就绪"
            break
        fi
        [ "$i" -eq 60 ] && { echo "[ERROR] Registry 启动超时"; exit 1; }
        sleep 3
    done
fi
echo ""

# ──────────── 阶段3: 基础镜像 → localhost:5000 ────────────
echo "===== 阶段3: 基础镜像 → localhost:5000 ====="
echo ""
echo "[策略] 优先从 containerd 本地已有镜像推送，不走外网"
echo ""

FAILED3=0
for ORIGINAL in "${BASE_IMAGES[@]}"; do
    LOCAL_REF="localhost:5000/library/${ORIGINAL}"
    echo "[1/1] $ORIGINAL → $LOCAL_REF"

    # 1) 检查 localhost:5000 是否已有
    REPO="library/${ORIGINAL%%:*}"
    TAG="${ORIGINAL##*:}"
    if curl -s "http://localhost:5000/v2/${REPO}/tags/list" 2>/dev/null | grep -q "$TAG"; then
        echo "        [SKIP] 本地 Registry 已存在"
        echo ""
        continue
    fi

    # 2) 优先: 在 containerd 中查找已有镜像 (docker save + k3s ctr import 的结果)
    FOUND_LOCAL=""
    echo "        查找 containerd 本地镜像..."
    for NAME in "${TEMURIN_LOCAL_NAMES[@]}"; do
        if $CTR images ls -q | grep -q "${NAME}"; then
            FOUND_LOCAL="$NAME"
            echo "        [OK] 找到本地镜像: $NAME"
            break
        fi
    done

    # 也用模糊匹配兜底 (k3s ctr images import 后 tag 可能不规范)
    if [ -z "$FOUND_LOCAL" ]; then
        FUZZY=$($CTR images ls -q | grep "eclipse-temurin" | grep "17-jdk-jammy" | head -1)
        if [ -n "$FUZZY" ]; then
            FOUND_LOCAL="$FUZZY"
            echo "        [OK] 模糊匹配到本地镜像: $FUZZY"
        fi
    fi

    if [ -n "$FOUND_LOCAL" ]; then
        # 直接从 containerd 本地推送到 localhost:5000, 不走外网
        echo "        推送到 localhost:5000 (本地 → 本地, 不走外网)..."
        $CTR images tag "$FOUND_LOCAL" "$LOCAL_REF" 2>/dev/null || true
        if $CTR images push --plain-http "$LOCAL_REF" > /dev/null 2>&1; then
            echo "        [OK] 推送成功 (零外网流量)"
        else
            echo "        [FAIL] 推送失败"
            FAILED3=$((FAILED3 + 1))
        fi
    else
        # 3) 本地没有, 从外网拉
        echo "        containerd 中未找到, 尝试从外网拉取..."
        PULLED=false
        for SRC in "${TEMURIN_REMOTE_SOURCES[@]}"; do
            echo "        拉取: $SRC"
            if $CTR images pull "$SRC" > /dev/null 2>&1; then
                $CTR images tag "$SRC" "$LOCAL_REF" 2>/dev/null || true
                if $CTR images push --plain-http "$LOCAL_REF" > /dev/null 2>&1; then
                    echo "        [OK] 拉取+推送成功"
                    PULLED=true
                    break
                fi
            fi
            echo "        [FAIL] 不可用"
        done
        if [ "$PULLED" = false ]; then
            echo "        [FAIL] 全部失败!"
            FAILED3=$((FAILED3 + 1))
        fi
    fi
    echo ""
done
echo "阶段3: 成功 $((${#BASE_IMAGES[@]} - FAILED3))/${#BASE_IMAGES[@]}"
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
echo "基础镜像 (localhost:5000):"
for ORIGINAL in "${BASE_IMAGES[@]}"; do
    R="library/${ORIGINAL%%:*}"; G="${ORIGINAL##*:}"
    if curl -s "http://localhost:5000/v2/${R}/tags/list" 2>/dev/null | grep -q "$G"; then
        echo "  [OK] localhost:5000/$R:$G"
    else
        echo "  [!!] localhost:5000/$R:$G"
    fi
done

TOTAL_FAIL=$((FAILED1 + FAILED3))
if [ $TOTAL_FAIL -gt 0 ]; then
    echo ""
    echo "手动补救:"
    echo "  # 在有 Docker 的机器上:"
    echo "  docker pull eclipse-temurin:17-jdk-jammy"
    echo "  docker save eclipse-temurin:17-jdk-jammy -o temurin.tar"
    echo "  scp temurin.tar root@<K3s节点>:/root/"
    echo ""
    echo "  # 在 K3s 节点上:"
    echo "  k3s ctr images import temurin.tar"
    echo "  # 然后重新执行本脚本即可自动推送到 localhost:5000"
fi

echo ""
echo "[INFO] 完成! 流水线完全离线运行"
