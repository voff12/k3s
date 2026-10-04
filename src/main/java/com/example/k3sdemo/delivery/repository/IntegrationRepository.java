package com.example.k3sdemo.delivery.repository;

import com.example.k3sdemo.delivery.entity.Integration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IntegrationRepository extends JpaRepository<Integration, Long> {

    List<Integration> findByType(String type);
}
