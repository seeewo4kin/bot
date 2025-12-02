package com.seeewo4kin.bot.Config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.HashSet;
import java.util.Set;

@Configuration
public class AdminConfig {

    private final Set<Long> adminUserIds = new HashSet<>();
    private final Long heartbeatChatId;

    public AdminConfig(@Value("${bot.admins:}") String adminIds,
                       @Value("${bot.heartbeat.chatId:8161846961}") Long heartbeatChatId) {
        this.heartbeatChatId = heartbeatChatId;
        if (adminIds != null && !adminIds.trim().isEmpty()) {
            String[] ids = adminIds.split(",");
            for (String id : ids) {
                try {
                    adminUserIds.add(Long.parseLong(id.trim()));
                } catch (NumberFormatException e) {
                    System.err.println("Invalid admin ID: " + id);
                }
            }
        }

        // Выводим информацию об админах при инициализации
        System.out.println("=== ADMIN CONFIGURATION ===");
        System.out.println("Configured admin IDs: " + adminUserIds);
        System.out.println("Total admins: " + adminUserIds.size());
        System.out.println("Heartbeat chat ID: " + heartbeatChatId);
        System.out.println("===========================");

        // Проверяем переменную окружения для bot username
        String botUsername = System.getenv("TELEGRAM_BOT_USERNAME");
        System.out.println("=== BOT CONFIGURATION ===");
        System.out.println("Bot username from env: " + (botUsername != null ? botUsername : "NOT SET"));
        System.out.println("===========================");
    }

    public boolean isAdmin(Long userId) {
        return adminUserIds.contains(userId);
    }

    public Set<Long> getAdminUserIds() {
        return new HashSet<>(adminUserIds);
    }

    public Long getHeartbeatChatId() {
        return heartbeatChatId;
    }

    /**
     * Возвращает строку с информацией об админах для отладки
     */
    public String getAdminInfo() {
        StringBuilder info = new StringBuilder();
        info.append("📊 Информация об админах:\n");
        info.append("👥 Количество админов: ").append(adminUserIds.size()).append("\n");
        info.append("🆔 ID админов: ");

        for (Long id : adminUserIds) {
            info.append(id).append(", ");
        }

        // Убираем последнюю запятую и пробел
        if (info.length() > 2) {
            info.setLength(info.length() - 2);
        }

        return info.toString();
    }
}