package com.seeewo4kin.bot.repository;

import com.seeewo4kin.bot.Entity.ReferralStats;
import com.seeewo4kin.bot.Entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface ReferralStatsRepository extends JpaRepository<ReferralStats, Long> {
    Optional<ReferralStats> findByUser(User user);
}