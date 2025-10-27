package com.seeewo4kin.bot.service;

import com.seeewo4kin.bot.Entity.*;
import com.seeewo4kin.bot.Enums.ReferralLevel;
import com.seeewo4kin.bot.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class ReferralService {
    private final UserService userService;
    private final ApplicationService applicationService;
    private final ReferralCodeRepository referralCodeRepository;
    private final ReferralUsageRepository referralUsageRepository;
    private final ReferralRelationshipRepository referralRelationshipRepository;
    private final ReferralStatsRepository referralStatsRepository;
    private final ReferralBonusEventRepository bonusEventRepository;

    @Value("${bot.referral.level1.percent:3.0}")
    private Double level1Percent;

    @Value("${bot.referral.level2.percent:0.5}")
    private Double level2Percent;

    @Value("${bot.referral.welcome.bonus:200}")
    private Double welcomeBonus;

    public ReferralService(UserService userService,
                           ApplicationService applicationService,
                           ReferralCodeRepository referralCodeRepository,
                           ReferralUsageRepository referralUsageRepository,
                           ReferralRelationshipRepository referralRelationshipRepository,
                           ReferralStatsRepository referralStatsRepository,
                           ReferralBonusEventRepository bonusEventRepository) {
        this.userService = userService;
        this.applicationService = applicationService;
        this.referralCodeRepository = referralCodeRepository;
        this.referralUsageRepository = referralUsageRepository;
        this.referralRelationshipRepository = referralRelationshipRepository;
        this.referralStatsRepository = referralStatsRepository;
        this.bonusEventRepository = bonusEventRepository;
    }

    // Добавим геттеры для процентов
    public Double getLevel1Percent() {
        return level1Percent;
    }

    public Double getLevel2Percent() {
        return level2Percent;
    }

    // Заменим processReferralReward на processReferralRewards
    public void processReferralRewards(Application application) {
        User user = application.getUser();

        // Находим пригласившего (уровень 1)
        User level1Inviter = user.getInvitedBy();
        if (level1Inviter != null) {
            double rewardLevel1 = application.getCalculatedGiveValue() * (level1Percent / 100);
            level1Inviter.setReferralBalance(level1Inviter.getReferralBalance() + rewardLevel1);
            application.setReferralRewardLevel1(rewardLevel1);
            userService.update(level1Inviter);

            // Находим пригласившего уровня 2
            User level2Inviter = level1Inviter.getInvitedBy();
            if (level2Inviter != null) {
                double rewardLevel2 = application.getCalculatedGiveValue() * (level2Percent / 100);
                level2Inviter.setReferralBalance(level2Inviter.getReferralBalance() + rewardLevel2);
                application.setReferralRewardLevel2(rewardLevel2);
                userService.update(level2Inviter);
            }
        }
    }

    // Старый метод для обратной совместимости
    public void processReferralReward(Application application) {
        processReferralRewards(application);
    }

    public void processReferralRegistration(User inviter, User invited) {
        // Создаем связь первого уровня
        ReferralRelationship level1Relation = new ReferralRelationship();
        level1Relation.setInviter(inviter);
        level1Relation.setInvited(invited);
        level1Relation.setLevel(ReferralLevel.LEVEL_1);
        referralRelationshipRepository.save(level1Relation);

        // Начисляем welcome bonus приглашенному
        invited.setBonusBalance(invited.getBonusBalance() + welcomeBonus);
        userService.update(invited);

        // Обновляем статистику приглашающего
        updateReferralStats(inviter);
    }

    public ReferralCode createReferralCode(User user) {
        String code = generateUniqueCode();

        ReferralCode referralCode = new ReferralCode();
        referralCode.setCode(code);
        referralCode.setOwner(user);
        referralCode.setDescription("Реферальный код пользователя " + user.getUsername());
        referralCode.setRewardPercent(level1Percent);
        referralCode.setIsActive(true);

        return referralCodeRepository.save(referralCode);
    }

    public boolean useReferralCode(String code, User user) {
        Optional<ReferralCode> referralCodeOpt = referralCodeRepository.findByCodeAndIsActiveTrue(code);

        if (referralCodeOpt.isPresent()) {
            ReferralCode referralCode = referralCodeOpt.get();

            // Проверяем, не использовал ли пользователь уже код
            if (referralUsageRepository.existsByUsedById(user.getId())) {
                return false;
            }

            // Создаем запись об использовании
            ReferralUsage usage = new ReferralUsage();
            usage.setReferralCode(referralCode);
            usage.setUsedBy(user);
            usage.setRewardGiven(false);
            referralUsageRepository.save(usage);

            // Устанавливаем связь с пригласившим
            user.setInvitedBy(referralCode.getOwner());
            user.setUsedReferralCode(code);
            user.setInvitedAt(LocalDateTime.now());
            userService.update(user);

            // Обрабатываем регистрацию
            processReferralRegistration(referralCode.getOwner(), user);

            return true;
        }

        return false;
    }

    public String generateReferralLink(User user) {
        return "https://t.me/cosanostra_change_bot?start=ref_" + user.getTelegramId();
    }

    public ReferralStats getReferralStats(User user) {
        return user.getReferralStats();
    }

    public List<ReferralCode> getUserReferralCodes(Long userId) {
        return referralCodeRepository.findByOwnerId(userId);
    }

    private String generateUniqueCode() {
        String code;
        do {
            code = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        } while (referralCodeRepository.existsByCode(code));
        return code;
    }

    private void updateReferralStats(User user) {
        ReferralStats stats = user.getReferralStats();
        if (stats == null) {
            stats = new ReferralStats();
            stats.setUser(user);
            user.setReferralStats(stats);
        }

        // Обновляем статистику
        List<ReferralRelationship> level1Relations =
                referralRelationshipRepository.findByInviterAndLevel(user, ReferralLevel.LEVEL_1);
        List<ReferralRelationship> level2Relations =
                referralRelationshipRepository.findByInviterAndLevel(user, ReferralLevel.LEVEL_2);

        stats.setLevel1Count(level1Relations.size());
        stats.setLevel2Count(level2Relations.size());
        stats.setActiveReferrals(level1Relations.size() + level2Relations.size());

        referralStatsRepository.save(stats);
        userService.update(user);
    }
}