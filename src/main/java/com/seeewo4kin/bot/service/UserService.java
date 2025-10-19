package com.seeewo4kin.bot.service;

import com.seeewo4kin.bot.Entity.User;

public interface UserService {
    User findOrCreateUser(org.telegram.telegrambots.meta.api.objects.User telegramUser);
    void create(User user);
    void update(User user);
    void delete(Long id);
    User find(Long id);
    User findByTelegramId(Long telegramId);
    int getActiveUsersCount(); // Добавляем новый метод
}