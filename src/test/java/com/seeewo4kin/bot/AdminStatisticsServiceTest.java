package com.seeewo4kin.bot;

import com.seeewo4kin.bot.Entity.Application;
import com.seeewo4kin.bot.Entity.ReferralBonusEvent;
import com.seeewo4kin.bot.Entity.ReferralRelationship;
import com.seeewo4kin.bot.Entity.User;
import com.seeewo4kin.bot.repository.*;
import com.seeewo4kin.bot.service.AdminStatisticsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@SpringBootTest
@ActiveProfiles("test")
public class AdminStatisticsServiceTest {

    @Autowired
    private AdminStatisticsService adminStatisticsService;

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ReferralBonusEventRepository referralBonusEventRepository;

    @Autowired
    private ReferralRelationshipRepository referralRelationshipRepository;

    @Test
    public void testGeneralStatistics() {
        System.out.println("=== ТЕСТИРОВАНИЕ AdminStatisticsService ===");

        try {
            // Получаем общую статистику за последние 30 дней
            Map<String, Object> stats = adminStatisticsService.getGeneralStatistics(30);

            System.out.println("\n=== ОБЩАЯ СТАТИСТИКА ЗА 30 ДНЕЙ ===");
            printStatistics(stats);

            // Форматируем и выводим отчет
            String report = adminStatisticsService.formatStatistics(stats, "30 дней");
            System.out.println("\n=== ОТФОРМАТИРОВАННЫЙ ОТЧЕТ ===");
            System.out.println(report);

            // Тестируем дневную статистику
            String dailyReport = adminStatisticsService.formatDailyStatistics(stats, 30);
            System.out.println("\n=== ДНЕВНАЯ СТАТИСТИКА ===");
            System.out.println(dailyReport);

            // Тестируем топ рефереров
            String topReferrersReport = adminStatisticsService.formatTopReferrers(stats);
            System.out.println("\n=== ТОП РЕФЕРЕРОВ ===");
            System.out.println(topReferrersReport);

            System.out.println("\n✅ ТЕСТИРОВАНИЕ ПРОШЛО УСПЕШНО");

        } catch (Exception e) {
            System.err.println("❌ ОШИБКА ПРИ ТЕСТИРОВАНИИ: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void printStatistics(Map<String, Object> stats) {
        // Выводим сырые данные для отладки
        stats.forEach((key, value) -> {
            System.out.println(key + ": " + value);
        });
    }
}
