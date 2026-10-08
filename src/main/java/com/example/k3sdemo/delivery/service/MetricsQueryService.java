package com.example.k3sdemo.delivery.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行质量指标查询（P3 可观测性切片）：
 * 用 PromQL 查 Prometheus HTTP API，返回交付中心运行质量页所需的服务级指标。
 * Prometheus 不可达时返回 available=false，前端保持空态（不伪造数据）。
 */
@Service
public class MetricsQueryService {

    private final WebClient webClient;
    private final String prometheusUrl;
    private final boolean percentilesHistogram;

    public MetricsQueryService(@Value("${prometheus.url:}") String prometheusUrl,
                               @Value("${management.metrics.distribution.percentiles-histogram.http.server.requests:false}")
                               boolean percentilesHistogram) {
        this.prometheusUrl = prometheusUrl == null ? "" : prometheusUrl.trim();
        this.percentilesHistogram = percentilesHistogram;
        this.webClient = WebClient.builder()
                .baseUrl(this.prometheusUrl.isEmpty() ? "http://prometheus:9090" : this.prometheusUrl)
                .build();
    }

    public boolean configured() {
        return !prometheusUrl.isEmpty();
    }

    /**
     * 查询运行质量总览。任一查询失败不影响整体，仅该指标缺失。
     */
    public Map<String, Object> qualityOverview() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!configured()) {
            result.put("available", false);
            result.put("reason", "未配置 prometheus.url");
            return result;
        }
        result.put("available", true);
        result.put("prometheusUrl", prometheusUrl);
        result.put("window", "5m");

        // 请求速率（QPS）
        result.put("httpRequestsPerSecond", queryScalar(
                "sum(rate(http_server_requests_seconds_count{uri!=\"/actuator/**\"}[5m]))"));
        // 错误率（5xx 占比，百分比）
        result.put("httpErrorRatePct", queryScalar(
                "100 * sum(rate(http_server_requests_seconds_count{status=~\"5..\",uri!=\"/actuator/**\"}[5m]))"
                        + " / clamp_min(sum(rate(http_server_requests_seconds_count{uri!=\"/actuator/**\"}[5m])), 1e-9)"));
        // P99 延迟（秒）：histogram 分位数（需应用侧开启 percentiles-histogram）
        result.put("p99HistogramEnabled", percentilesHistogram);
        if (percentilesHistogram) {
            result.put("httpP99Seconds", queryScalar(
                    "histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{uri!=\"/actuator/**\"}[5m])) by (le))"));
        } else {
            // 未开 histogram 时退回 max 指标（标记为非分位数）
            result.put("httpP99Seconds", null);
        }
        result.put("httpMaxSeconds", queryScalar(
                "max(rate(http_server_requests_seconds_max{uri!=\"/actuator/**\"}[5m]))"));
        // JVM 与连接池
        result.put("jvmMemoryUsedBytes", queryScalar("sum(jvm_memory_used_bytes{area=\"heap\"})"));
        result.put("jvmMemoryMaxBytes", queryScalar("sum(jvm_memory_max_bytes{area=\"heap\"})"));
        result.put("jdbcActiveConnections", queryScalar("jdbc_connections_active"));
        result.put("jdbcMaxConnections", queryScalar("jdbc_connections_max"));
        result.put("processCpuUsage", queryScalar("process_cpu_usage"));
        result.put("uptimeSeconds", queryScalar("process_uptime_seconds"));
        return result;
    }

    /** 全站请求成功率（P4 服务健康切片）：(1 - 5xx 占比) × 100，含趋势折线。 */
    private static final String SUCCESS_RATE_EXPR =
            "100 * (1 - sum(rate(http_server_requests_seconds_count{status=~\"5..\",uri!=\"/actuator/**\"}[5m]))"
                    + " / clamp_min(sum(rate(http_server_requests_seconds_count{uri!=\"/actuator/**\"}[5m])), 1e-9))";

    /** window 参数白名单：防止 PromQL 注入与异常窗口。 */
    private static long windowSeconds(String window) {
        if (window == null) {
            return 24 * 3600;
        }
        switch (window.trim()) {
            case "1h":
                return 3600;
            case "6h":
                return 6 * 3600;
            case "7d":
                return 7 * 24 * 3600;
            case "24h":
            default:
                return 24 * 3600;
        }
    }

    /**
     * 服务成功率总览（设计 4.4 /api/delivery/overview/success-rate）。
     * Prometheus 未配置/不可达时返回 available=false，前端保持空态（不伪造数据）。
     * window 支持 1h/6h/24h/7d，默认 24h；trend 为 [epochMillis, 成功率%] 序列。
     */
    public Map<String, Object> successRateOverview(String window) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!configured()) {
            result.put("available", false);
            result.put("reason", "未配置 prometheus.url");
            return result;
        }
        long seconds = windowSeconds(window);
        String windowLabel = window == null ? "24h" : window.trim();
        result.put("available", true);
        result.put("prometheusUrl", prometheusUrl);
        result.put("window", windowLabel);

        // 当前成功率（即时查询，5m 窗口内的 5xx 占比）
        result.put("successRatePct", queryScalar(SUCCESS_RATE_EXPR));

        // 趋势折线（range 查询）：步长按窗口缩放，约 60 个点
        long end = System.currentTimeMillis() / 1000;
        long start = end - seconds;
        long step = Math.max(60, seconds / 60);
        result.put("trend", queryRange(SUCCESS_RATE_EXPR, start, end, step));
        return result;
    }

    /**
     * 执行即时（instant）PromQL，返回标量；查询无结果/异常返回 null。
     */
    private Double queryScalar(String promql) {
        try {
            // PromQL 含 { } " ! 等保留字符，必须整体 URL 编码后再拼 URL，
            // 否则 java.net.URI 抛 "Illegal character in query"
            String encoded = java.net.URLEncoder.encode(promql, java.nio.charset.StandardCharsets.UTF_8);
            Map<String, Object> resp = webClient.get()
                    .uri(prometheusUrl + "/api/v1/query?query=" + encoded)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(4));
            if (resp == null || !"success".equals(resp.get("status"))) {
                return null;
            }
            Object data = resp.get("data");
            if (!(data instanceof Map<?, ?> dm) || !(dm.get("result") instanceof List<?> results)
                    || results.isEmpty()) {
                return null;
            }
            // 取第一个 series 的 value[1]
            Object first = results.get(0);
            if (first instanceof Map<?, ?> series && series.get("value") instanceof List<?> value
                    && value.size() >= 2) {
                return Double.parseDouble(String.valueOf(value.get(1)));
            }
            return null;
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(MetricsQueryService.class)
                    .warn("prometheus query failed [{}]: {}", promql, String.valueOf(e.getMessage()));
            return null;
        }
    }

    /**
     * 执行区间（range）PromQL，返回 [[epochMillis, value], ...] 趋势点；查询无结果/异常返回空列表。
     */
    private List<List<Object>> queryRange(String promql, long startSec, long endSec, long stepSec) {
        try {
            String encoded = java.net.URLEncoder.encode(promql, java.nio.charset.StandardCharsets.UTF_8);
            Map<String, Object> resp = webClient.get()
                    .uri(prometheusUrl + "/api/v1/query_range?query=" + encoded
                            + "&start=" + startSec + "&end=" + endSec + "&step=" + stepSec)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(6));
            if (resp == null || !"success".equals(resp.get("status"))) {
                return List.of();
            }
            Object data = resp.get("data");
            if (!(data instanceof Map<?, ?> dm) || !(dm.get("result") instanceof List<?> results)
                    || results.isEmpty()) {
                return List.of();
            }
            Object first = results.get(0);
            if (!(first instanceof Map<?, ?> series) || !(series.get("values") instanceof List<?> values)) {
                return List.of();
            }
            List<List<Object>> points = new java.util.ArrayList<>(values.size());
            for (Object v : values) {
                if (v instanceof List<?> pair && pair.size() >= 2) {
                    long epochMillis = (long) (Double.parseDouble(String.valueOf(pair.get(0))) * 1000);
                    Double val = parseNumber(pair.get(1));
                    if (val != null) {
                        points.add(List.of(epochMillis, val));
                    }
                }
            }
            return points;
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(MetricsQueryService.class)
                    .warn("prometheus range query failed [{}]: {}", promql, String.valueOf(e.getMessage()));
            return List.of();
        }
    }

    /** Prometheus 的 value 可能是数字或字符串（含 "NaN"），统一解析；NaN/不可解析返回 null。 */
    private static Double parseNumber(Object raw) {
        try {
            double d = Double.parseDouble(String.valueOf(raw));
            return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
