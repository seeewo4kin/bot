package com.seeewo4kin.bot.repository;

import com.seeewo4kin.bot.Entity.ReferralRelationship;
import com.seeewo4kin.bot.Entity.User;
import com.seeewo4kin.bot.Enums.ReferralLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ReferralRelationshipRepository extends JpaRepository<ReferralRelationship, Long> {
    List<ReferralRelationship> findByInviterAndLevel(User inviter, ReferralLevel level);
    List<ReferralRelationship> findByInviter(User inviter);
    boolean existsByInvited(User invited);
}