package com.example.k3sdemo.controller;

import com.example.k3sdemo.service.QwenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Controller
public class AiToolsController {

    @Autowired
    private QwenService qwenService;

    @GetMapping("/aitools")
    public String index() {
        return "aitools";
    }

    @PostMapping("/aitools/chat")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter chat(
            @RequestBody Map<String, String> payload) {
        String prompt = payload.get("prompt");
        // 增加超时时间到 5 分钟，与 QwenService 的超时时间一致
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(
                300000L); // 5 min timeout

        // 设置错误和完成回调
        emitter.onError((ex) -> {
            System.err.println("SSE Emitter error: " + ex.getMessage());
            ex.printStackTrace();
        });
        
        emitter.onTimeout(() -> {
            System.err.println("SSE Emitter timeout");
            try {
                emitter.complete();
            } catch (Exception e) {
                // Ignore
            }
        });
        
        emitter.onCompletion(() -> {
            System.out.println("SSE Emitter completed");
        });

        // Run in a separate thread
        new Thread(() -> {
            try {
                qwenService.streamChat(prompt, emitter);
            } catch (Exception e) {
                System.err.println("Error in streamChat thread: " + e.getMessage());
                e.printStackTrace();
                try {
                    emitter.completeWithError(e);
                } catch (Exception ex) {
                    // Ignore
                }
            }
        }).start();

        return emitter;
    }
}
