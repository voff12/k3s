package com.example.k3sdemo.delivery.repository;

import com.example.k3sdemo.delivery.entity.Release;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReleaseRepository extends JpaRepository<Release, Long> {

    List<Release> findByAppIdOrderByCreatedAtDesc(Long appId);

    List<Release> findByStatusInOrderByCreatedAtDesc(List<String> statuses);

    /** 当日序号 +1（无则建为 1）；行锁持有到事务提交，须在事务内调用 */
    @Modifying
    @Query(value = "INSERT INTO release_no_seq (seq_day, seq) VALUES (:day, 1) "
            + "ON DUPLICATE KEY UPDATE seq = seq + 1", nativeQuery = true)
    void incrementDailySeq(@Param("day") String day);

    @Query(value = "SELECT seq FROM release_no_seq WHERE seq_day = :day", nativeQuery = true)
    int findDailySeq(@Param("day") String day);
}
