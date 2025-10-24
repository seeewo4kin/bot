package com.seeewo4kin.bot.service;

import com.seeewo4kin.bot.Entity.Application;
import com.seeewo4kin.bot.Enums.ApplicationStatus;
import com.seeewo4kin.bot.repository.ApplicationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Service
@Transactional
public class ApplicationServiceImpl implements ApplicationService {

    private final ApplicationRepository applicationRepository;

    public ApplicationServiceImpl(ApplicationRepository applicationRepository) {
        this.applicationRepository = applicationRepository;
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
}