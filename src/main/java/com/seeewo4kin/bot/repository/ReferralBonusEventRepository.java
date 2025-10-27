package com.seeewo4kin.bot.repository;

import com.seeewo4kin.bot.Entity.ReferralBonusEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReferralBonusEventRepository extends JpaRepository<ReferralBonusEvent, Long> {
}