package com.seeewo4kin.bot.service;

import com.seeewo4kin.bot.Entity.Coupon;
import com.seeewo4kin.bot.Entity.User;
import com.seeewo4kin.bot.repository.CouponRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class CouponService {

    private final CouponRepository couponRepository;

    public CouponService(CouponRepository couponRepository) {
        this.couponRepository = couponRepository;
    }

    public Optional<Coupon> findValidCoupon(String code, User user) {
        return couponRepository.findValidCouponByCodeAndUser(code, user.getId());
    }

    public List<Coupon> getUserCoupons(Long userId) {
        return couponRepository.findByUserIdAndIsUsedFalse(userId);
    }

    public double applyCoupon(double originalAmount, Coupon coupon) {
        if (coupon.getDiscountPercent() != null) {
            return originalAmount * (1 - coupon.getDiscountPercent() / 100);
        } else if (coupon.getDiscountAmount() != null) {
            return Math.max(0, originalAmount - coupon.getDiscountAmount());
        }
        return originalAmount;
    }

    public void markCouponAsUsed(Coupon coupon) {
        coupon.setIsUsed(true);
        couponRepository.save(coupon);
    }
}