package com.example.k3sdemo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class QwenService {

    @Value("${qwen.api.key}")
    private String apiKey;

    // 使用 OpenAI 兼容协议，更稳定
    @Value("${qwen.api.url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String apiUrl;

    // 使用更稳定的模型
    @Value("${qwen.api.model:qwen-plus}")
    private String model;

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public QwenService() {
        this.webClient = WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024)) // 10MB
                .build();
    }

    public void streamChat(String prompt, org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter) {
        if (apiKey == null || apiKey.contains("YOUR_API_KEY") || apiKey.isEmpty()) {
            try {
                emitter.send("Error: Qwen API Key is not configured in application.properties.");
                emitter.complete();
            } catch (Exception e) {
                // Ignore
            }
            return;
        }

        // 确保 emitter 在方法结束时被正确关闭
        try {
            // 使用 OpenAI 兼容协议的请求格式
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("stream", true);
            requestBody.put("messages", List.of(
                    Map.of("role", "system", "content", getSystemPrompt()),
                    Map.of("role", "user", "content", prompt)));

            // 使用 WebClient 进行流式请求，更稳定可靠
            // 使用 DataBuffer 处理 SSE 流，然后按行分割
            webClient.post()
                    .uri(apiUrl + "/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToFlux(DataBuffer.class)
                    .timeout(Duration.ofMinutes(5)) // 5分钟超时
                    .doOnNext(buffer -> {
                        // 确保缓冲区被正确读取
                        if (buffer.readableByteCount() == 0) {
                            DataBufferUtils.release(buffer);
                        }
                    })
                    .filter(buffer -> buffer.readableByteCount() > 0) // 过滤空缓冲区
                    .map(buffer -> {
                        try {
                            byte[] bytes = new byte[buffer.readableByteCount()];
                            buffer.read(bytes);
                            return new String(bytes, StandardCharsets.UTF_8);
                        } finally {
                            DataBufferUtils.release(buffer);
                        }
                    })
                    .flatMap(content -> {
                        // 按行分割 SSE 流
                        if (content == null || content.isEmpty()) {
                            return Flux.empty();
                        }
                        String[] lines = content.split("\n", -1); // -1 保留尾部的空字符串
                        return Flux.fromArray(lines);
                    })
                    .filter(line -> line != null) // 只过滤 null，保留空字符串（SSE 格式需要）
                    .doOnError(error -> {
                        System.err.println("WebClient stream error: " + error.getMessage());
                        error.printStackTrace();
                        try {
                            if (emitter != null) {
                                try {
                                    emitter.send("Error: " + error.getMessage());
                                    emitter.completeWithError(error);
                                } catch (IllegalStateException ise) {
                                    // Emitter 已经关闭
                                    System.out.println("Emitter already closed when handling WebClient error");
                                }
                            }
                        } catch (Exception ex) {
                            System.err.println("Error sending error message to emitter: " + ex.getMessage());
                            // 最后尝试关闭
                            try {
                                if (emitter != null) {
                                    emitter.complete();
                                }
                            } catch (Exception finalEx) {
                                // 忽略
                            }
                        }
                    })
                    .onErrorResume(e -> {
                        // 返回空流，避免继续处理，但确保 emitter 被关闭
                        try {
                            if (emitter != null) {
                                emitter.complete();
                            }
                        } catch (Exception ex) {
                            // 忽略
                        }
                        return Flux.empty();
                    })
                    .subscribe(
                            line -> {
                                try {
                                    // 检查 emitter 是否仍然有效
                                    if (emitter == null) {
                                        return;
                                    }
                                    
                                    // 跳过空行（SSE 格式中的空行是分隔符）
                                    if (line == null || line.trim().isEmpty()) {
                                        return;
                                    }

                                    // 处理 SSE 格式: data: {...}
                                    if (line.startsWith("data: ")) {
                                        String data = line.substring(6).trim();
                                        
                                        if ("[DONE]".equals(data)) {
                                            if (emitter != null) {
                                                emitter.complete();
                                            }
                                            return;
                                        }

                                        // 解析 JSON
                                        try {
                                            JsonNode jsonNode = objectMapper.readTree(data);
                                            JsonNode choices = jsonNode.get("choices");
                                            if (choices != null && choices.isArray() && choices.size() > 0) {
                                                JsonNode delta = choices.get(0).get("delta");
                                                if (delta != null && delta.has("content")) {
                                                    String content = delta.get("content").asText();
                                                    if (content != null && !content.isEmpty() && emitter != null) {
                                                        emitter.send(content);
                                                    }
                                                }
                                            }
                                        } catch (Exception e) {
                                            // JSON 解析失败，记录但不中断
                                            System.err.println("Failed to parse JSON: " + data + ", error: " + e.getMessage());
                                        }
                                    }
                                } catch (Exception e) {
                                    System.err.println("Error processing line: " + e.getMessage());
                                    e.printStackTrace();
                                    try {
                                        if (emitter != null) {
                                            try {
                                                emitter.completeWithError(e);
                                            } catch (IllegalStateException ise) {
                                                // Emitter 已经关闭
                                                System.out.println("Emitter already closed when processing line error");
                                            }
                                        }
                                    } catch (Exception ex) {
                                        System.err.println("Error completing emitter with error: " + ex.getMessage());
                                        // 最后尝试正常关闭
                                        try {
                                            if (emitter != null) {
                                                emitter.complete();
                                            }
                                        } catch (Exception finalEx) {
                                            // 忽略
                                        }
                                    }
                                }
                            },
                            error -> {
                                System.err.println("Subscribe error handler: " + error.getMessage());
                                error.printStackTrace();
                                try {
                                    if (emitter != null) {
                                        emitter.send("Error: " + error.getMessage());
                                        emitter.completeWithError(error);
                                    }
                                } catch (Exception e) {
                                    System.err.println("Error in error handler: " + e.getMessage());
                                }
                            },
                            () -> {
                                System.out.println("Stream completed successfully");
                                try {
                                    if (emitter != null) {
                                        emitter.complete();
                                    }
                                } catch (Exception e) {
                                    System.err.println("Error completing emitter: " + e.getMessage());
                                }
                            }
                    );

        } catch (Exception e) {
            System.err.println("Exception in streamChat: " + e.getMessage());
            e.printStackTrace();
            try {
                if (emitter != null) {
                    // 检查 emitter 是否已经关闭
                    try {
                        emitter.send("Error: " + e.getMessage());
                        emitter.completeWithError(e);
                    } catch (IllegalStateException ise) {
                        // Emitter 已经关闭，忽略
                        System.out.println("Emitter already closed, ignoring error");
                    }
                }
            } catch (Exception ex) {
                System.err.println("Error sending exception to emitter: " + ex.getMessage());
                // 最后尝试关闭 emitter
                try {
                    if (emitter != null) {
                        emitter.complete();
                    }
                } catch (Exception finalEx) {
                    // 忽略最终错误
                }
            }
        }
    }

    public String chat(String prompt) {
        if (apiKey == null || apiKey.contains("YOUR_API_KEY") || apiKey.isEmpty()) {
            return "Error: Qwen API Key is not configured in application.properties.";
        }

        try {
            // 使用 OpenAI 兼容协议的请求格式
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("stream", false);
            requestBody.put("messages", List.of(
                    Map.of("role", "system", "content", getSystemPrompt()),
                    Map.of("role", "user", "content", prompt)));

            // 使用 WebClient 进行同步调用，更稳定可靠
            String responseJson = webClient.post()
                    .uri(apiUrl + "/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMinutes(5)) // 5分钟超时，与流式调用保持一致
                    .block();

            if (responseJson == null || responseJson.isEmpty()) {
                return "Error: Empty response from Qwen API.";
            }

            // 解析 JSON 响应
            JsonNode jsonNode = objectMapper.readTree(responseJson);
            
            // 检查错误
            if (jsonNode.has("error")) {
                JsonNode error = jsonNode.get("error");
                String errorMessage = error.has("message") ? error.get("message").asText() : "Unknown error";
                return "Error: " + errorMessage;
            }

            // 提取响应内容
            JsonNode choices = jsonNode.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode message = choices.get(0).get("message");
                if (message != null && message.has("content")) {
                    return message.get("content").asText();
                }
            }

            return "Error: No response text found in: " + responseJson;

        } catch (Exception e) {
            e.printStackTrace();
            return "Error calling Qwen API: " + e.getMessage();
        }
    }

    /**
     * 获取统一的系统提示词
     * 确保所有AI调用（AI工具箱和事件分析）使用相同的系统提示词和模型配置
     */
    private String getSystemPrompt() {
        return "You are a helpful Kubernetes expert assistant. You provide clear, accurate, and practical advice about Kubernetes cluster management, troubleshooting, and best practices. Always respond in Chinese (中文) when the user asks in Chinese, and use Markdown format for better readability.";
    }
}
