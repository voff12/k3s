package com.example.k3sdemo.delivery.repository;

import com.example.k3sdemo.delivery.entity.Release;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReleaseRepository extends JpaRepository<Release, Long> {

    List<Release> findByAppIdOrderByCreatedAtDesc(Long appId);

    List<Release> findByStatusInOrderByCreatedAtDesc(List<String> statuses);
}
