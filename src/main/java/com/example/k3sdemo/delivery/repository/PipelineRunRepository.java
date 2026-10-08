package com.example.k3sdemo.delivery.repository;

import com.example.k3sdemo.delivery.entity.PipelineRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PipelineRunRepository extends JpaRepository<PipelineRunEntity, Long> {

    Optional<PipelineRunEntity> findByRunId(String runId);

    List<PipelineRunEntity> findByStatusIn(List<String> statuses);
}
