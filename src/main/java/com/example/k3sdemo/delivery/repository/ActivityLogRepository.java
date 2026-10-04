package com.example.k3sdemo.delivery.repository;

import com.example.k3sdemo.delivery.entity.ActivityLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long> {

    List<ActivityLog> findByOrderByCreatedAtDescIdDesc(Pageable pageable);

    List<ActivityLog> findByReleaseIdOrderByCreatedAtDescIdDesc(Long releaseId);
}
