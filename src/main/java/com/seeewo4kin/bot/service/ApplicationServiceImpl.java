package com.seeewo4kin.bot.service;

import com.seeewo4kin.bot.Entity.Application;
import com.seeewo4kin.bot.Enums.ApplicationStatus;
import com.seeewo4kin.bot.repository.ApplicationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

@Service
@Transactional
public class ApplicationServiceImpl implements ApplicationService {

    private final ApplicationRepository applicationRepository;

    // Убираем UserServiceImpl из конструктора, если он не используется в методах
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
                Arrays.asList(ApplicationStatus.CLOSED));
    }
}