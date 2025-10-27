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
    Optional<Coupon> findByCode(String code);

    @Query("SELECT c FROM Coupon c WHERE c.user.id = :userId AND c.isUsed = false")
    List<Coupon> findByUserIdAndIsUsedFalse(@Param("userId") Long userId);

    @Query("SELECT c FROM Coupon c WHERE c.user IS NULL AND c.isActive = true AND c.isUsed = false")
    List<Coupon> findByUserIsNullAndIsActiveTrueAndIsUsedFalse();

    List<Coupon> findByIsActiveTrue();

    @Query("SELECT c FROM Coupon c WHERE c.code = :code AND c.isActive = true AND c.isUsed = false")
    Optional<Coupon> findValidCouponByCode(@Param("code") String code);
}