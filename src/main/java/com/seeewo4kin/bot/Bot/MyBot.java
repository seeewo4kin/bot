package com.seeewo4kin.bot.Bot;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.CopyMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;

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
            // Не выводим полный стек трейс для обычных ошибок типа "chat not found"
            String errorMessage = e.getMessage();
            if (errorMessage != null && errorMessage.contains("chat not found")) {
                System.err.println("Chat not found for ID: " + chatId + " - user may not have started the bot");
            } else {
                System.err.println("Error sending message to " + chatId + ": " + errorMessage);
            }
            return -1;
        }
    }

    public int sendMessageWithInlineKeyboard(Long chatId, String text, InlineKeyboardMarkup keyboard) {
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

    public void editMessageText(Long chatId, Integer messageId, String text, InlineKeyboardMarkup keyboard) {
        EditMessageText message = new EditMessageText();
        message.setChatId(chatId.toString());
        message.setMessageId(messageId);
        message.setText(text);
        if (keyboard != null) {
            message.setReplyMarkup(keyboard);
        }

        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
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
            // Игнорируем ошибки удаления
        }
    }

    public void answerCallbackQuery(String callbackQueryId, String text) {
        AnswerCallbackQuery answer = new AnswerCallbackQuery();
        answer.setCallbackQueryId(callbackQueryId);
        answer.setText(text);
        answer.setShowAlert(false); // Всплывающее уведомление, а не alert

        try {
            execute(answer);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    public void answerCallbackQueryWithAlert(String callbackQueryId, String text) {
        AnswerCallbackQuery answer = new AnswerCallbackQuery();
        answer.setCallbackQueryId(callbackQueryId);
        answer.setText(text);
        answer.setShowAlert(true); // Alert окно

        try {
            execute(answer);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    public void copyMessage(Long chatId, Long fromChatId, Integer messageId) {
        CopyMessage copyMessage = new CopyMessage();
        copyMessage.setChatId(chatId.toString());
        copyMessage.setFromChatId(fromChatId.toString());
        copyMessage.setMessageId(messageId);

        try {
            execute(copyMessage);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    /**
     * Отправляет фото из файла на диске с подписью
     */
    public int sendPhoto(Long chatId, File photoFile, String caption) {
        try {
            SendPhoto sendPhoto = new SendPhoto();
            sendPhoto.setChatId(chatId.toString());
            sendPhoto.setPhoto(new InputFile(photoFile));

            if (caption != null && !caption.trim().isEmpty()) {
                sendPhoto.setCaption(caption);
            }

            Message sentMessage = execute(sendPhoto);
            return sentMessage.getMessageId();
        } catch (TelegramApiException e) {
            e.printStackTrace();
            return -1;
        }
    }

    /**
     * Отправляет фото по URL с подписью
     */
    public int sendPhotoFromUrl(Long chatId, String photoUrl, String caption) {
        try {
            SendPhoto sendPhoto = new SendPhoto();
            sendPhoto.setChatId(chatId.toString());
            sendPhoto.setPhoto(new InputFile(photoUrl));

            if (caption != null && !caption.trim().isEmpty()) {
                sendPhoto.setCaption(caption);
            }

            Message sentMessage = execute(sendPhoto);
            return sentMessage.getMessageId();
        } catch (TelegramApiException e) {
            e.printStackTrace();
            return -1;
        }
    }

}