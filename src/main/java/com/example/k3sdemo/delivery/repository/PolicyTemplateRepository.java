package com.example.k3sdemo.delivery.repository;

import com.example.k3sdemo.delivery.entity.PolicyTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PolicyTemplateRepository extends JpaRepository<PolicyTemplate, Long> {

    Optional<PolicyTemplate> findByCode(String code);

    List<PolicyTemplate> findAllByDeletedFalseOrderByCreatedAtAsc();

    Optional<PolicyTemplate> findByIdAndDeletedFalse(Long id);
}
