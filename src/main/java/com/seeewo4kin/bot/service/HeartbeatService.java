package com.seeewo4kin.bot.service;

import com.seeewo4kin.bot.Bot.MyBot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class HeartbeatService {

    private final MyBot bot;
    private final Long heartbeatChatId;

    public HeartbeatService(@Lazy MyBot bot,
                            @Value("${bot.heartbeat.chatId:0}") Long heartbeatChatId) {
        this.bot = bot;
        this.heartbeatChatId = heartbeatChatId;
    }

    // fixedRate = 600000 (10 минут * 60 сек * 1000 мс)
    @Scheduled(fixedRate = 600000)
    public void sendHeartbeat() {
        if (heartbeatChatId == null || heartbeatChatId == 0) {
            return;
        }

        try {
            String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            bot.sendMessage(heartbeatChatId, "🟢 Бот работает. " + time);
        } catch (Exception e) {
            System.err.println("Не удалось отправить 'heartbeat' пинг на ID " + heartbeatChatId + ": " + e.getMessage());
        }
    }
}