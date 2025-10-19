package com.seeewo4kin.bot.service;

import com.seeewo4kin.bot.Entity.Application;
import com.seeewo4kin.bot.Enums.ApplicationStatus;

import java.util.List;

// Убираем @Service с интерфейса или используем @Repository
public interface ApplicationService {

    void create(Application application);

    void update(Application application);

    void delete(Application application);

    Application find(Long id);

    List<Application> findAll();

    List<Application> findByUser(Long id);

    public List<Application> findActiveApplications();
    public List<Application> findByUserAndStatusIn(Long userId, List<ApplicationStatus> statuses);
    public List<Application> findCompletedApplicationsByUser(Long userId);
    }