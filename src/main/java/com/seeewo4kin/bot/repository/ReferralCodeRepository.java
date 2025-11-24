package com.seeewo4kin.bot.repository;

import com.seeewo4kin.bot.Entity.ReferralCode;
import com.seeewo4kin.bot.Entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReferralCodeRepository extends JpaRepository<ReferralCode, Long> {


    // Поиск по пользователю и активности
    List<ReferralCode> findByUserAndIsActive(User user, Boolean isActive);

    // Проверка существования кода
    boolean existsByCode(String code);

    // Увеличение счетчика использований
    @Modifying
    @Query("UPDATE ReferralCode rc SET rc.usedCount = rc.usedCount + 1 WHERE rc.id = :id")
    void incrementUsedCount(@Param("id") Long id);

    // Поиск истекших кодов
    @Query("SELECT rc FROM ReferralCode rc WHERE rc.expiresAt < CURRENT_TIMESTAMP AND rc.isActive = true")
    List<ReferralCode> findExpiredCodes();

    // Деактивация истекших кодов
    @Modifying
    @Query("UPDATE ReferralCode rc SET rc.isActive = false WHERE rc.expiresAt < CURRENT_TIMESTAMP AND rc.isActive = true")
    void deactivateExpiredCodes();

    // Поиск популярных кодов (по количеству использований)
    @Query("SELECT rc FROM ReferralCode rc ORDER BY rc.usedCount DESC LIMIT :limit")
    List<ReferralCode> findTopByUsedCount(@Param("limit") int limit);

    Optional<ReferralCode> findByCode(String code);

    // FIX: This should return List, not Optional
    List<ReferralCode> findByUserAndIsActiveTrue(User user);

    // Поиск всех кодов пользователя
    List<ReferralCode> findByUser(User user);

}