package com.seeewo4kin.bot.service;

import com.seeewo4kin.bot.Entity.Application;

import java.util.List;

// Убираем @Service с интерфейса или используем @Repository
public interface ApplicationService {

    void create(Application application);
    void update(Application application);
    void delete(Application application);
    Application find(Long id);
    List<Application> findAll();
    List<Application> findByUser(Long id);
}