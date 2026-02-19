package com.example.k3sdemo.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 跟踪单次应用发布执行状态。
 * 两步流程: 构建发布 (Clone + Maven + Kaniko → Harbor) → K3s 部署
 */
public class ReleaseRecord {

    public enum Status {
        PENDING("等待中"),
        BUILDING("构建发布"),
        DEPLOYING("K3s 部署"),
        SUCCESS("发布成功"),
        FAILED("发布失败");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    private final String id;
    private final ReleaseConfig config;
    private volatile Status status;
    private volatile int currentStep; // 0-2
    private final LocalDateTime startTime;
    private volatile LocalDateTime endTime;
    private final List<String> logs;
    private volatile String errorMessage;
    private volatile LocalDateTime lastActivityTime;

    public ReleaseRecord(ReleaseConfig config) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.config = config;
        this.status = Status.PENDING;
        this.currentStep = -1;
        this.startTime = LocalDateTime.now();
        this.lastActivityTime = this.startTime;
        this.logs = Collections.synchronizedList(new ArrayList<>());
    }

    // --- Log operations ---

    public void addLog(String line) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        logs.add("[" + timestamp + "] " + line);
        this.lastActivityTime = LocalDateTime.now();
    }

    public List<String> getLogs() {
        return logs;
    }

    public List<String> getLogsSince(int fromIndex) {
        if (fromIndex >= logs.size())
            return Collections.emptyList();
        return new ArrayList<>(logs.subList(fromIndex, logs.size()));
    }

    // --- State transitions ---

    public void advanceTo(Status newStatus) {
        this.status = newStatus;
        this.lastActivityTime = LocalDateTime.now();
        switch (newStatus) {
            case BUILDING -> currentStep = 0;
            case DEPLOYING -> currentStep = 1;
            case SUCCESS, FAILED -> {
                currentStep = 2;
                endTime = LocalDateTime.now();
            }
            default -> {
            }
        }
    }

    public void fail(String errorMessage) {
        this.errorMessage = errorMessage;
        this.status = Status.FAILED;
        this.endTime = LocalDateTime.now();
        addLog("[ERROR] " + errorMessage);
    }

    // --- Getters ---

    public String getId() {
        return id;
    }

    public ReleaseConfig getConfig() {
        return config;
    }

    public Status getStatus() {
        return status;
    }

    public int getCurrentStep() {
        return currentStep;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public LocalDateTime getLastActivityTime() {
        return lastActivityTime;
    }

    public boolean isFinished() {
        return status == Status.SUCCESS || status == Status.FAILED;
    }

    public String getDuration() {
        LocalDateTime end = endTime != null ? endTime : LocalDateTime.now();
        long seconds = java.time.Duration.between(startTime, end).getSeconds();
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }

    public String getStartTimeFormatted() {
        return startTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
