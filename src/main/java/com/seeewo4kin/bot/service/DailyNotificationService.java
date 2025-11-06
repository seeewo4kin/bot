package com.seeewo4kin.bot.service;

import com.seeewo4kin.bot.Bot.MyBot;
import com.seeewo4kin.bot.Entity.User;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class DailyNotificationService {
    private final MyBot bot;
    private final UserService userService;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    private static final String WARNING_MESSAGE = """
        ⚠️ Будьте бдительны!
        Не подвергайтесь провокациям мошенников, наш оператор первым не пишет✍️

        Актуальные контакты:
        Бот:🤖 @COSANOSTRA24_bot
        ☎️Оператор 24/7: @SUP_CN
        """;

    public DailyNotificationService(MyBot bot, UserService userService) {
        this.bot = bot;
        this.userService = userService;
        scheduleDailyNotification();
    }

    private void scheduleDailyNotification() {
        // Вычисляем время до следующего 12:00
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextNotification = now.withHour(12).withMinute(0).withSecond(0);
        if (now.isAfter(nextNotification)) {
            nextNotification = nextNotification.plusDays(1);
        }

        long initialDelay = Duration.between(now, nextNotification).toMillis();
        long period = TimeUnit.DAYS.toMillis(1); // 24 часа

        scheduler.scheduleAtFixedRate(this::sendDailyNotifications, initialDelay, period, TimeUnit.MILLISECONDS);
    }

    private void sendDailyNotifications() {
        try {
            List<User> activeUsers = userService.findAllActiveUsers();

            for (User user : activeUsers) {
                try {
                    bot.sendMessage(user.getTelegramId(), WARNING_MESSAGE);
                    // Пауза между отправками, чтобы не превысить лимиты Telegram
                    Thread.sleep(100);
                } catch (Exception e) {
                    System.err.println("Не удалось отправить уведомление пользователю " + user.getTelegramId() + ": " + e.getMessage());
                }
            }

            System.out.println("Ежедневные уведомления отправлены для " + activeUsers.size() + " пользователей");
        } catch (Exception e) {
            System.err.println("Ошибка при отправке ежедневных уведомлений: " + e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
    }
}
