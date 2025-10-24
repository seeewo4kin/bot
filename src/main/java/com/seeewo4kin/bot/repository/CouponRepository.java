package com.seeewo4kin.bot.repository;

import com.seeewo4kin.bot.Entity.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CouponRepository extends JpaRepository<Coupon, Long> {

    @Query("SELECT c FROM Coupon c WHERE c.code = :code AND c.user.id = :userId AND c.isActive = true AND c.isUsed = false AND (c.validUntil IS NULL OR c.validUntil > CURRENT_TIMESTAMP)")
    Optional<Coupon> findValidCouponByCodeAndUser(@Param("code") String code, @Param("userId") Long userId);

    List<Coupon> findByUserIdAndIsUsedFalse(Long userId);

    List<Coupon> findByUserIsNullAndIsActiveTrueAndIsUsedFalse(); // Общие купоны
    Optional<Coupon> findByCode(String code);
}