package com.seeewo4kin.bot.service;

import com.seeewo4kin.bot.Entity.Coupon;
import com.seeewo4kin.bot.Entity.User;
import com.seeewo4kin.bot.repository.CouponRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
        Optional<Coupon> couponOpt = couponRepository.findByCode(code.toUpperCase());

        if (couponOpt.isPresent()) {
            Coupon coupon = couponOpt.get();
            if (coupon.canBeUsed()) {
                // Проверяем, персональный ли купон
                if (coupon.getUser() != null && !coupon.getUser().getId().equals(user.getId())) {
                    return Optional.empty();
                }
                return Optional.of(coupon);
            }
        }
        return Optional.empty();
    }

    public List<Coupon> getUserCoupons(Long userId) {
        return couponRepository.findByUserIdAndIsUsedFalse(userId);
    }

    public List<Coupon> getAllCoupons() {
        return couponRepository.findAll();
    }

    public List<Coupon> getActiveCoupons() {
        return couponRepository.findByIsActiveTrue();
    }

    public BigDecimal applyCoupon(BigDecimal amount, Coupon coupon) {
        if (amount == null || coupon == null) {
            return amount != null ? amount : BigDecimal.ZERO;
        }

        if (coupon.getDiscountPercent() != null) {
            BigDecimal discount = amount.multiply(coupon.getDiscountPercent())
                    .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
            return amount.subtract(discount).max(BigDecimal.ZERO);
        } else if (coupon.getDiscountAmount() != null) {
            return amount.subtract(coupon.getDiscountAmount()).max(BigDecimal.ZERO);
        }

        return amount;
    }


    public void createCoupon(Coupon coupon) {
        couponRepository.save(coupon);
    }

    public Optional<Coupon> findByCode(String code) {
        return couponRepository.findByCode(code.toUpperCase());
    }

    public void deactivateCoupon(Long couponId) {
        couponRepository.findById(couponId).ifPresent(coupon -> {
            coupon.setIsActive(false);
            couponRepository.save(coupon);
        });
    }

    public void updateCoupon(Coupon coupon) {
        couponRepository.save(coupon);
    }
}