package com.example.k3sdemo.delivery.repository;

import com.example.k3sdemo.delivery.entity.Artifact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ArtifactRepository extends JpaRepository<Artifact, Long> {

    List<Artifact> findByAppIdOrderByCreatedAtDesc(Long appId);

    List<Artifact> findByAppIdAndScanStatusOrderByCreatedAtDesc(Long appId, String scanStatus);
}
