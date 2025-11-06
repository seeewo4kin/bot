package com.seeewo4kin.bot.service;

import com.seeewo4kin.bot.Entity.User;
import com.seeewo4kin.bot.Enums.UserState;

import java.util.List;

public interface UserService {
    User findOrCreateUser(org.telegram.telegrambots.meta.api.objects.User telegramUser);
    void create(User user);
    void update(User user);
    void delete(Long id);
    User find(Long id);
    User findByTelegramId(Long telegramId);
    int getActiveUsersCount(); // Добавляем новый метод
    public User findByUsername(String username);
    public List<User> findAllActiveUsers();
}