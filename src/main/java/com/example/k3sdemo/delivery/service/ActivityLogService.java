package com.example.k3sdemo.delivery.service;

import com.example.k3sdemo.delivery.entity.ActivityLog;
import com.example.k3sdemo.delivery.repository.ActivityLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 统一活动流水写入/查询（P2 表 activity_log）。
 */
@Service
public class ActivityLogService {

    private final ActivityLogRepository repository;

    public ActivityLogService(ActivityLogRepository repository) {
        this.repository = repository;
    }

    /** 跟随当前事务：与业务写入同生共死，避免审计行引用未提交的外键 */
    @Transactional(propagation = Propagation.REQUIRED)
    public void log(Long appId, Long releaseId, String actorType, String actor,
                    String action, String message, String env) {
        ActivityLog row = new ActivityLog();
        row.setAppId(appId);
        row.setReleaseId(releaseId);
        row.setActorType(actorType);
        row.setActor(actor);
        row.setAction(action);
        row.setMessage(message);
        row.setEnv(env);
        repository.save(row);
    }

    public List<ActivityLog> recent(int limit) {
        Pageable pageable = PageRequest.of(0, Math.max(1, Math.min(limit, 100)));
        return repository.findByOrderByCreatedAtDescIdDesc(pageable);
    }

    public List<ActivityLog> byRelease(Long releaseId) {
        return repository.findByReleaseIdOrderByCreatedAtDescIdDesc(releaseId);
    }
}
