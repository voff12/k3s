package com.example.k3sdemo.delivery.repository;

import com.example.k3sdemo.delivery.entity.ReleaseApproval;

import java.util.Optional;

public interface ReleaseApprovalRepository extends org.springframework.data.jpa.repository.JpaRepository<ReleaseApproval, Long> {

    Optional<ReleaseApproval> findByReleaseIdAndGateAndStatus(Long releaseId, String gate, String status);
}
