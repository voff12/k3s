package com.example.k3sdemo.delivery.repository;

import com.example.k3sdemo.delivery.entity.AppEnvironment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AppEnvironmentRepository extends JpaRepository<AppEnvironment, Long> {

    List<AppEnvironment> findByAppId(Long appId);

    void deleteByAppId(Long appId);
}
