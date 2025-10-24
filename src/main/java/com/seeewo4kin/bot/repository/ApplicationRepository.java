package com.seeewo4kin.bot.repository;

import com.seeewo4kin.bot.Entity.Application;
import com.seeewo4kin.bot.Enums.ApplicationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ApplicationRepository extends JpaRepository<Application, Long> {
    List<Application> findByUserId(Long userId);
    List<Application> findByStatusIn(List<ApplicationStatus> statuses);
    List<Application> findByUserIdAndStatusIn(Long userId, List<ApplicationStatus> statuses);

    @Query("SELECT a FROM Application a WHERE a.user.id = :userId AND a.status = 'CLOSED'")
    List<Application> findCompletedByUser(@Param("userId") Long userId);

    // Новый метод для поиска по UUID (если понадобится)
    Application findByUuid(String uuid);
    List<Application> findByCreatedAtBetween(LocalDateTime startDate, LocalDateTime endDate);

}