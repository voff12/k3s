# ============================================================
# Stage 1: Build — 使用完整 JDK + Maven 构建项目
# ============================================================
FROM eclipse-temurin:17-jdk-jammy AS builder

WORKDIR /build

# ── 依赖层（变化少，充分利用 Docker 缓存） ──
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    apt-get update && apt-get install -y --no-install-recommends maven && \
    mvn dependency:go-offline -B && \
    rm -rf /var/lib/apt/lists/*

# ── 源码层（变化频繁，仅此层需要重建） ──
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn package -DskipTests -B && \
    # 提取分层 JAR 进一步优化镜像层
    java -Djarmode=layertools -jar target/k3s-1.0.0-SNAPSHOT.jar extract --destination /extracted

# ============================================================
# Stage 2: Runtime — JRE Alpine，体积更小（约 180MB）
# ============================================================
FROM eclipse-temurin:17-jre-alpine AS runtime

# 安全：创建非 root 用户 + 安装 curl（用于健康检查）
RUN addgroup -S appgroup && adduser -S -G appgroup -D -h /app -s /bin/false appuser && \
    apk add --no-cache curl

WORKDIR /app

# 从构建阶段复制分层 JAR（利用 Spring Boot Layered JAR）
COPY --from=builder /extracted/dependencies/          ./
COPY --from=builder /extracted/spring-boot-loader/    ./
COPY --from=builder /extracted/snapshot-dependencies/  ./
COPY --from=builder /extracted/application/            ./

# 目录权限
RUN chown -R appuser:appgroup /app

# 切换到非 root 用户
USER appuser

EXPOSE 8080 8081

# Docker 健康检查（使用 Actuator 独立管理端口）
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=3 \
    CMD curl -sf http://localhost:8081/actuator/health || exit 1

# JVM 容器感知 + 性能调优
ENV JAVA_OPTS="-XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -XX:InitialRAMPercentage=50.0 \
    -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
