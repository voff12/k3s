package com.example.k3sdemo.delivery.repository;

import com.example.k3sdemo.delivery.entity.ReleaseStage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReleaseStageRepository extends JpaRepository<ReleaseStage, Long> {

    List<ReleaseStage> findByReleaseIdOrderByStageOrderAsc(Long releaseId);

    Optional<ReleaseStage> findByReleaseIdAndStageOrder(Long releaseId, int stageOrder);
}
