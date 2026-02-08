#!/bin/bash
set -e

echo "🔹 Building Spring Boot Application..."
# Use system maven since mvnw was missing/failed
mvn clean package -DskipTests

echo "🔹 Building Docker Image..."
docker buildx build --platform linux/amd64 -t k3s:v1 .
docker save -o k3s.tar k3s:v1

echo "✅ 成功构建镜像!"
# scp k3s.tar root@60.205.252.82:/root/home/admin
