package com.example.k3sdemo.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * MCP 端点（/mcp/**）的 API Key 保护。
 * MCP 端点默认无认证，任何可达客户端都能调用全部工具，故强制校验。
 * 规则：配置了 mcp.api.key 则要求请求头 X-API-Key 精确匹配（常量时间比较）；
 *       未配置时仅放行本机回环地址（本地开发兜底），其余一律 401。
 */
@Component
@Order(1)
public class McpApiKeyFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-API-Key";

    @Value("${mcp.api.key:}")
    private String apiKey;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/mcp");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (apiKey != null && !apiKey.isBlank()) {
            String provided = request.getHeader(HEADER);
            if (provided == null || !constantTimeEquals(provided, apiKey)) {
                reject(response, "missing or invalid " + HEADER);
                return;
            }
        } else if (!isLoopback(request.getRemoteAddr())) {
            reject(response, "mcp.api.key not configured; only loopback allowed");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isLoopback(String addr) {
        return "127.0.0.1".equals(addr) || "0:0:0:0:0:0:0:1".equals(addr) || "::1".equals(addr);
    }

    private void reject(HttpServletResponse response, String msg) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + msg + "\"}");
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
