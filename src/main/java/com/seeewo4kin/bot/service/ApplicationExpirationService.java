package com.seeewo4kin.bot.service;

import com.seeewo4kin.bot.Entity.Application;
import com.seeewo4kin.bot.Enums.ApplicationStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ApplicationExpirationService {

    private final ApplicationService applicationService;

    public ApplicationExpirationService(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @Scheduled(fixedRate = 60000) // Проверка каждую минуту
    @Transactional
    public void closeExpiredApplications() {
        List<Application> activeApplications = applicationService.findActiveApplications();
        LocalDateTime now = LocalDateTime.now();

        for (Application application : activeApplications) {
            // Проверяем просроченность через метод isExpired()
            if (application.isExpired()) {
                application.setStatus(ApplicationStatus.CANCELLED);
                applicationService.update(application);

                // Логируем закрытие заявки
                System.out.println("Заявка #" + application.getId() + " автоматически закрыта по истечении времени");
            }
        }
    }
}