package com.seeewo4kin.bot.repository;

import com.seeewo4kin.bot.Entity.ReferralUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ReferralUsageRepository extends JpaRepository<ReferralUsage, Long> {
    boolean existsByUsedById(Long userId);
    List<ReferralUsage> findByReferralCodeOwnerId(Long ownerId);
}