package com.seeewo4kin.bot.service;

import com.seeewo4kin.bot.Entity.*;
import com.seeewo4kin.bot.Entity.ReferralStatsEmbedded;
import com.seeewo4kin.bot.Enums.BonusType;
import com.seeewo4kin.bot.Enums.ReferralLevel;
import com.seeewo4kin.bot.Enums.ValueType;
import com.seeewo4kin.bot.repository.ReferralBonusEventRepository;
import com.seeewo4kin.bot.repository.ReferralCodeRepository;
import com.seeewo4kin.bot.repository.ReferralRelationshipRepository;
import com.seeewo4kin.bot.repository.UserRepository;
import com.seeewo4kin.bot.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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

    public ReferralService(ReferralCodeRepository referralCodeRepository,
                           ReferralRelationshipRepository referralRelationshipRepository,
                           ReferralBonusEventRepository referralBonusEventRepository,
                           UserRepository userRepository,
                           ApplicationService applicationService,
                           UserService userService) {
        this.referralCodeRepository = referralCodeRepository;
        this.referralRelationshipRepository = referralRelationshipRepository;
        this.userRepository = userRepository;
        this.userService = userService;
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
     * Генерирует уникальный код
     */
    private String generateUniqueCode() {
        String symbols = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        Random random = new Random();
        String code;

        do {
            StringBuilder codeBuilder = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                codeBuilder.append(symbols.charAt(random.nextInt(symbols.length())));
            }
            code = codeBuilder.toString();
        } while (referralCodeRepository.existsByCode(code));

        return code;
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
    /**
     * Получает активные реферальные коды пользователя
     */
    public List<ReferralCode> getUserActiveReferralCodes(Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return List.of();
        }

        // FIX: Use the correct repository method that returns List
        return referralCodeRepository.findByUserAndIsActiveTrue(user);
    }

    /**
     * Обрабатывает регистрацию по реферальной ссылке
     */
    @Transactional
    public void processReferralRegistration(User inviter, User newUser, String referralCodeValue) {
        if (inviter == null || newUser == null || inviter.getId().equals(newUser.getId())) {
            return;
        }

        // Находим реферальный код
        ReferralCode referralCode = findByCode(referralCodeValue);
        if (referralCode == null) {
            return;
        }

        // Устанавливаем связь
        newUser.setInvitedBy(inviter);
        newUser.setInvitedAt(LocalDateTime.now());
        newUser.setUsedReferralCode(referralCodeValue); // Сохраняем использованный реферальный код
        userRepository.save(newUser);

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

        // Вознаграждение для первого уровня (3%)
        BigDecimal level1Reward = baseAmount.multiply(BigDecimal.valueOf(0.03));

        // Создаем запись о бонусе для первого уровня
        ReferralBonusEvent level1Bonus = new ReferralBonusEvent();
        level1Bonus.setUser(inviter);
        level1Bonus.setBonusType(BonusType.REFERRAL_LEVEL_1);
        level1Bonus.setAmount(level1Reward);
        level1Bonus.setDescription("Реферальный бонус 1 уровня за заявку #" + application.getId());
        // TODO: Сохранить level1Bonus в базу данных

        inviter.setReferralEarnings(inviter.getReferralEarnings().add(level1Reward));
        inviter.setBonusBalance(inviter.getBonusBalance().add(level1Reward));
        userRepository.save(inviter);

        // Вознаграждение для второго уровня (0.5%)
        User secondLevelInviter = inviter.getInvitedBy();
        if (secondLevelInviter != null) {
            BigDecimal level2Reward = baseAmount.multiply(BigDecimal.valueOf(0.005));

            // Создаем запись о бонусе для второго уровня
            ReferralBonusEvent level2Bonus = new ReferralBonusEvent();
            level2Bonus.setUser(secondLevelInviter);
            level2Bonus.setBonusType(BonusType.REFERRAL_LEVEL_2);
            level2Bonus.setAmount(level2Reward);
            level2Bonus.setDescription("Реферальный бонус 2 уровня за заявку #" + application.getId());
            // TODO: Сохранить level2Bonus в базу данных

            secondLevelInviter.setReferralEarnings(secondLevelInviter.getReferralEarnings().add(level2Reward));
            secondLevelInviter.setBonusBalance(secondLevelInviter.getBonusBalance().add(level2Reward));
            userRepository.save(secondLevelInviter);
        }

        System.out.println("REFERRAL REWARD DEBUG: " +
                "Application: " + application.getId() +
                ", Base Amount: " + baseAmount +
                ", Level1 Reward: " + level1Reward +
                ", Level2 Reward: " + (secondLevelInviter != null ? "processed" : "no second level"));
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
            // Для покупки: сумма, которую пользователь заплатил (включая комиссию)
            // Восстанавливаем исходную сумму: текущая сумма + использованные бонусы
            baseAmount = application.getCalculatedGiveValue()
                    .add(application.getUsedBonusBalance() != null ?
                            application.getUsedBonusBalance() : BigDecimal.ZERO);
        } else {
            // Для продажи: сумма, которую пользователь получил (после комиссии)
            // Восстанавливаем исходную сумму: текущая сумма - использованные бонусы
            baseAmount = application.getCalculatedGetValue()
                    .subtract(application.getUsedBonusBalance() != null ?
                            application.getUsedBonusBalance() : BigDecimal.ZERO);
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
        for (User level1 : level1Referrals) {
            level2Count += userRepository.findByInvitedBy(level1).size();
        }
        stats.setLevel2Count(level2Count);

        // Активные рефералы (сделали хотя бы одну заявку)
        int activeReferrals = 0;
        BigDecimal totalExchangeAmount = BigDecimal.ZERO;
        int totalExchangeCount = 0;

        for (User referral : level1Referrals) {
            if (referral.getTotalApplications() > 0) {
                activeReferrals++;
            }

            totalExchangeAmount = totalExchangeAmount
                    .add(referral.getTotalBuyAmount())
                    .add(referral.getTotalSellAmount());
            totalExchangeCount += referral.getTotalApplications();
        }

        stats.setActiveReferrals(activeReferrals);
        stats.setTotalExchangeAmount(totalExchangeAmount);
        stats.setTotalExchangeCount(totalExchangeCount);

        // Сохраняем обновленную статистику
        userService.update(user);

        return stats;
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

    // Форматирование реферальной ссылки
    private String formatReferralLink(String code) {
        String botUsername = "COSANOSTRA24_bot"; // Замените на реальный username бота
        return "https://t.me/" + botUsername + "?start=ref_" + code;
    }
    public String generateReferralLinkWithCode(User user) {
        try {
            // 1. Проверяем, есть ли уже активный реферальный код у пользователя
            // FIX: Call the method directly without referralService
            List<ReferralCode> existingCodes = getUserActiveReferralCodes(user.getId());

            if (!existingCodes.isEmpty()) {
                ReferralCode code = existingCodes.get(0); // Take the first active code
                if (code.getExpiresAt().isAfter(LocalDateTime.now())) {
                    // Возвращаем существующую активную ссылку
                    return formatReferralLink(code.getCode());
                } else {
                    // Деактивируем просроченный код
                    code.setIsActive(false);
                    referralCodeRepository.save(code);
                }
            }

            // 2. Создаем новый реферальный код
            ReferralCode referralCode = new ReferralCode();
            referralCode.setCode(generateUniqueCode());
            referralCode.setUser(user);
            referralCode.setOwnerId(user.getId());
            referralCode.setDescription("Реферальный код пользователя " +
                    (user.getUsername() != null ? user.getUsername() : "User_" + user.getId()));
            referralCode.setIsActive(true);
            referralCode.setRewardPercent(BigDecimal.valueOf(3.0));
            referralCode.setCreatedAt(LocalDateTime.now());
            referralCode.setExpiresAt(LocalDateTime.now().plusMonths(6));
            referralCode.setUsedCount(0);

            // 3. Сохраняем в базу
            ReferralCode savedCode = createReferralCode(referralCode);

            // 4. Возвращаем сформированную ссылку
            return formatReferralLink(savedCode.getCode());

        } catch (Exception e) {
            System.err.println("Error generating referral link for user " + user.getId() + ": " + e.getMessage());
            throw new RuntimeException("Не удалось создать реферальную ссылку", e);
        }
    }

    // Метод createReferralCode должен принимать только ReferralCode
    public ReferralCode createReferralCode(ReferralCode referralCode) {
        // Если нужно установить ownerId, делаем это здесь
        if (referralCode.getOwnerId() == null && referralCode.getUser() != null) {
            referralCode.setOwnerId(referralCode.getUser().getId());
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

        User inviter = referralCode.getUser();
        if (inviter.getId().equals(newUser.getId())) {
            return false; // Нельзя использовать свой код
        }

        // Проверяем срок действия
        if (referralCode.getExpiresAt() != null &&
                referralCode.getExpiresAt().isBefore(LocalDateTime.now())) {
            return false;
        }

        // Обрабатываем регистрацию
        processReferralRegistration(inviter, newUser, referralCode.getCode());

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
        if (code == null || !code.getUser().getId().equals(userId)) {
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
}