# ============================================================
# git-alpine: 流水线 init 容器工具镜像 (registry-check / git-clone / rewrite-dockerfile)
# 预装 git + curl, 替代运行时 apk add —— 消除每次构建重复下载 Alpine 包 (~30-60s/次)。
#
# 构建 (K3s 节点上, prewarm-images.sh 会自动执行):
#   docker build -t 172.16.223.135:5000/git-alpine:3.19 -f docker/git-alpine.Dockerfile docker/
# 导入 containerd:
#   ctr -a /run/k3s/containerd/containerd.sock -n k8s.io images import git-alpine.tar
# ============================================================
FROM alpine:3.19

# 阿里云 Alpine 源: 与被删除的运行时 sed 换源行为等价 (后续 apk 仍走内网可达源)
RUN sed -i 's/dl-cdn.alpinelinux.org/mirrors.aliyun.com/g' /etc/apk/repositories \
    && apk add --no-cache git curl \
    && git --version && curl --version
