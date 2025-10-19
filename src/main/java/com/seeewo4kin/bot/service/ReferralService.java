package com.seeewo4kin.bot.service;

import com.seeewo4kin.bot.Entity.*;
import com.seeewo4kin.bot.repository.CouponRepository;
import com.seeewo4kin.bot.repository.ReferralCodeRepository;
import com.seeewo4kin.bot.repository.ReferralUsageRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class ReferralService {

    private final ReferralCodeRepository referralCodeRepository;
    private final ReferralUsageRepository referralUsageRepository;
    private final CouponRepository couponRepository;
    private final UserService userService;

    @Value("${bot.referral.reward.percent:10}")
    private Double referralRewardPercent;

    @Value("${bot.referral.coupon.amount:100}")
    private Double referralCouponAmount;

    public ReferralService(ReferralCodeRepository referralCodeRepository,
                           ReferralUsageRepository referralUsageRepository,
                           CouponRepository couponRepository,
                           UserService userService) {
        this.referralCodeRepository = referralCodeRepository;
        this.referralUsageRepository = referralUsageRepository;
        this.couponRepository = couponRepository;
        this.userService = userService;
    }

    public ReferralCode createReferralCode(User owner) { // Убираем параметр description
        String code;
        do {
            code = generateRandomCode();
        } while (referralCodeRepository.existsByCode(code));

        // Создаем купон для награды
        Coupon coupon = new Coupon();
        coupon.setCode("REF_" + code);
        coupon.setDescription("Реферальный бонус");
        coupon.setDiscountAmount(referralCouponAmount);
        coupon.setUser(owner);
        coupon.setIsActive(true);

        // СОХРАНЯЕМ купон перед использованием
        coupon = couponRepository.save(coupon);

        ReferralCode referralCode = new ReferralCode();
        referralCode.setCode(code);
        referralCode.setOwner(owner);
        referralCode.setRewardCoupon(coupon);
        referralCode.setRewardPercent(referralRewardPercent);

        return referralCodeRepository.save(referralCode);
    }

    public boolean useReferralCode(String code, User user) {
        // Проверяем, не использовал ли пользователь уже реферальный код
        if (referralUsageRepository.existsByUsedById(user.getId())) {
            return false;
        }

        Optional<ReferralCode> referralCodeOpt = referralCodeRepository.findByCodeAndIsActiveTrue(code);
        if (referralCodeOpt.isEmpty()) {
            return false;
        }

        ReferralCode referralCode = referralCodeOpt.get();

        // Создаем запись об использовании
        ReferralUsage usage = new ReferralUsage();
        usage.setReferralCode(referralCode);
        usage.setUsedBy(user);

        referralUsageRepository.save(usage);

        // Обновляем статистику у владельца кода
        User owner = referralCode.getOwner();
        owner.setReferralCount(owner.getReferralCount() + 1);
        userService.update(owner);

        // Сохраняем использованный код у пользователя
        user.setUsedReferralCode(code);
        userService.update(user);

        return true;
    }

    public void processReferralReward(Application application) {
        User user = application.getUser();
        if (user.getUsedReferralCode() == null) {
            return;
        }

        Optional<ReferralCode> referralCodeOpt = referralCodeRepository.findByCodeAndIsActiveTrue(user.getUsedReferralCode());
        if (referralCodeOpt.isEmpty()) {
            return;
        }

        ReferralCode referralCode = referralCodeOpt.get();
        User owner = referralCode.getOwner();

        // Рассчитываем вознаграждение (процент от суммы заявки)
        double reward = application.getCalculatedGiveValue() * (referralCode.getRewardPercent() / 100);

        owner.setReferralEarnings(owner.getReferralEarnings() + reward);
        userService.update(owner);
    }

    public List<ReferralCode> getUserReferralCodes(Long userId) {
        return referralCodeRepository.findByOwnerId(userId);
    }

    public Long getUserReferralCount(Long userId) {
        return referralCodeRepository.countUsagesByOwner(userId);
    }

    private String generateRandomCode() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}