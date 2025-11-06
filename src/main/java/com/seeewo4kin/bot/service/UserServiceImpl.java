package com.seeewo4kin.bot.service;

import com.seeewo4kin.bot.Entity.User;
import com.seeewo4kin.bot.Enums.UserState;
import com.seeewo4kin.bot.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

    public UserServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public User findOrCreateUser(org.telegram.telegrambots.meta.api.objects.User telegramUser) {
        Long telegramId = telegramUser.getId();

        // Ищем пользователя в БД
        User user = userRepository.findByTelegramId(telegramId).orElse(null);

        if (user == null) {
            // Создаем нового пользователя
            user = new User();
            user.setTelegramId(telegramId);
            user.setUsername(telegramUser.getUserName());
            user.setFirstName(telegramUser.getFirstName());
            user.setLastName(telegramUser.getLastName());
            user.setState(UserState.START);

            userRepository.save(user);
        }

        return user;
    }
    @Override
    @Transactional(readOnly = true)
    public int getActiveUsersCount() {
        return (int) userRepository.count();
    }

    @Override
    public void create(User user) {
        userRepository.save(user);
    }

    @Override
    public void update(User user) {
        userRepository.save(user);
    }

    @Override
    public void delete(Long id) {
        userRepository.deleteById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public User find(Long id) {
        return userRepository.findById(id).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public User findByTelegramId(Long telegramId) {
        return userRepository.findByTelegramId(telegramId).orElse(null);
    }
    @Override
    public User findByUsername(String username) {
        if (username.startsWith("@")) {
            username = username.substring(1);
        }
        return userRepository.findByUsername(username).orElse(null);
    }
    public List<User> findAllActiveUsers() {
        // Возвращаем всех пользователей, которые прошли капчу (не в состоянии START)
        return userRepository.findByStateNot(UserState.START);
    }
}