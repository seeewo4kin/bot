package com.seeewo4kin.bot.service;

import com.seeewo4kin.bot.Entity.User;

import java.util.List;

public interface UserService {
    User findOrCreateUser(org.telegram.telegrambots.meta.api.objects.User telegramUser);
    boolean wasUserCreated(User user, org.telegram.telegrambots.meta.api.objects.User telegramUser);
    void create(User user);
    void update(User user);
    void delete(Long id);
    User find(Long id);
    User findByTelegramId(Long telegramId);
    int getActiveUsersCount();
    User findByUsername(String username);
    List<User> findAllActiveUsers();
    List<User> findRecentUsers();
    long getUsersWithReferralCodeCount();
}