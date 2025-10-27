package com.seeewo4kin.bot.service;

import com.seeewo4kin.bot.Bot.MyBot;
import com.seeewo4kin.bot.Entity.Application;
import com.seeewo4kin.bot.Enums.ApplicationStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ApplicationExpirationService {

    private final ApplicationService applicationService;
    private final MyBot bot;

    public ApplicationExpirationService(ApplicationService applicationService, MyBot bot) {
        this.applicationService = applicationService;
        this.bot = bot;
    }

    @Scheduled(fixedRate = 240000)
    @Transactional
    public void closeExpiredApplications() {
        List<Application> activeApplications = applicationService.findActiveApplications();

        for (Application application : activeApplications) {
            if (application.isExpired()) {
                application.setStatus(ApplicationStatus.CANCELLED);
                applicationService.update(application);

                // УДАЛЯЕМ сообщение с заявкой у пользователя
                if (application.getTelegramMessageId() != null) {
                    bot.deleteMessage(application.getUser().getTelegramId(), application.getTelegramMessageId());
                }

                // Отправляем уведомление пользователю
                String notificationMessage = String.format("""
                    ⏰ Заявка #%d автоматически отменена
                    
                    Причина: истекло время ожидания (40 минут)
                    ID заявки: %s
                    Сумма: %.2f ₽
                    
                    💎 Вы можете создать новую заявку в главном меню.
                    """,
                        application.getId(),
                        application.getUuid().substring(0, 8),
                        application.getCalculatedGiveValue()
                );

                bot.sendMessage(application.getUser().getTelegramId(), notificationMessage);

                System.out.println("Заявка #" + application.getId() + " автоматически закрыта по истечении времени");
            }
        }
    }
}
