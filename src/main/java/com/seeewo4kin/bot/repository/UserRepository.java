package com.seeewo4kin.bot.repository;

import com.seeewo4kin.bot.Entity.User;
import com.seeewo4kin.bot.Enums.UserState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByTelegramId(Long telegramId);
    boolean existsByTelegramId(Long telegramId);
    Optional<User> findByUsername(String username); // Добавляем новый метод
    List<User> findByStateNot(UserState state);

    List<User> findByInvitedBy(User invitedBy);

    @Query("SELECT u FROM User u WHERE u.invitedBy = :inviter AND u.totalApplications > 0")
    List<User> findActiveReferralsByInviter(@Param("inviter") User inviter);

    @Query("SELECT COUNT(u) FROM User u WHERE u.invitedBy = :inviter")
    long countReferralsByInviter(@Param("inviter") User inviter);

    @Query("SELECT SUM(u.totalBuyAmount + u.totalSellAmount) FROM User u WHERE u.invitedBy = :inviter")
    BigDecimal getTotalExchangeAmountByReferrals(@Param("inviter") User inviter);
}