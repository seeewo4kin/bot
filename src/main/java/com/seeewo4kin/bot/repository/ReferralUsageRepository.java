package com.seeewo4kin.bot.repository;

import com.seeewo4kin.bot.Entity.ReferralUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReferralUsageRepository extends JpaRepository<ReferralUsage, Long> {

    // Метод 1: Использование @Query для поиска по ownerId через связь
    @Query("SELECT ru FROM ReferralUsage ru WHERE ru.referralCode.user.id = :ownerId")
    List<ReferralUsage> findByReferralCodeOwnerId(@Param("ownerId") Long ownerId);

    // Метод 2: Поиск по коду приглашения
    List<ReferralUsage> findByReferralCode_Code(String referralCode);

    // Метод 3: Поиск по ID пользователя, который использовал код
    @Query("SELECT ru FROM ReferralUsage ru WHERE ru.user.id = :userId")
    List<ReferralUsage> findByUserId(@Param("userId") Long userId);

    // Метод 4: Поиск по коду приглашения и ID пользователя
    @Query("SELECT ru FROM ReferralUsage ru WHERE ru.referralCode.code = :referralCode AND ru.user.id = :userId")
    Optional<ReferralUsage> findByReferralCodeAndUserId(@Param("referralCode") String referralCode, @Param("userId") Long userId);

    // Метод 5: Подсчет количества использований по коду
    @Query("SELECT COUNT(ru) FROM ReferralUsage ru WHERE ru.referralCode.code = :referralCode")
    long countByReferralCode(@Param("referralCode") String referralCode);

    @Query("SELECT ru FROM ReferralUsage ru WHERE ru.referralCode.code = :referralCode AND ru.user.isActive = true")
    List<ReferralUsage> findActiveUsagesByReferralCode(@Param("referralCode") String referralCode);

    // Метод 7: Получить все использования с информацией о коде и пользователе
    @Query("SELECT ru FROM ReferralUsage ru JOIN FETCH ru.referralCode JOIN FETCH ru.user WHERE ru.referralCode.user.id = :ownerId")
    List<ReferralUsage> findByOwnerIdWithDetails(@Param("ownerId") Long ownerId);
}