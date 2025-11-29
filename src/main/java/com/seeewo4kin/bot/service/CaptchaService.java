package com.seeewo4kin.bot.service;

import com.seeewo4kin.bot.Enums.Emoji;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class CaptchaService {
    private final ConcurrentMap<Long, String> userCaptchaAnswers = new ConcurrentHashMap<>();

    public CaptchaChallenge generateCaptcha(Long userId) {
        // Выбираем случайный смайлик как правильный ответ
        Emoji correctEmoji = Emoji.getRandom();
        userCaptchaAnswers.put(userId, correctEmoji.getCode());

        // Создаем список опций (8 смайликов)
        List<String> options = new ArrayList<>();
        options.add(correctEmoji.getCode());

        // Добавляем 7 случайных неправильных вариантов
        while (options.size() < 8) {
            Emoji randomEmoji = Emoji.getRandom();
            if (!options.contains(randomEmoji.getCode())) {
                options.add(randomEmoji.getCode());
            }
        }

        // Перемешиваем опции
        Collections.shuffle(options);
        return new CaptchaChallenge(correctEmoji.getCode(), options);
    }

    public boolean verifyCaptcha(Long userId, String selectedEmoji) {
        String correctAnswer = userCaptchaAnswers.get(userId);
        if (correctAnswer != null && correctAnswer.equals(selectedEmoji)) {
            userCaptchaAnswers.remove(userId);
            return true;
        }
        return false;
    }

    public static class CaptchaChallenge {
        private final String correctEmoji;
        private final List<String> options;

        public CaptchaChallenge(String correctEmoji, List<String> options) {
            this.correctEmoji = correctEmoji;
            this.options = options;
        }

        public String getCorrectEmoji() {
            return correctEmoji;
        }

        public List<String> getOptions() {
            return options;
        }
    }
}