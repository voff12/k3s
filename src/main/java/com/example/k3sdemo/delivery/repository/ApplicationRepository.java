package com.example.k3sdemo.delivery.repository;

import com.example.k3sdemo.delivery.entity.Application;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

    Optional<Application> findByCode(String code);

    /** 引用计数：删除策略模板前的保护检查 */
    long countByPolicyTemplateId(Long policyTemplateId);

    /** 目录列表：只含未停用应用，按团队过滤可选 */
    List<Application> findByStatusOrderByUpdatedAtDesc(String status);

    List<Application> findByStatusAndTeamOrderByUpdatedAtDesc(String status, String team);
}
