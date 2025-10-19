package com.seeewo4kin.bot.Bot;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MyBot extends TelegramLongPollingBot {
    private final String botUsername;
    private final MessageProcessor messageProcessor;

    public MyBot(@Value("${telegram.bot.token}") String botToken,
                 @Value("${telegram.bot.username}") String botUsername,
                 MessageProcessor messageProcessor) {
        super(botToken);
        this.botUsername = botUsername;
        this.messageProcessor = messageProcessor;
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public void onUpdateReceived(Update update) {
        messageProcessor.processUpdate(update, this);
    }

    public int sendMessage(Long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText(text);

        try {
            Message sentMessage = execute(message);
            return sentMessage.getMessageId();
        } catch (TelegramApiException e) {
            e.printStackTrace();
            return -1;
        }
    }


    public int sendMessageWithKeyboard(Long chatId, String text, InlineKeyboardMarkup keyboard) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText(text);
        message.setReplyMarkup(keyboard);

        try {
            Message sentMessage = execute(message);
            return sentMessage.getMessageId();
        } catch (TelegramApiException e) {
            e.printStackTrace();
            return -1;
        }
    }

    public int sendMessageWithKeyboard(Long chatId, String text, ReplyKeyboardMarkup keyboard) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText(text);
        message.setReplyMarkup(keyboard);

        try {
            Message sentMessage = execute(message);
            return sentMessage.getMessageId();
        } catch (TelegramApiException e) {
            e.printStackTrace();
            return -1;
        }
    }

    public void deleteMessage(Long chatId, Integer messageId) {
        if (messageId == null || messageId == -1) {
            return;
        }

        DeleteMessage deleteMessage = new DeleteMessage();
        deleteMessage.setChatId(chatId.toString());
        deleteMessage.setMessageId(messageId);

        try {
            execute(deleteMessage);
        } catch (TelegramApiException e) {
            // Игнорируем ошибки удаления (сообщение может быть уже удалено или недоступно)
        }
    }

}