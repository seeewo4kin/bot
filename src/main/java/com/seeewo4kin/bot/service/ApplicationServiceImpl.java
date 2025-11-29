package com.seeewo4kin.bot.service;

import com.seeewo4kin.bot.Config.AdminConfig;
import com.seeewo4kin.bot.Entity.Application;
import com.seeewo4kin.bot.Entity.User;
import com.seeewo4kin.bot.Enums.ApplicationStatus;
import com.seeewo4kin.bot.repository.ApplicationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Service
@Transactional
public class ApplicationServiceImpl implements ApplicationService {

    private final ApplicationRepository applicationRepository;
    private final AdminConfig adminConfig;

    public ApplicationServiceImpl(ApplicationRepository applicationRepository, AdminConfig adminConfig) {
        this.applicationRepository = applicationRepository;
        this.adminConfig = adminConfig;
    }

    @Override
    public void create(Application application) {
        applicationRepository.save(application);
    }

    @Override
    public void update(Application application) {
        applicationRepository.save(application);
    }

    @Override
    public void delete(Application application) {
        applicationRepository.delete(application);
    }

    @Override
    @Transactional(readOnly = true)
    public Application find(Long id) {
        return applicationRepository.findById(id).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Application> findAll() {
        return applicationRepository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Application> findByUser(Long id) {
        return applicationRepository.findByUserId(id);
    }

    @Override
    public List<Application> findActiveApplications() {
        return applicationRepository.findByStatusIn(
                Arrays.asList(ApplicationStatus.FREE, ApplicationStatus.IN_WORK)
        );
    }

    @Override
    public List<Application> findByUserAndStatusIn(Long userId, List<ApplicationStatus> statuses) {
        return applicationRepository.findByUserIdAndStatusIn(userId, statuses);
    }

    @Override
    public List<Application> findCompletedApplicationsByUser(Long userId) {
        return applicationRepository.findByUserIdAndStatusIn(userId,
                Arrays.asList(ApplicationStatus.COMPLETED));
    }
    @Override
    public List<Application> findApplicationsByDateRange(LocalDateTime startDate, LocalDateTime endDate) {
        return applicationRepository.findByCreatedAtBetween(startDate, endDate);
    }

    @Override
    public List<Application> findApplicationsByPeriod(String period) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startDate;

        switch (period.toLowerCase()) {
            case "today":
                startDate = now.withHour(0).withMinute(0).withSecond(0).withNano(0);
                break;
            case "week":
                startDate = now.minusDays(7);
                break;
            case "month":
                startDate = now.minusMonths(1);
                break;
            default:
                startDate = now.minusDays(1); // по умолчанию за сегодня
        }

        return findApplicationsByDateRange(startDate, now);
    }
    public List<Application> findByAdminId(Long adminId) {
        // Реализуйте этот метод в вашем ApplicationService
        // Он должен возвращать все заявки, где admin_id = adminId
        return applicationRepository.findByAdminId(adminId);
    }

    public List<Application> findExpiredApplications() {
        return applicationRepository.findExpiredApplications();
    }

    public String getNewApplicationNotificationMessage(Application application) {
        User user = application.getUser();
        String username = user.getUsername() != null ? "@" + user.getUsername() : "без username";
        String firstName = user.getFirstName() != null ? user.getFirstName() : "";
        String lastName = user.getLastName() != null ? " " + user.getLastName() : "";

        String priority = application.getIsVip() ? "👑 VIP" : "🔹 Обычный";
        String status = application.getStatus() != null ? application.getStatus().toString() : "Неизвестен";

        return String.format("""
            📋 Новая заявка! #%d

            👤 Пользователь: %s%s (ID: %d)
            📝 Username: %s
            💰 Получает: %.8f %s
            💸 Отдает: %.2f %s
            ⭐ Приоритет: %s
            📊 Статус: %s
            🕒 Время: %s
            """,
            application.getId(),
            firstName,
            lastName,
            user.getTelegramId(),
            username,
            application.getCalculatedGetValue(),
            application.getUserValueGetType(),
            application.getCalculatedGiveValue(),
            application.getUserValueGiveType(),
            priority,
            status,
            application.getCreatedAt() != null ?
                application.getCreatedAt().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) :
                java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
        );
    }

}