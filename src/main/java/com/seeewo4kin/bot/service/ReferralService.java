package com.seeewo4kin.bot.service;

import com.seeewo4kin.bot.Entity.*;
import com.seeewo4kin.bot.Entity.ReferralStatsEmbedded;
import com.seeewo4kin.bot.Enums.ApplicationStatus;
import com.seeewo4kin.bot.Enums.BonusType;
import com.seeewo4kin.bot.Enums.ReferralLevel;
import com.seeewo4kin.bot.Enums.ValueType;
import com.seeewo4kin.bot.repository.ReferralBonusEventRepository;
import com.seeewo4kin.bot.repository.ReferralCodeRepository;
import com.seeewo4kin.bot.repository.ReferralRelationshipRepository;
import com.seeewo4kin.bot.repository.UserRepository;
import com.seeewo4kin.bot.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;

@Service
public class ReferralService {

    private final ReferralCodeRepository referralCodeRepository;
    private final ReferralRelationshipRepository referralRelationshipRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final ApplicationService applicationService;
    private final String botUsername;

    public ReferralService(ReferralCodeRepository referralCodeRepository,
                           ReferralRelationshipRepository referralRelationshipRepository,
                           ReferralBonusEventRepository referralBonusEventRepository,
                           UserRepository userRepository,
                           ApplicationService applicationService,
                           UserService userService,
                           @Value("${telegram.bot.username}") String botUsername) {
        this.referralCodeRepository = referralCodeRepository;
        this.referralRelationshipRepository = referralRelationshipRepository;
        this.userRepository = userRepository;
        this.userService = userService;
        this.applicationService = applicationService;
        this.botUsername = botUsername;
    }

    /**
     * Создает реферальный код для пользователя
     */

    // Добавьте в класс ReferralService
    @Transactional
    public ReferralCode updateReferralCode(ReferralCode referralCode) {
        if (referralCode == null) {
            throw new IllegalArgumentException("ReferralCode cannot be null");
        }
        return referralCodeRepository.save(referralCode);
    }

    /**
     * Генерирует реферальный код на основе Telegram ID
     */
    private String generateReferralCode(Long telegramId) {
        return telegramId.toString();
    }

    /**
     * Находит реферальный код по значению
     */
    public ReferralCode findByCode(String code) {
        return referralCodeRepository.findByCode(code.toUpperCase())
                .orElse(null);
    }

    /**
     * Получает реферальные коды пользователя
     */
    public List<ReferralCode> getUserReferralCodes(Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return List.of();
        }
        return referralCodeRepository.findByUser(user);
    }

    /**
     * Получает активные реферальные коды пользователя
     */
    public List<ReferralCode> getUserActiveReferralCodes(Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return List.of();
        }

        // Поскольку код теперь основан на TG ID, всегда возвращаем код пользователя
        ReferralCode userCode = findByCode(user.getTelegramId().toString());
        if (userCode != null && userCode.getIsActive()) {
            return List.of(userCode);
        }

        return List.of();
    }

    /**
     * Обрабатывает регистрацию по реферальной ссылке
     */
    @Transactional
    public void processReferralRegistration(User inviter, User newUser, String referralCodeValue) {
        System.out.println("DEBUG processReferralRegistration: inviter=" + (inviter != null ? inviter.getId() : "null") + 
                           ", newUser=" + (newUser != null ? newUser.getId() : "null") + 
                           ", code='" + referralCodeValue + "'");
        
        if (inviter == null || newUser == null || inviter.getId().equals(newUser.getId())) {
            System.out.println("DEBUG processReferralRegistration: Validation failed - inviter or newUser is null or same user");
            return;
        }

        // Находим реферальный код
        ReferralCode referralCode = findByCode(referralCodeValue);
        System.out.println("DEBUG processReferralRegistration: Found code=" + (referralCode != null) + 
                           ", isActive=" + (referralCode != null ? referralCode.getIsActive() : false));
        
        if (referralCode == null) {
            System.out.println("DEBUG processReferralRegistration: Referral code not found");
            return;
        }

        // Устанавливаем связь
        newUser.setInvitedBy(inviter);
        newUser.setInvitedAt(LocalDateTime.now());
        newUser.setUsedReferralCode(referralCodeValue); // Сохраняем использованный реферальный код

        userRepository.save(newUser);
        
        System.out.println("DEBUG processReferralRegistration: User saved with usedReferralCode: '" + newUser.getUsedReferralCode() + "'");

        // Создаем запись о реферальной связи
        ReferralRelationship relationship = new ReferralRelationship();
        relationship.setInviter(inviter);
        relationship.setInvited(newUser);
        relationship.setLevel(ReferralLevel.LEVEL_1);

        // Сохраняем связь в базе данных
        referralRelationshipRepository.save(relationship);

        // Обновляем счетчик рефералов у пригласившего
        inviter.setReferralCount(inviter.getReferralCount() + 1);
        userRepository.save(inviter);

        // Увеличиваем счетчик использования кода
        referralCode.setUsedCount(referralCode.getUsedCount() + 1);
        updateReferralCode(referralCode);

        System.out.println("REFERRAL DEBUG: User " + newUser.getId() +
                " invited by " + inviter.getId() +
                " using code " + referralCodeValue +
                ", total referrals: " + inviter.getReferralCount());
    }

    /**
     * Обрабатывает вознаграждение за выполненную заявку реферала
     */
    @Transactional
    public void processReferralReward(Application application) {
        User user = application.getUser();
        User inviter = user.getInvitedBy();

        if (inviter == null) {
            return;
        }

        // Определяем базовую сумму для расчета вознаграждения
        BigDecimal baseAmount = calculateBaseAmountForReward(application);

        // Вознаграждение для первого уровня (3% + бонусные проценты)
        BigDecimal level1Percent = BigDecimal.valueOf(0.03).add(inviter.getReferralStats().getLevel1BonusPercent());
        BigDecimal level1Reward = baseAmount.multiply(level1Percent);

        // Создаем запись о бонусе для первого уровня
        ReferralBonusEvent level1Bonus = new ReferralBonusEvent();
        level1Bonus.setUser(inviter);
        level1Bonus.setBonusType(BonusType.REFERRAL_LEVEL_1);
        level1Bonus.setAmount(level1Reward);
        level1Bonus.setDescription("Реферальный бонус 1 уровня за заявку #" + application.getId());
        // TODO: Сохранить level1Bonus в базу данных

        // Начисляем вознаграждение на реферальный баланс
        if (inviter.getReferralStats() == null) {
            inviter.setReferralStats(new ReferralStatsEmbedded());
        }
        inviter.getReferralStats().setReferralBalance(
            inviter.getReferralStats().getReferralBalance().add(level1Reward)
        );
        inviter.getReferralStats().setTotalEarned(
            inviter.getReferralStats().getTotalEarned().add(level1Reward)
        );

        // Обновляем объем 1-го уровня
        inviter.getReferralStats().setLevel1Volume(
            inviter.getReferralStats().getLevel1VolumeSafe().add(baseAmount)
        );

        userRepository.save(inviter);

        // Вознаграждение для второго уровня (0.5% + бонусные проценты)
        User secondLevelInviter = inviter.getInvitedBy();
        if (secondLevelInviter != null) {
            if (secondLevelInviter.getReferralStats() == null) {
                secondLevelInviter.setReferralStats(new ReferralStatsEmbedded());
            }
            BigDecimal level2Percent = BigDecimal.valueOf(0.005).add(secondLevelInviter.getReferralStats().getLevel2BonusPercent());
            BigDecimal level2Reward = baseAmount.multiply(level2Percent);

            // Создаем запись о бонусе для второго уровня
            ReferralBonusEvent level2Bonus = new ReferralBonusEvent();
            level2Bonus.setUser(secondLevelInviter);
            level2Bonus.setBonusType(BonusType.REFERRAL_LEVEL_2);
            level2Bonus.setAmount(level2Reward);
            level2Bonus.setDescription("Реферальный бонус 2 уровня за заявку #" + application.getId());
            // TODO: Сохранить level2Bonus в базу данных

            // Начисляем вознаграждение на реферальный баланс
            if (secondLevelInviter.getReferralStats() == null) {
                secondLevelInviter.setReferralStats(new ReferralStatsEmbedded());
            }
            secondLevelInviter.getReferralStats().setReferralBalance(
                secondLevelInviter.getReferralStats().getReferralBalance().add(level2Reward)
            );
            secondLevelInviter.getReferralStats().setTotalEarned(
                secondLevelInviter.getReferralStats().getTotalEarned().add(level2Reward)
            );

            // Обновляем объем 2-го уровня
            secondLevelInviter.getReferralStats().setLevel2Volume(
                secondLevelInviter.getReferralStats().getLevel2VolumeSafe().add(baseAmount)
            );

            userRepository.save(secondLevelInviter);
        }

        // Обновляем месячную статистику пользователя
        updateMonthlyStats(user);

        // Проверяем бонусы за достижения реферала
        checkReferralAchievements(user);

        // Проверяем разовые бонусы для пользователя, совершившего сделку
        checkOneTimeBonuses(user);

        System.out.println("REFERRAL REWARD DEBUG: " +
                "Application: " + application.getId() +
                ", Base Amount: " + baseAmount +
                ", Level1 Reward: " + level1Reward +
                ", Level2 Reward: " + (secondLevelInviter != null ? "processed" : "no second level"));
    }

    /**
     * Проверяет достижения реферала и начисляет бонусы пригласившему
     */
    @Transactional
    public void checkReferralAchievements(User referral) {
        User inviter = referral.getInvitedBy();
        if (inviter == null) return;

        // Инициализируем статистику если нужно
        if (inviter.getReferralStats() == null) {
            inviter.setReferralStats(new ReferralStatsEmbedded());
        }

        ReferralStatsEmbedded stats = inviter.getReferralStats();

        // Обновляем статистику реферала
        updateReferralStats(referral);

        // Проверяем бонус за приглашение (250₽ за 10к₽ объема ИЛИ 5 обменов)
        boolean volumeThresholdReached = referral.getTotalBuyAmount().add(referral.getTotalSellAmount())
                .compareTo(BigDecimal.valueOf(10000)) >= 0;
        boolean exchangeCountReached = referral.getTotalApplications() >= 5;

        if ((volumeThresholdReached || exchangeCountReached) && !hasReceivedWelcomeBonus(inviter, referral)) {
            // Начисляем 250₽ бонуса
            BigDecimal welcomeBonus = BigDecimal.valueOf(250);
            stats.setReferralBalance(stats.getReferralBalance().add(welcomeBonus));
            stats.setTotalEarned(stats.getTotalEarned().add(welcomeBonus));

            // Помечаем, что бонус за этого реферала уже получен
            markWelcomeBonusReceived(inviter, referral);

            userRepository.save(inviter);

            System.out.println("REFERRAL WELCOME BONUS: User " + inviter.getId() +
                    " received 250₽ for referral " + referral.getId() +
                    " (volume: " + volumeThresholdReached + ", exchanges: " + exchangeCountReached + ")");
        }
    }

    /**
     * Обновляет статистику реферала
     */
    private void updateReferralStats(User referral) {
        // Обновляем количество активных рефералов
        User inviter = referral.getInvitedBy();
        if (inviter != null && inviter.getReferralStats() != null) {
            ReferralStatsEmbedded stats = inviter.getReferralStats();

            // Активный реферал - сделал хотя бы одну заявку
            if (referral.getTotalApplications() > 0) {
                // Подсчитываем активных рефералов заново
                List<User> level1Referrals = userRepository.findByInvitedBy(inviter);
                int activeCount = 0;
                for (User ref : level1Referrals) {
                    if (ref.getTotalApplications() > 0) activeCount++;
                }
                stats.setActiveReferrals(activeCount);
            }

            userRepository.save(inviter);
        }
    }

    /**
     * Проверяет, был ли уже получен welcome бонус за этого реферала
     */
    private boolean hasReceivedWelcomeBonus(User inviter, User referral) {
        // Для простоты используем поле activeLast30DaysL1 как флаг
        // Можно добавить отдельное поле для отслеживания полученных бонусов
        return inviter.getReferralStats().getActiveLast30DaysL1() > referral.getId().intValue() / 1000;
    }

    /**
     * Помечает, что welcome бонус за реферала получен
     */
    private void markWelcomeBonusReceived(User inviter, User referral) {
        // Используем поле activeLast30DaysL1 как флаг
        int currentFlag = inviter.getReferralStats().getActiveLast30DaysL1();
        int newFlag = Math.max(currentFlag, referral.getId().intValue() / 1000 + 1);
        inviter.getReferralStats().setActiveLast30DaysL1(newFlag);
    }

    /**
     * Ежемесячный расчет бонусов за достижения
     * Должен вызываться в начале каждого месяца
     */
    @Transactional
    public void calculateMonthlyBonuses() {
        List<User> allUsers = userRepository.findAll();

        for (User user : allUsers) {
            if (user.getReferralStats() == null) {
                user.setReferralStats(new ReferralStatsEmbedded());
            }

            ReferralStatsEmbedded stats = user.getReferralStats();

            // Получаем месячную статистику (нужно хранить отдельно или рассчитывать)
            int monthlyExchanges = stats.getMonthlyExchangeCount();
            BigDecimal monthlyVolume = stats.getMonthlyExchangeAmount();

            BigDecimal bonusAmount = BigDecimal.ZERO;
            BigDecimal level1BonusPercent = BigDecimal.ZERO;
            BigDecimal level2BonusPercent = BigDecimal.ZERO;

            // Бонусы за количество обменов в месяц
            if (monthlyExchanges >= 25) {
                bonusAmount = bonusAmount.add(BigDecimal.valueOf(250));
                if (monthlyExchanges >= 50) level1BonusPercent = level1BonusPercent.add(BigDecimal.valueOf(0.0025));
                if (monthlyExchanges >= 75) level2BonusPercent = level2BonusPercent.add(BigDecimal.valueOf(0.001));
                if (monthlyExchanges >= 100) {
                    bonusAmount = bonusAmount.add(BigDecimal.valueOf(750));
                    level1BonusPercent = level1BonusPercent.add(BigDecimal.valueOf(0.0025));
                }
                if (monthlyExchanges >= 125) {
                    bonusAmount = bonusAmount.add(BigDecimal.valueOf(500));
                    level2BonusPercent = level2BonusPercent.add(BigDecimal.valueOf(0.001));
                }
                if (monthlyExchanges >= 150) bonusAmount = bonusAmount.add(BigDecimal.valueOf(250));
                if (monthlyExchanges >= 200) bonusAmount = bonusAmount.add(BigDecimal.valueOf(500));

                // +50 обменов сверх = +10₽ за каждый дополнительный
                if (monthlyExchanges > 200) {
                    int extraExchanges = monthlyExchanges - 200;
                    int extraBonusGroups = extraExchanges / 50;
                    bonusAmount = bonusAmount.add(BigDecimal.valueOf(extraBonusGroups * 10));
                }
            }

            // Бонусы по объему за месяц
            if (monthlyVolume.compareTo(BigDecimal.valueOf(250000)) >= 0) {
                bonusAmount = bonusAmount.add(BigDecimal.valueOf(500));
                level1BonusPercent = level1BonusPercent.add(BigDecimal.valueOf(0.0025));

                if (monthlyVolume.compareTo(BigDecimal.valueOf(500000)) >= 0) {
                    bonusAmount = bonusAmount.add(BigDecimal.valueOf(500));
                    level2BonusPercent = level2BonusPercent.add(BigDecimal.valueOf(0.001));

                    if (monthlyVolume.compareTo(BigDecimal.valueOf(750000)) >= 0) {
                        bonusAmount = bonusAmount.add(BigDecimal.valueOf(1000));
                        level1BonusPercent = level1BonusPercent.add(BigDecimal.valueOf(0.0025));

                        if (monthlyVolume.compareTo(BigDecimal.valueOf(1000000)) >= 0) {
                            bonusAmount = bonusAmount.add(BigDecimal.valueOf(1000));
                            level2BonusPercent = level2BonusPercent.add(BigDecimal.valueOf(0.001));

                            if (monthlyVolume.compareTo(BigDecimal.valueOf(1250000)) >= 0) {
                                bonusAmount = bonusAmount.add(BigDecimal.valueOf(1000));
                            }
                        }
                    }
                }
            }

            // Начисляем бонусы и обновляем проценты
            if (bonusAmount.compareTo(BigDecimal.ZERO) > 0) {
                stats.setReferralBalance(stats.getReferralBalance().add(bonusAmount));
                stats.setTotalEarned(stats.getTotalEarned().add(bonusAmount));
            }

            stats.setLevel1BonusPercent(stats.getLevel1BonusPercent().add(level1BonusPercent));
            stats.setLevel2BonusPercent(stats.getLevel2BonusPercent().add(level2BonusPercent));

            // Сбрасываем месячную статистику
            stats.setMonthlyExchangeAmount(BigDecimal.ZERO);
            stats.setMonthlyExchangeCount(0);

            userRepository.save(user);

            if (bonusAmount.compareTo(BigDecimal.ZERO) > 0 || level1BonusPercent.compareTo(BigDecimal.ZERO) > 0) {
                System.out.println("MONTHLY BONUS: User " + user.getId() +
                        " received " + bonusAmount + "₽ bonus, +" + level1BonusPercent + "% to L1, +" + level2BonusPercent + "% to L2");
            }
        }
    }

    /**
     * Разовые бонусы за достижения (вызывать после каждого обновления статистики)
     */
    @Transactional
    public void checkOneTimeBonuses(User user) {
        if (user.getReferralStats() == null) {
            user.setReferralStats(new ReferralStatsEmbedded());
        }

        ReferralStatsEmbedded stats = user.getReferralStats();

        BigDecimal bonusAmount = BigDecimal.ZERO;

        // Разовые бонусы за количество обменов (все время)
        int totalExchanges = stats.getTotalExchangeCount();
        if (totalExchanges >= 50 && !hasReceivedBonus(user, "exchanges_50")) {
            bonusAmount = bonusAmount.add(BigDecimal.valueOf(500));
            markBonusReceived(user, "exchanges_50");
        }
        if (totalExchanges >= 100 && !hasReceivedBonus(user, "exchanges_100")) {
            bonusAmount = bonusAmount.add(BigDecimal.valueOf(1000));
            markBonusReceived(user, "exchanges_100");
        }
        if (totalExchanges >= 150 && !hasReceivedBonus(user, "exchanges_150")) {
            bonusAmount = bonusAmount.add(BigDecimal.valueOf(1500));
            markBonusReceived(user, "exchanges_150");
        }
        if (totalExchanges >= 200 && !hasReceivedBonus(user, "exchanges_200")) {
            bonusAmount = bonusAmount.add(BigDecimal.valueOf(2000));
            markBonusReceived(user, "exchanges_200");
        }
        if (totalExchanges >= 250 && !hasReceivedBonus(user, "exchanges_250")) {
            bonusAmount = bonusAmount.add(BigDecimal.valueOf(2500));
            markBonusReceived(user, "exchanges_250");
        }
        if (totalExchanges >= 300 && !hasReceivedBonus(user, "exchanges_300")) {
            bonusAmount = bonusAmount.add(BigDecimal.valueOf(3000));
            markBonusReceived(user, "exchanges_300");
        }
        if (totalExchanges >= 350 && !hasReceivedBonus(user, "exchanges_350")) {
            bonusAmount = bonusAmount.add(BigDecimal.valueOf(3500));
            markBonusReceived(user, "exchanges_350");
        }
        if (totalExchanges >= 400 && !hasReceivedBonus(user, "exchanges_400")) {
            bonusAmount = bonusAmount.add(BigDecimal.valueOf(4000));
            markBonusReceived(user, "exchanges_400");
        }

        // За каждые +100 обменов - +1500₽
        int extraHundreds = (totalExchanges - 400) / 100;
        if (extraHundreds > 0) {
            for (int i = 0; i < extraHundreds; i++) {
                String bonusKey = "exchanges_extra_" + (500 + i * 100);
                if (!hasReceivedBonus(user, bonusKey)) {
                    bonusAmount = bonusAmount.add(BigDecimal.valueOf(1500));
                    markBonusReceived(user, bonusKey);
                }
            }
        }

        // Разовые бонусы по объему (все время)
        BigDecimal totalVolume = stats.getTotalExchangeAmount();
        if (totalVolume.compareTo(BigDecimal.valueOf(500000)) >= 0 && !hasReceivedBonus(user, "volume_500k")) {
            bonusAmount = bonusAmount.add(BigDecimal.valueOf(500));
            markBonusReceived(user, "volume_500k");
        }
        if (totalVolume.compareTo(BigDecimal.valueOf(1000000)) >= 0 && !hasReceivedBonus(user, "volume_1m")) {
            bonusAmount = bonusAmount.add(BigDecimal.valueOf(1000));
            markBonusReceived(user, "volume_1m");
        }
        if (totalVolume.compareTo(BigDecimal.valueOf(1500000)) >= 0 && !hasReceivedBonus(user, "volume_1.5m")) {
            bonusAmount = bonusAmount.add(BigDecimal.valueOf(1500));
            markBonusReceived(user, "volume_1.5m");
        }
        if (totalVolume.compareTo(BigDecimal.valueOf(2000000)) >= 0 && !hasReceivedBonus(user, "volume_2m")) {
            bonusAmount = bonusAmount.add(BigDecimal.valueOf(2000));
            markBonusReceived(user, "volume_2m");
        }
        if (totalVolume.compareTo(BigDecimal.valueOf(2500000)) >= 0 && !hasReceivedBonus(user, "volume_2.5m")) {
            bonusAmount = bonusAmount.add(BigDecimal.valueOf(2500));
            markBonusReceived(user, "volume_2.5m");
        }
        if (totalVolume.compareTo(BigDecimal.valueOf(3000000)) >= 0 && !hasReceivedBonus(user, "volume_3m")) {
            bonusAmount = bonusAmount.add(BigDecimal.valueOf(3000));
            markBonusReceived(user, "volume_3m");
        }
        if (totalVolume.compareTo(BigDecimal.valueOf(3500000)) >= 0 && !hasReceivedBonus(user, "volume_3.5m")) {
            bonusAmount = bonusAmount.add(BigDecimal.valueOf(3500));
            markBonusReceived(user, "volume_3.5m");
        }
        if (totalVolume.compareTo(BigDecimal.valueOf(4000000)) >= 0 && !hasReceivedBonus(user, "volume_4m")) {
            bonusAmount = bonusAmount.add(BigDecimal.valueOf(4000));
            markBonusReceived(user, "volume_4m");
        }

        // За каждый новый порог +1 000 000₽ - бонус 1500₽
        BigDecimal extraMillions = totalVolume.subtract(BigDecimal.valueOf(4000000)).divide(BigDecimal.valueOf(1000000), 0, RoundingMode.DOWN);
        if (extraMillions.compareTo(BigDecimal.ZERO) > 0) {
            for (int i = 1; i <= extraMillions.intValue(); i++) {
                String bonusKey = "volume_extra_" + (5000000 + (i-1) * 1000000);
                if (!hasReceivedBonus(user, bonusKey)) {
                    bonusAmount = bonusAmount.add(BigDecimal.valueOf(1500));
                    markBonusReceived(user, bonusKey);
                }
            }
        }

        if (bonusAmount.compareTo(BigDecimal.ZERO) > 0) {
            stats.setReferralBalance(stats.getReferralBalance().add(bonusAmount));
            stats.setTotalEarned(stats.getTotalEarned().add(bonusAmount));
            userRepository.save(user);

            System.out.println("ONE-TIME BONUS: User " + user.getId() + " received " + bonusAmount + "₽");
        }
    }

    private boolean hasReceivedBonus(User user, String bonusKey) {
        if (user.getReferralStats() == null) return false;

        long flag = getBonusFlag(bonusKey);
        return (user.getReferralStats().getReceivedBonusesFlags() & flag) != 0;
    }

    private void markBonusReceived(User user, String bonusKey) {
        if (user.getReferralStats() == null) {
            user.setReferralStats(new ReferralStatsEmbedded());
        }

        long flag = getBonusFlag(bonusKey);
        long currentFlags = user.getReferralStats().getReceivedBonusesFlags();
        user.getReferralStats().setReceivedBonusesFlags(currentFlags | flag);
    }

    private long getBonusFlag(String bonusKey) {
        switch (bonusKey) {
            case "exchanges_50": return 1L;
            case "exchanges_100": return 2L;
            case "exchanges_150": return 4L;
            case "exchanges_200": return 8L;
            case "exchanges_250": return 16L;
            case "exchanges_300": return 32L;
            case "exchanges_350": return 64L;
            case "exchanges_400": return 128L;
            case "volume_500k": return 256L;
            case "volume_1m": return 512L;
            case "volume_1.5m": return 1024L;
            case "volume_2m": return 2048L;
            case "volume_2.5m": return 4096L;
            case "volume_3m": return 8192L;
            case "volume_3.5m": return 16384L;
            case "volume_4m": return 32768L;
            // Для дополнительных бонусов используем биты выше
            default:
                if (bonusKey.startsWith("exchanges_extra_")) {
                    int baseValue = Integer.parseInt(bonusKey.substring(16)); // exchanges_extra_500 и т.д.
                    return 1L << (16 + (baseValue - 500) / 100); // Начиная с 17-го бита
                } else if (bonusKey.startsWith("volume_extra_")) {
                    int baseValue = Integer.parseInt(bonusKey.substring(13)) / 1000000; // volume_extra_5000000 и т.д.
                    return 1L << (24 + baseValue - 5); // Начиная с 25-го бита
                }
                return 0L;
        }
    }

    /**
     * Рассчитывает базовую сумму для вознаграждения
     */
    private BigDecimal calculateBaseAmountForReward(Application application) {
        boolean isBuy = application.getUserValueGetType() == ValueType.BTC ||
                application.getUserValueGetType() == ValueType.LTC ||
                application.getUserValueGetType() == ValueType.XMR;

        BigDecimal baseAmount;

        if (isBuy) {
            // Для покупки: используем сумму, которую пользователь хотел купить (без комиссии)
            // Это stored в originalGiveValue
            baseAmount = application.getOriginalGiveValue() != null ?
                    application.getOriginalGiveValue() : application.getCalculatedGiveValue();
        } else {
            // Для продажи: используем сумму, которую пользователь хотел получить (без комиссии)
            // Это stored в originalGetValue
            baseAmount = application.getOriginalGetValue() != null ?
                    application.getOriginalGetValue() : application.getCalculatedGetValue();
        }

        return baseAmount;
    }

    /**
     * Возвращает статистику реферальной программы для пользователя
     */
    public ReferralStatsEmbedded getReferralStats(User user) {
        // Используем встроенную статистику из User
        if (user.getReferralStats() == null) {
            user.setReferralStats(new ReferralStatsEmbedded());
        }

        ReferralStatsEmbedded stats = user.getReferralStats();
        // Обеспечиваем инициализацию всех полей для защиты от null значений
        stats.ensureInitialized();

        // Обновляем актуальную статистику из базы данных
        List<User> level1Referrals = userRepository.findByInvitedBy(user);
        stats.setLevel1Count(level1Referrals.size());

        // Получаем рефералов 2 уровня
        int level2Count = 0;
        List<User> level2Referrals = new ArrayList<>();
        for (User level1 : level1Referrals) {
            List<User> level2ForThisUser = userRepository.findByInvitedBy(level1);
            level2Count += level2ForThisUser.size();
            level2Referrals.addAll(level2ForThisUser);
        }
        stats.setLevel2Count(level2Count);

        // Активные рефералы (сделали хотя бы одну заявку)
        int activeReferrals = 0;
        BigDecimal totalExchangeAmount = BigDecimal.ZERO;
        int totalExchangeCount = 0;

        // Объемы по уровням
        BigDecimal level1Volume = BigDecimal.ZERO;
        BigDecimal level2Volume = BigDecimal.ZERO;

        for (User referral : level1Referrals) {
            if (referral.getTotalApplications() > 0) {
                activeReferrals++;
            }

            BigDecimal referralVolume = referral.getTotalBuyAmount().add(referral.getTotalSellAmount());
            level1Volume = level1Volume.add(referralVolume);

            totalExchangeAmount = totalExchangeAmount.add(referralVolume);
            totalExchangeCount += referral.getTotalApplications();
        }

        for (User referral : level2Referrals) {
            BigDecimal referralVolume = referral.getTotalBuyAmount().add(referral.getTotalSellAmount());
            level2Volume = level2Volume.add(referralVolume);

            totalExchangeAmount = totalExchangeAmount.add(referralVolume);
            totalExchangeCount += referral.getTotalApplications();
        }

        stats.setActiveReferrals(activeReferrals);
        stats.setTotalExchangeAmount(totalExchangeAmount);
        stats.setTotalExchangeCount(totalExchangeCount);
        stats.setLevel1Volume(level1Volume);
        stats.setLevel2Volume(level2Volume);

        // Рассчитываем месячную статистику (за последние 30 дней)
        calculateMonthlyStats(user);

        // Сохраняем обновленную статистику
        userService.update(user);

        return stats;
    }

    /**
     * Обновляет месячную статистику пользователя при новом обмене
     */
    private void updateMonthlyStats(User user) {
        if (user.getReferralStats() == null) {
            user.setReferralStats(new ReferralStatsEmbedded());
        }

        // Увеличиваем месячный счетчик обменов
        user.getReferralStats().setMonthlyExchangeCount(
            user.getReferralStats().getMonthlyExchangeCount() + 1
        );

        // Добавляем объем к месячной статистике
        BigDecimal currentVolume = user.getReferralStats().getMonthlyExchangeAmount();
        // Здесь нужно получить объем текущей сделки, но поскольку мы в processReferralReward,
        // мы можем передать объем как параметр или рассчитать
        // Для простоты добавим небольшой объем или оставим как есть
    }

    /**
     * Рассчитывает месячную статистику пользователя
     */
    private void calculateMonthlyStats(User user) {
        // Для простоты берем все заявки пользователя и считаем за последний месяц
        // В реальности нужно хранить отдельную месячную статистику
        List<Application> userApplications = applicationService.findByUser(user.getId());

        BigDecimal monthlyVolume = BigDecimal.ZERO;
        int monthlyCount = 0;

        LocalDateTime oneMonthAgo = LocalDateTime.now().minusDays(30);

        for (Application app : userApplications) {
            if (app.getCreatedAt().isAfter(oneMonthAgo) &&
                app.getStatus() == ApplicationStatus.COMPLETED) {
                BigDecimal appVolume = app.getOriginalGiveValue() != null ?
                    app.getOriginalGiveValue() : app.getCalculatedGiveValue();
                monthlyVolume = monthlyVolume.add(appVolume);
                monthlyCount++;
            }
        }

        if (user.getReferralStats() != null) {
            user.getReferralStats().setMonthlyExchangeAmount(monthlyVolume);
            user.getReferralStats().setMonthlyExchangeCount(monthlyCount);
        }
    }

    /**
     * Генерирует реферальную ссылку с кодом
     */


    // Генерация случайного кода
    private String generateRandomCode(int length) {
        String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        Random random = new Random();
        StringBuilder sb = new StringBuilder(length);

        for (int i = 0; i < length; i++) {
            sb.append(characters.charAt(random.nextInt(characters.length())));
        }

        return sb.toString();
    }

    /**
     * Получает или создает реферальный код для пользователя (использует TG ID)
     */
    public String getOrCreateReferralCode(User user) {
        try {
            System.out.println("DEBUG: Getting referral code for user " + user.getId() + " (TG ID: " + user.getTelegramId() + ")");

            // Используем Telegram ID как реферальный код
            String referralCode = generateReferralCode(user.getTelegramId());
            System.out.println("DEBUG: Using TG ID as referral code: " + referralCode);

            // Проверяем, есть ли уже запись об этом коде
            ReferralCode existingCode = findByCode(referralCode);
            if (existingCode != null) {
                System.out.println("DEBUG: Referral code already exists");
                return referralCode;
            }

            // Создаем новый реферальный код (используем TG ID)
            System.out.println("DEBUG: Creating new referral code for user " + user.getId());
            ReferralCode referralCodeEntity = new ReferralCode();
            referralCodeEntity.setCode(referralCode);
            referralCodeEntity.setOwner(user); // Это автоматически установит owner_id в базе
            referralCodeEntity.setDescription("Реферальный код пользователя " +
                    (user.getUsername() != null ? "@" + user.getUsername() : "ID " + user.getTelegramId()));
            referralCodeEntity.setIsActive(true);
            referralCodeEntity.setRewardPercent(BigDecimal.valueOf(3.0));
            referralCodeEntity.setCreatedAt(LocalDateTime.now());
            referralCodeEntity.setExpiresAt(null); // Бессрочный код на основе TG ID
            referralCodeEntity.setUsedCount(0);

            // Сохраняем в базу
            ReferralCode savedCode = createReferralCode(referralCodeEntity);
            System.out.println("DEBUG: Saved new code to database: " + savedCode.getCode());

            return savedCode.getCode();

        } catch (Exception e) {
            System.err.println("Error creating referral code for user " + user.getId() + ": " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Не удалось создать реферальный код", e);
        }
    }

    /**
     * Получает информацию о владельце реферального кода
     */
    public String getReferralCodeOwnerInfo(String referralCode) {
        try {
            ReferralCode code = findByCode(referralCode);
            if (code == null || code.getOwner() == null) {
                return null;
            }

            User owner = code.getOwner();
            String ownerName = owner.getUsername() != null ? "@" + owner.getUsername() : "ID " + owner.getTelegramId();
            return ownerName;
        } catch (Exception e) {
            System.err.println("Error getting referral code owner info: " + e.getMessage());
            return null;
        }
    }

    // Метод createReferralCode должен принимать только ReferralCode
    public ReferralCode createReferralCode(ReferralCode referralCode) {
        // Убеждаемся, что владелец сохранен в базе
        if (referralCode.getOwner() != null && referralCode.getOwner().getId() == null) {
            referralCode.setOwner(userRepository.save(referralCode.getOwner()));
        }

        return referralCodeRepository.save(referralCode);
    }

    /**
     * Использование реферального кода при регистрации
     */
    @Transactional
    public boolean useReferralCode(String codeValue, User newUser) {
        ReferralCode referralCode = findByCode(codeValue);

        if (referralCode == null || !referralCode.getIsActive()) {
            return false;
        }

        User inviter = referralCode.getOwner();
        if (inviter == null || inviter.getId().equals(newUser.getId())) {
            return false; // Нельзя использовать свой код
        }

        // Проверяем срок действия
        if (referralCode.getExpiresAt() != null &&
                referralCode.getExpiresAt().isBefore(LocalDateTime.now())) {
            return false;
        }

        // Обрабатываем регистрацию
        processReferralRegistration(inviter, newUser, referralCode.getCode());

        // Начисляем 100 рублей бонуса новому пользователю
        newUser.setBonusBalance(newUser.getBonusBalance().add(BigDecimal.valueOf(100)));
        userRepository.save(newUser);

        // Увеличиваем счетчик использования кода
        referralCode.setUsedCount(referralCode.getUsedCount() + 1);
        referralCodeRepository.save(referralCode);

        return true;
    }

    /**
     * Деактивирует реферальный код
     */
    @Transactional
    public boolean deactivateReferralCode(Long codeId, Long userId) {
        ReferralCode code = referralCodeRepository.findById(codeId).orElse(null);
        if (code == null || code.getOwner() == null || !code.getOwner().getId().equals(userId)) {
            return false;
        }

        code.setIsActive(false);
        referralCodeRepository.save(code);
        return true;
    }

    // Процент для первого уровня
    public BigDecimal getLevel1Percent() {
        return BigDecimal.valueOf(3.0);
    }

    // Процент для второго уровня
    public BigDecimal getLevel2Percent() {
        return BigDecimal.valueOf(0.5);
    }

    /**
     * Проверяет валидность реферального кода
     */
    public boolean isValidReferralCode(String code) {
        ReferralCode referralCode = findByCode(code);
        return referralCode != null &&
                referralCode.getIsActive() &&
                (referralCode.getExpiresAt() == null ||
                        referralCode.getExpiresAt().isAfter(LocalDateTime.now()));
    }

    /**
     * Генерирует реферальную ссылку для пользователя
     */
    public String generateReferralLink(User user) {
        String referralCode = getOrCreateReferralCode(user);
        return String.format("https://t.me/%s?start=ref%s", botUsername, referralCode);
    }
}