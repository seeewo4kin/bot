package com.seeewo4kin.bot.service;

import com.seeewo4kin.bot.Entity.Application;
import com.seeewo4kin.bot.Enums.ApplicationStatus;

import java.time.LocalDateTime;
import java.util.List;

// Убираем @Service с интерфейса или используем @Repository
public interface ApplicationService {

    void create(Application application);

    void update(Application application);

    void delete(Application application);

    Application find(Long id);

    List<Application> findAll();

    List<Application> findByUser(Long id);
    List<Application> findApplicationsByDateRange(LocalDateTime startDate, LocalDateTime endDate);
    List<Application> findApplicationsByPeriod(String period); // today, week, month

    public List<Application> findActiveApplications();
    public List<Application> findByUserAndStatusIn(Long userId, List<ApplicationStatus> statuses);
    public List<Application> findCompletedApplicationsByUser(Long userId);
    public List<Application> findByAdminId(Long adminId);
    }