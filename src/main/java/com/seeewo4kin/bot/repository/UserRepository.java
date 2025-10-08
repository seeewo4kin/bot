package com.seeewo4kin.bot.repository;

import com.seeewo4kin.bot.Entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByTelegramId(Long telegramId);
    boolean existsByTelegramId(Long telegramId);
}