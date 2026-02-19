#!/bin/bash
set -e

echo "🔹 Building Docker Image (multi-stage, includes Maven build)..."
docker buildx build --platform linux/amd64 -t k3s:v1 .
docker save -o k3s.tar k3s:v1

echo "✅ 成功构建镜像!"
# scp k3s.tar root@60.205.252.82:/root/home/admin
