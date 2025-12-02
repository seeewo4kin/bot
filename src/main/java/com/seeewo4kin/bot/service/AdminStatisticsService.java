package com.seeewo4kin.bot.service;

import com.seeewo4kin.bot.Entity.*;
import com.seeewo4kin.bot.Enums.ApplicationStatus;
import com.seeewo4kin.bot.repository.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AdminStatisticsService {

    private final ApplicationRepository applicationRepository;
    private final UserRepository userRepository;
    private final ReferralBonusEventRepository referralBonusEventRepository;
    private final ReferralRelationshipRepository referralRelationshipRepository;

    public AdminStatisticsService(
            ApplicationRepository applicationRepository,
            UserRepository userRepository,
            ReferralBonusEventRepository referralBonusEventRepository,
            ReferralRelationshipRepository referralRelationshipRepository) {
        this.applicationRepository = applicationRepository;
        this.userRepository = userRepository;
        this.referralBonusEventRepository = referralBonusEventRepository;
        this.referralRelationshipRepository = referralRelationshipRepository;
    }

    /**
     * Получение общей статистики за период
     */
    public Map<String, Object> getGeneralStatistics(int days) {
        LocalDateTime startDate = LocalDateTime.now().minusDays(days);
        LocalDateTime endDate = LocalDateTime.now();

        Map<String, Object> stats = new HashMap<>();

        // Статистика заявок
        List<Application> applications = applicationRepository.findByCreatedAtBetween(startDate, endDate);
        stats.put("applications", getApplicationsStats(applications));

        // Статистика пользователей
        stats.put("users", getUsersStats(startDate, endDate));

        // Статистика рефералов
        stats.put("referrals", getReferralsStats(startDate, endDate));

        // Финансовая статистика
        stats.put("finance", getFinanceStats(applications));

        return stats;
    }

    /**
     * Получение статистики по заявкам
     */
    private Map<String, Object> getApplicationsStats(List<Application> applications) {
        Map<String, Object> stats = new HashMap<>();

        // Общее количество заявок
        stats.put("total", applications.size());

        // По статусам
        Map<ApplicationStatus, Long> byStatus = applications.stream()
                .collect(Collectors.groupingBy(Application::getStatus, Collectors.counting()));
        stats.put("byStatus", byStatus);

        // Завершенные заявки
        List<Application> completedApps = applications.stream()
                .filter(app -> app.getStatus() == ApplicationStatus.COMPLETED)
                .collect(Collectors.toList());

        stats.put("completed", completedApps.size());

        // Общая сумма заявок
        BigDecimal totalAmount = completedApps.stream()
                .map(app -> app.getCalculatedGiveValue() != null ? app.getCalculatedGiveValue() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        stats.put("totalAmount", totalAmount);

        // Комиссии
        BigDecimal totalCommission = completedApps.stream()
                .map(app -> app.getCalculatedGiveValue() != null && app.getOriginalGiveValue() != null ?
                        app.getCalculatedGiveValue().subtract(app.getOriginalGiveValue()) : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        stats.put("totalCommission", totalCommission);

        // По дням
        Map<LocalDate, List<Application>> byDay = applications.stream()
                .collect(Collectors.groupingBy(app -> app.getCreatedAt().toLocalDate()));

        Map<String, Map<String, Object>> dailyStats = new TreeMap<>();
        byDay.forEach((date, dayApps) -> {
            Map<String, Object> dayStat = new HashMap<>();
            dayStat.put("total", dayApps.size());
            dayStat.put("completed", dayApps.stream().filter(app -> app.getStatus() == ApplicationStatus.COMPLETED).count());

            BigDecimal dayAmount = dayApps.stream()
                    .filter(app -> app.getStatus() == ApplicationStatus.COMPLETED)
                    .map(app -> app.getCalculatedGiveValue() != null ? app.getCalculatedGiveValue() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            dayStat.put("amount", dayAmount);

            dailyStats.put(date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")), dayStat);
        });
        stats.put("daily", dailyStats);

        return stats;
    }

    /**
     * Получение статистики по пользователям
     */
    private Map<String, Object> getUsersStats(LocalDateTime startDate, LocalDateTime endDate) {
        Map<String, Object> stats = new HashMap<>();

        // Все пользователи
        List<User> allUsers = userRepository.findAll();
        stats.put("total", allUsers.size());

        // Новые пользователи за период
        List<User> newUsers = allUsers.stream()
                .filter(user -> user.getCreatedAt() != null &&
                        user.getCreatedAt().isAfter(startDate) &&
                        !user.getCreatedAt().isAfter(endDate))
                .collect(Collectors.toList());

        stats.put("new", newUsers.size());

        // Активные пользователи (создали заявки за период)
        List<Application> periodApplications = applicationRepository.findByCreatedAtBetween(startDate, endDate);
        Set<Long> activeUserIds = periodApplications.stream()
                .map(app -> app.getUser().getId())
                .collect(Collectors.toSet());
        stats.put("active", activeUserIds.size());

        // Пользователи с реферальными кодами
        long usersWithReferralCode = userRepository.countByUsedReferralCodeIsNotNull();
        stats.put("withReferralCode", usersWithReferralCode);

        // Новые пользователи по дням
        Map<LocalDate, Long> newUsersByDay = newUsers.stream()
                .collect(Collectors.groupingBy(
                        user -> user.getCreatedAt().toLocalDate(),
                        Collectors.counting()
                ));

        Map<String, Long> dailyNewUsers = new TreeMap<>();
        newUsersByDay.forEach((date, count) ->
                dailyNewUsers.put(date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")), count));
        stats.put("dailyNew", dailyNewUsers);

        return stats;
    }

    /**
     * Получение статистики по рефералам
     */
    private Map<String, Object> getReferralsStats(LocalDateTime startDate, LocalDateTime endDate) {
        Map<String, Object> stats = new HashMap<>();

        // Все реферальные отношения
        List<ReferralRelationship> allRelationships = referralRelationshipRepository.findAll();
        stats.put("totalRelationships", allRelationships.size());

        // Реферальные бонусы за период
        List<ReferralBonusEvent> bonusEvents = referralBonusEventRepository.findAll().stream()
                .filter(event -> event.getCreatedAt() != null &&
                        event.getCreatedAt().isAfter(startDate) &&
                        event.getCreatedAt().isBefore(endDate))
                .collect(Collectors.toList());

        stats.put("bonusEvents", bonusEvents.size());

        // Общая сумма реферальных бонусов
        BigDecimal totalReferralBonus = bonusEvents.stream()
                .map(event -> event.getAmount() != null ? event.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        stats.put("totalBonusAmount", totalReferralBonus);

        // Реферальные бонусы по дням
        Map<LocalDate, BigDecimal> bonusByDay = bonusEvents.stream()
                .collect(Collectors.groupingBy(
                        event -> event.getCreatedAt().toLocalDate(),
                        Collectors.mapping(
                                event -> event.getAmount() != null ? event.getAmount() : BigDecimal.ZERO,
                                Collectors.reducing(BigDecimal.ZERO, BigDecimal::add)
                        )
                ));

        Map<String, BigDecimal> dailyBonus = new TreeMap<>();
        bonusByDay.forEach((date, amount) ->
                dailyBonus.put(date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")), amount));
        stats.put("dailyBonus", dailyBonus);

        // Топ рефереров
        Map<User, Long> referrerStats = allRelationships.stream()
                .collect(Collectors.groupingBy(ReferralRelationship::getInviter, Collectors.counting()));

        List<Map<String, Object>> topReferrers = referrerStats.entrySet().stream()
                .sorted(Map.Entry.<User, Long>comparingByValue().reversed())
                .limit(10)
                .map(entry -> {
                    Map<String, Object> referrer = new HashMap<>();
                    referrer.put("username", entry.getKey().getUsername() != null ? entry.getKey().getUsername() : "ID:" + entry.getKey().getId());
                    referrer.put("referrals", entry.getValue());
                    return referrer;
                })
                .collect(Collectors.toList());

        stats.put("topReferrers", topReferrers);

        return stats;
    }

    /**
     * Получение финансовой статистики
     */
    private Map<String, Object> getFinanceStats(List<Application> applications) {
        Map<String, Object> stats = new HashMap<>();

        List<Application> completedApps = applications.stream()
                .filter(app -> app.getStatus() == ApplicationStatus.COMPLETED)
                .collect(Collectors.toList());

        // Общая сумма оборота
        BigDecimal totalTurnover = completedApps.stream()
                .map(app -> app.getCalculatedGiveValue() != null ? app.getCalculatedGiveValue() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        stats.put("totalTurnover", totalTurnover);

        // Общая комиссия
        BigDecimal totalCommission = completedApps.stream()
                .map(app -> {
                    if (app.getCalculatedGiveValue() != null && app.getOriginalGiveValue() != null) {
                        return app.getCalculatedGiveValue().subtract(app.getOriginalGiveValue());
                    }
                    return BigDecimal.ZERO;
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        stats.put("totalCommission", totalCommission);

        // Средняя комиссия
        if (!completedApps.isEmpty()) {
            stats.put("averageCommission", totalCommission.divide(BigDecimal.valueOf(completedApps.size()), 2, BigDecimal.ROUND_HALF_UP));
        } else {
            stats.put("averageCommission", BigDecimal.ZERO);
        }

        // По типам операций (покупка/продажа)
        Map<String, BigDecimal> byOperationType = completedApps.stream()
                .collect(Collectors.groupingBy(
                        app -> {
                            boolean isBuy = app.getUserValueGetType() != null &&
                                    (app.getUserValueGetType().toString().startsWith("BTC") ||
                                     app.getUserValueGetType().toString().startsWith("LTC") ||
                                     app.getUserValueGetType().toString().startsWith("XMR"));
                            return isBuy ? "BUY" : "SELL";
                        },
                        Collectors.mapping(
                                app -> app.getCalculatedGiveValue() != null ? app.getCalculatedGiveValue() : BigDecimal.ZERO,
                                Collectors.reducing(BigDecimal.ZERO, BigDecimal::add)
                        )
                ));

        stats.put("byOperationType", byOperationType);

        // По криптовалютам
        Map<String, BigDecimal> byCrypto = completedApps.stream()
                .collect(Collectors.groupingBy(
                        app -> app.getCryptoCurrencySafe().getSymbol(),
                        Collectors.mapping(
                                app -> app.getCalculatedGiveValue() != null ? app.getCalculatedGiveValue() : BigDecimal.ZERO,
                                Collectors.reducing(BigDecimal.ZERO, BigDecimal::add)
                        )
                ));

        stats.put("byCrypto", byCrypto);

        return stats;
    }

    /**
     * Форматирование статистики для отображения
     */
    public String formatStatistics(Map<String, Object> stats, String period) {
        StringBuilder result = new StringBuilder();
        result.append("📊 СТАТИСТИКА ЗА ").append(period.toUpperCase()).append("\n\n");

        // Заявки
        @SuppressWarnings("unchecked")
        Map<String, Object> applications = (Map<String, Object>) stats.get("applications");
        result.append("📋 ЗАЯВКИ:\n");
        result.append("• Всего: ").append(applications.get("total")).append("\n");
        result.append("• Завершено: ").append(applications.get("completed")).append("\n");
        result.append("• Общая сумма: ").append(formatRubAmount((BigDecimal) applications.get("totalAmount"))).append("\n");
        result.append("• Комиссия: ").append(formatRubAmount((BigDecimal) applications.get("totalCommission"))).append("\n\n");

        // Пользователи
        @SuppressWarnings("unchecked")
        Map<String, Object> users = (Map<String, Object>) stats.get("users");
        result.append("👥 ПОЛЬЗОВАТЕЛИ:\n");
        result.append("• Всего: ").append(users.get("total")).append("\n");
        result.append("• Новых: ").append(users.get("new")).append("\n");
        result.append("• Активных: ").append(users.get("active")).append("\n");
        result.append("• С реф. кодом: ").append(users.get("withReferralCode")).append("\n\n");

        // Рефералы
        @SuppressWarnings("unchecked")
        Map<String, Object> referrals = (Map<String, Object>) stats.get("referrals");
        result.append("👨‍👩‍👧‍👦 РЕФЕРАЛЫ:\n");
        result.append("• Отношений: ").append(referrals.get("totalRelationships")).append("\n");
        result.append("• Бонусов начислено: ").append(referrals.get("bonusEvents")).append("\n");
        result.append("• Сумма бонусов: ").append(formatRubAmount((BigDecimal) referrals.get("totalBonusAmount"))).append("\n\n");

        // Финансы
        @SuppressWarnings("unchecked")
        Map<String, Object> finance = (Map<String, Object>) stats.get("finance");
        result.append("💰 ФИНАНСЫ:\n");
        result.append("• Оборот: ").append(formatRubAmount((BigDecimal) finance.get("totalTurnover"))).append("\n");
        result.append("• Комиссия: ").append(formatRubAmount((BigDecimal) finance.get("totalCommission"))).append("\n");

        return result.toString();
    }

    /**
     * Форматирование статистики по дням
     */
    public String formatDailyStatistics(Map<String, Object> stats, int days) {
        StringBuilder result = new StringBuilder();
        result.append("📅 ДНЕВНАЯ СТАТИСТИКА ЗА ").append(days).append(" ДНЕЙ\n\n");

        // Статистика заявок по дням
        @SuppressWarnings("unchecked")
        Map<String, Object> applications = (Map<String, Object>) stats.get("applications");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> dailyApps = (Map<String, Map<String, Object>>) applications.get("daily");

        if (!dailyApps.isEmpty()) {
            result.append("📋 ЗАЯВКИ ПО ДНЯМ:\n");
            dailyApps.forEach((date, dayStat) -> {
                result.append("• ").append(date).append(": ")
                        .append(dayStat.get("total")).append(" (")
                        .append(dayStat.get("completed")).append(" ✓) - ")
                        .append(formatRubAmount((BigDecimal) dayStat.get("amount"))).append("\n");
            });
            result.append("\n");
        }

        // Новые пользователи по дням
        @SuppressWarnings("unchecked")
        Map<String, Object> users = (Map<String, Object>) stats.get("users");
        @SuppressWarnings("unchecked")
        Map<String, Long> dailyNewUsers = (Map<String, Long>) users.get("dailyNew");

        if (!dailyNewUsers.isEmpty()) {
            result.append("👥 НОВЫЕ ПОЛЬЗОВАТЕЛИ ПО ДНЯМ:\n");
            dailyNewUsers.forEach((date, count) ->
                    result.append("• ").append(date).append(": ").append(count).append(" чел.\n"));
            result.append("\n");
        }

        // Реферальные бонусы по дням
        @SuppressWarnings("unchecked")
        Map<String, Object> referrals = (Map<String, Object>) stats.get("referrals");
        @SuppressWarnings("unchecked")
        Map<String, BigDecimal> dailyBonus = (Map<String, BigDecimal>) referrals.get("dailyBonus");

        if (!dailyBonus.isEmpty()) {
            result.append("💰 РЕФЕРАЛЬНЫЕ БОНУСЫ ПО ДНЯМ:\n");
            dailyBonus.forEach((date, amount) ->
                    result.append("• ").append(date).append(": ").append(formatRubAmount(amount)).append("\n"));
        }

        return result.toString();
    }

    /**
     * Форматирование топ рефереров
     */
    public String formatTopReferrers(Map<String, Object> stats) {
        StringBuilder result = new StringBuilder();
        result.append("🏆 ТОП РЕФЕРЕРОВ\n\n");

        @SuppressWarnings("unchecked")
        Map<String, Object> referrals = (Map<String, Object>) stats.get("referrals");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topReferrers = (List<Map<String, Object>>) referrals.get("topReferrers");

        if (topReferrers.isEmpty()) {
            result.append("Рефереров пока нет");
        } else {
            for (int i = 0; i < topReferrers.size(); i++) {
                Map<String, Object> referrer = topReferrers.get(i);
                result.append(i + 1).append(". ")
                        .append(referrer.get("username")).append(" - ")
                        .append(referrer.get("referrals")).append(" рефералов\n");
            }
        }

        return result.toString();
    }

    private String formatRubAmount(BigDecimal amount) {
        if (amount == null) return "0 ₽";
        return String.format("%.2f ₽", amount);
    }
}
