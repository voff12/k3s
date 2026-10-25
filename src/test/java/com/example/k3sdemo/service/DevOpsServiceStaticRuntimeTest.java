package com.example.k3sdemo.service;

import com.example.k3sdemo.model.PipelineConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** runtime=static 的 Dockerfile/nginx 生成：两阶段构建、SPA 回退、端口与产物目录可配。 */
class DevOpsServiceStaticRuntimeTest {

    @Test
    void generatesTwoStageNodeNginxDockerfile() {
        PipelineConfig config = new PipelineConfig();
        config.setRuntime("static");
        String df = DevOpsService.buildStaticDockerfile("harbor.local", config);

        // 阶段 1: node alpine 构建
        assertThat(df).contains("FROM harbor.local/library/node:20-alpine AS build");
        assertThat(df).contains("COPY package*.json ./");
        assertThat(df).contains("npm ci --no-audit --no-fund");
        assertThat(df).contains("RUN npm run build");
        // 阶段 2: nginx 托管
        assertThat(df).contains("FROM harbor.local/library/nginx:alpine");
        assertThat(df).contains("COPY --from=build /app/dist /usr/share/nginx/html");
        assertThat(df).contains("COPY nginx-static.conf /etc/nginx/conf.d/default.conf");
        // static 默认端口 80
        assertThat(df).contains("EXPOSE 80");
        assertThat(df).contains("CMD [\"nginx\", \"-g\", \"daemon off;\"]");
    }

    @Test
    void honorsCustomNodeVersionDistDirAndBuildCommand() {
        PipelineConfig config = new PipelineConfig();
        config.setRuntime("static");
        config.setNodeVersion("22");
        config.setStaticDistDir("build");
        config.setNpmBuildCommand("pnpm build");
        config.setAppPort(8080);
        String df = DevOpsService.buildStaticDockerfile("harbor.local", config);

        assertThat(df).contains("node:22-alpine");
        assertThat(df).contains("COPY --from=build /app/build /usr/share/nginx/html");
        assertThat(df).contains("RUN pnpm build");
        assertThat(df).contains("EXPOSE 8080");
    }

    @Test
    void nginxConfHasSpaFallback() {
        String conf = DevOpsService.buildStaticNginxConf(80);
        assertThat(conf).contains("listen 80;");
        assertThat(conf).contains("root /usr/share/nginx/html;");
        assertThat(conf).contains("try_files $uri $uri/ /index.html;");
    }

    @Test
    void autoDetectScriptRecognizesPackageJson() throws Exception {
        // 仿 BuildkitCacheMountTest：截 rewrite 脚本源码段断言探测分支存在
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/example/k3sdemo/service/DevOpsService.java"));
        int start = src.indexOf("String rewriteCmd =");
        int end = src.indexOf("jobBuilder = jobBuilder", start);
        assertThat(start).isGreaterThan(0);
        String rewrite = src.substring(start, end);
        assertThat(rewrite).contains("package.json");
        assertThat(rewrite).contains("HAS_NPM");
        assertThat(rewrite).contains("RUNTIME=static");
        assertThat(rewrite).contains("nginx-static.conf");
    }
}
