package com.seeewo4kin.bot.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class CaptchaService {
    private final ConcurrentMap<Long, String> userCaptchaAnswers = new ConcurrentHashMap<>();
    private final Random random = new Random();

    private final String[] emojis = {"😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇"};

    public CaptchaChallenge generateCaptcha(Long userId) {
        String correctEmoji = emojis[random.nextInt(emojis.length)];
        userCaptchaAnswers.put(userId, correctEmoji);

        List<String> options = new ArrayList<>();
        options.add(correctEmoji);

        // Добавляем случайные неправильные варианты
        while (options.size() < 4) {
            String randomEmoji = emojis[random.nextInt(emojis.length)];
            if (!options.contains(randomEmoji)) {
                options.add(randomEmoji);
            }
        }

        Collections.shuffle(options);
        return new CaptchaChallenge(correctEmoji, options);
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