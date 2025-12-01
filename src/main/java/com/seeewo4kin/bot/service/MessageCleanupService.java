package com.seeewo4kin.bot.service;

import com.seeewo4kin.bot.Bot.MyBot;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class MessageCleanupService {

    private final ScheduledExecutorService scheduler;
    private final MyBot bot;

    public MessageCleanupService(MyBot bot) {
        this.bot = bot;
        this.scheduler = Executors.newScheduledThreadPool(2);
    }

    /**
     * Планирует удаление сообщения через указанное количество минут
     */
    public void scheduleMessageDeletion(Long chatId, Integer messageId, int delayMinutes) {
        scheduler.schedule(() -> {
            try {
                bot.deleteMessage(chatId, messageId);
                System.out.println("Автоматически удалено сообщение " + messageId + " в чате " + chatId);
            } catch (Exception e) {
                System.err.println("Не удалось автоматически удалить сообщение " + messageId +
                    " в чате " + chatId + ": " + e.getMessage());
            }
        }, delayMinutes, TimeUnit.MINUTES);
    }

    /**
     * Планирует удаление сообщения через 5 минут (по умолчанию для админских уведомлений)
     */
    public void scheduleAdminNotificationDeletion(Long chatId, Integer messageId) {
        scheduleMessageDeletion(chatId, messageId, 5);
    }

    /**
     * Закрывает планировщик при остановке приложения
     */
    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
