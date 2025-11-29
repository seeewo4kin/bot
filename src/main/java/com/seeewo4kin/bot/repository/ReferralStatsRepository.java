package com.seeewo4kin.bot.repository;

import com.seeewo4kin.bot.Entity.ReferralStats;
import com.seeewo4kin.bot.Entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReferralStatsRepository extends JpaRepository<ReferralStats, Long> {

    // Метод 1: Поиск статистики по пользователю
    Optional<ReferralStats> findByUser(User user);

    // Метод 2: Поиск статистики по ID пользователя
    @Query("SELECT rs FROM ReferralStats rs WHERE rs.user.id = :userId")
    Optional<ReferralStats> findByUserId(@Param("userId") Long userId);

    // Метод 3: Обновление количества рефералов 1-го уровня
    @Modifying
    @Query("UPDATE ReferralStats rs SET rs.level1Count = rs.level1Count + :increment WHERE rs.user.id = :userId")
    void incrementLevel1Count(@Param("userId") Long userId, @Param("increment") int increment);

    // Метод 4: Обновление количества рефералов 2-го уровня
    @Modifying
    @Query("UPDATE ReferralStats rs SET rs.level2Count = rs.level2Count + :increment WHERE rs.user.id = :userId")
    void incrementLevel2Count(@Param("userId") Long userId, @Param("increment") int increment);

    // Метод 5: Обновление активных рефералов
    @Modifying
    @Query("UPDATE ReferralStats rs SET rs.activeReferrals = rs.activeReferrals + :increment WHERE rs.user.id = :userId")
    void incrementActiveReferrals(@Param("userId") Long userId, @Param("increment") int increment);

    // Метод 6: Добавление к общей сумме обменов
    @Modifying
    @Query("UPDATE ReferralStats rs SET rs.totalExchangeAmount = rs.totalExchangeAmount + :amount, rs.totalExchangeCount = rs.totalExchangeCount + 1 WHERE rs.user.id = :userId")
    void addToTotalExchange(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    // Метод 7: Добавление к месячной сумме обменов
    @Modifying
    @Query("UPDATE ReferralStats rs SET rs.monthlyExchangeAmount = rs.monthlyExchangeAmount + :amount, rs.monthlyExchangeCount = rs.monthlyExchangeCount + 1 WHERE rs.user.id = :userId")
    void addToMonthlyExchange(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    // Метод 8: Добавление к общему заработку
    @Modifying
    @Query("UPDATE ReferralStats rs SET rs.totalEarned = rs.totalEarned + :amount WHERE rs.user.id = :userId")
    void addToTotalEarned(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    // Метод 9: Сброс месячной статистики
    @Modifying
    @Query("UPDATE ReferralStats rs SET rs.monthlyExchangeAmount = 0, rs.monthlyExchangeCount = 0 WHERE rs.user.id = :userId")
    void resetMonthlyStats(@Param("userId") Long userId);

    // Метод 10: Получить топ пользователей по общему заработку
    @Query("SELECT rs FROM ReferralStats rs ORDER BY rs.totalEarned DESC LIMIT :limit")
    List<ReferralStats> findTopByTotalEarned(@Param("limit") int limit);

    // Метод 11: Получить топ пользователей по количеству активных рефералов
    @Query("SELECT rs FROM ReferralStats rs ORDER BY rs.activeReferrals DESC LIMIT :limit")
    List<ReferralStats> findTopByActiveReferrals(@Param("limit") int limit);

    // Метод 12: Проверить существование статистики для пользователя
    boolean existsByUser(User user);

    // Метод 13: Получить общее количество активных рефералов в системе
    @Query("SELECT SUM(rs.activeReferrals) FROM ReferralStats rs")
    Long getTotalActiveReferrals();

    // Метод 14: Получить общую сумму заработка в системе
    @Query("SELECT SUM(rs.totalEarned) FROM ReferralStats rs")
    BigDecimal getTotalEarnedInSystem();
}