package com.seeewo4kin.bot.repository;

import com.seeewo4kin.bot.Entity.ReferralCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReferralCodeRepository extends JpaRepository<ReferralCode, Long> {
    Optional<ReferralCode> findByCodeAndIsActiveTrue(String code);
    List<ReferralCode> findByOwnerId(Long ownerId);
    boolean existsByCode(String code);

    @Query("SELECT COUNT(ru) FROM ReferralUsage ru WHERE ru.referralCode.owner.id = :ownerId")
    Long countUsagesByOwner(@Param("ownerId") Long ownerId);
}