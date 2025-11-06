package com.seeewo4kin.bot.Config;

import com.seeewo4kin.bot.Bot.MyBot;
import com.seeewo4kin.bot.service.DailyNotificationService;
import com.seeewo4kin.bot.service.UserService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

@Configuration
public class BotConfig {

    @Bean
    public TelegramBotsApi telegramBotsApi(MyBot myBot) throws TelegramApiException {
        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        botsApi.registerBot(myBot);
        return botsApi;
    }

    @Bean
    public DailyNotificationService dailyNotificationService(MyBot bot, UserService userService) {
        return new DailyNotificationService(bot, userService);
    }
}