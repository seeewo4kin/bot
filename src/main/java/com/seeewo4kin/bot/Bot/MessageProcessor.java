package com.seeewo4kin.bot.Bot;

import com.seeewo4kin.bot.Entity.Application;
import com.seeewo4kin.bot.Entity.User;
import com.seeewo4kin.bot.Enums.UserState;
import com.seeewo4kin.bot.Enums.ValueType;
import com.seeewo4kin.bot.ValueGettr.CryptoPriceService;
import com.seeewo4kin.bot.ValueGettr.ExchangeRateCache;
import com.seeewo4kin.bot.service.ApplicationService;
import com.seeewo4kin.bot.service.UserService;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MessageProcessor {
    private final UserService userService;
    private final ApplicationService applicationService;
    private final CryptoPriceService cryptoPriceService;
    private final ExchangeRateCache exchangeRateCache;

    private final Map<Long, Application> temporaryApplications = new HashMap<>();
    private final Map<Long, Map<String, Object>> calculatorData = new HashMap<>();

    public MessageProcessor(UserService userService,
                            ApplicationService applicationService,
                            CryptoPriceService cryptoPriceService,
                            ExchangeRateCache exchangeRateCache) {

        this.userService = userService;
        this.applicationService = applicationService;
        this.cryptoPriceService = cryptoPriceService;
        this.exchangeRateCache = exchangeRateCache;
    }

    public void processUpdate(Update update, MyBot bot) {
        // Обработка callback'ов от кнопок
        if (update.hasCallbackQuery()) {
            processCallback(update, bot);
            return;
        }

        // Обработка текстовых сообщений
        if (update.hasMessage() && update.getMessage().hasText()) {
            processTextMessage(update, bot);
        }
    }

    private void processTextMessage(Update update, MyBot bot) {
        String text = update.getMessage().getText();
        Long chatId = update.getMessage().getChatId();
        Long telegramId = update.getMessage().getFrom().getId();

        User user = userService.findByTelegramId(telegramId);

        // Проверка на отмену в любом состоянии
        if (text.equalsIgnoreCase("отмена") || text.equalsIgnoreCase("cancel") || text.equals("/cancel")) {
            processApplicationCancellation(chatId, user, bot);
            return;
        }

        // Если пользователь не найден или в начальном состоянии - обрабатываем команды
        if (user == null || user.getState() == UserState.START) {
            processCommand(update, bot);
        } else {
            // Если пользователь в процессе создания заявки - обрабатываем состояние
            processUserState(update, user, bot);
        }
    }

    private void processCommand(Update update, MyBot bot) {
        String text = update.getMessage().getText();
        Long chatId = update.getMessage().getChatId();

        switch (text) {
            case "/start":
                processStartCommand(update, bot);
                break;
            case "/my_applications":
                processMyApplicationsCommand(update, bot);
                break;
            case "/new_application":
                processNewApplicationCommand(update, bot);
                break;
            case "/queue":
                processQueueCommand(update, bot);
                break;
            case "/rates":
                processRatesCommand(update, bot);
                break;
            default:
                bot.sendMessage(chatId, "❌ Неизвестная команда. Используйте /start для списка команд.");
        }
    }

    private void processUserState(Update update, User user, MyBot bot) {
        Long chatId = update.getMessage().getChatId();
        String text = update.getMessage().getText();

        Application application = getOrCreateApplication(user);

        switch (user.getState()) {
            case ENTERING_GET_VALUE:
                processEnteringGetValue(chatId, user, application, text, bot);
                break;
            case ENTERING_GIVE_VALUE:
                processEnteringGiveValue(chatId, user, application, text, bot);
                break;
        }
    }

    private void processCallback(Update update, MyBot bot) {
        String callbackData = update.getCallbackQuery().getData();
        Long chatId = update.getCallbackQuery().getMessage().getChatId();
        Long telegramId = update.getCallbackQuery().getFrom().getId();

        User user = userService.findByTelegramId(telegramId);
        if (user == null) {
            bot.sendMessage(chatId, "❌ Пользователь не найден");
            return;
        }

        Application application = getOrCreateApplication(user);

        if (callbackData.startsWith("get_type_")) {
            processGetTypeSelection(chatId, user, application, callbackData, bot);
        } else if (callbackData.startsWith("give_type_")) {
            processGiveTypeSelection(chatId, user, application, callbackData, bot);
        } else if (callbackData.equals("confirm_application")) {
            processApplicationConfirmation(chatId, user, application, bot);
        } else if (callbackData.equals("cancel_application")) {
            processApplicationCancellation(chatId, user, bot);
        } else if (callbackData.equals("use_calculated_value")) {
            processUseCalculatedValue(chatId, user, application, bot);
        }
    }

    private void processStartCommand(Update update, MyBot bot) {
        Long chatId = update.getMessage().getChatId();
        org.telegram.telegrambots.meta.api.objects.User telegramUser = update.getMessage().getFrom();

        User user = userService.findOrCreateUser(telegramUser);
        user.setState(UserState.START);
        userService.update(user);

        String welcomeText = String.format("""
            🎉 Добро пожаловать, %s!
            
            📋 Доступные команды:
            /my_applications - мои заявки
            /new_application - создать заявку
            /rates - курсы валют
            /queue - статистика заявок
            
            🔑 Ваш ID: %d
            """,
                user.getUsername() != null ? user.getUsername() : "пользователь",
                user.getId()
        );

        bot.sendMessage(chatId, welcomeText);
    }

    private void processMyApplicationsCommand(Update update, MyBot bot) {
        Long chatId = update.getMessage().getChatId();
        Long telegramId = update.getMessage().getFrom().getId();

        User user = userService.findByTelegramId(telegramId);
        if (user == null) {
            bot.sendMessage(chatId, "❌ Сначала зарегистрируйтесь с помощью /start");
            return;
        }

        List<Application> applications = applicationService.findByUser(user.getId());

        if (applications.isEmpty()) {
            bot.sendMessage(chatId, "📭 У вас пока нет заявок.\nСоздайте первую с помощью /new_application");
            return;
        }

        StringBuilder response = new StringBuilder("📋 Ваши заявки:\n\n");

        for (int i = 0; i < applications.size(); i++) {
            Application app = applications.get(i);
            response.append(String.format("""
                🆔 Заявка #%d
                💰 Получаю: %.2f %s
                💸 Отдаю: %.2f %s
                📅 Создана: %s
                --------------------
                """,
                    app.getId(),
                    app.getUserValueGetValue(),
                    app.getUserValueGetType(),
                    app.getUserValueGiveValue(),
                    app.getUserValueGiveType(),
                    app.getCreatedAt().toLocalDate()
            ));
        }

        bot.sendMessage(chatId, response.toString());
    }

    private void processNewApplicationCommand(Update update, MyBot bot) {
        Long chatId = update.getMessage().getChatId();
        Long telegramId = update.getMessage().getFrom().getId();

        User user = userService.findByTelegramId(telegramId);
        if (user == null) {
            bot.sendMessage(chatId, "❌ Сначала зарегистрируйтесь с помощью /start");
            return;
        }

        // Создаем временную заявку
        Application application = getOrCreateApplication(user);

        // Начинаем процесс создания заявки
        user.setState(UserState.CHOOSING_GET_TYPE);
        userService.update(user);

        InlineKeyboardMarkup keyboard = createCurrencyKeyboardWithCancel("get_type");
        bot.sendMessageWithKeyboard(chatId,
                "📝 Создание новой заявки!\n\n" +
                        "💎 Выберите валюту/тип, которую хотите ПОЛУЧИТЬ:",
                keyboard);
    }

    private void processQueueCommand(Update update, MyBot bot) {
        Long chatId = update.getMessage().getChatId();

        List<Application> allApplications = applicationService.findAll();
        int queueSize = allApplications.size();

        String message = String.format("""
            📊 Статистика заявок:
            
            📈 Всего заявок в системе: %d
            👥 Уникальных пользователей: %d
            
            💡 Создайте заявку: /new_application
            """, queueSize, getUniqueUsersCount(allApplications));

        bot.sendMessage(chatId, message);
    }

    private void processEnteringGetValue(Long chatId, User user, Application application, String text, MyBot bot) {
        try {
            double value = Double.parseDouble(text);
            application.setUserValueGetValue(value);
            user.setState(UserState.CHOOSING_GIVE_TYPE);
            userService.update(user);

            InlineKeyboardMarkup keyboard = createCurrencyKeyboardWithCancel("give_type");
            bot.sendMessageWithKeyboard(chatId, "💎 Выберите валюту/тип, которую хотите ОТДАТЬ:", keyboard);
        } catch (NumberFormatException e) {
            bot.sendMessage(chatId, "❌ Пожалуйста, введите корректное число:");
        }
    }

    private void processEnteringGiveValue(Long chatId, User user, Application application, String text, MyBot bot) {
        try {
            double value = Double.parseDouble(text);
            application.setUserValueGiveValue(value);
            user.setState(UserState.CONFIRMING_APPLICATION);
            userService.update(user);

            String confirmationText = String.format("""
                ✅ Заявка готова к созданию!
                
                💰 Получаю: %.2f %s
                💸 Отдаю: %.2f %s
                
                Подтверждаете создание заявки?
                """,
                    application.getUserValueGetValue(),
                    application.getUserValueGetType().getDisplayName(),
                    application.getUserValueGiveValue(),
                    application.getUserValueGiveType().getDisplayName()
            );

            InlineKeyboardMarkup keyboard = createConfirmationKeyboard();
            bot.sendMessageWithKeyboard(chatId, confirmationText, keyboard);
        } catch (NumberFormatException e) {
            bot.sendMessage(chatId, "❌ Пожалуйста, введите корректное число:");
        }
    }

    private void processGetTypeSelection(Long chatId, User user, Application application, String callbackData, MyBot bot) {
        String typeName = callbackData.replace("get_type_", "");
        try {
            ValueType valueType = ValueType.valueOf(typeName);
            application.setUserValueGetType(valueType);
            user.setState(UserState.ENTERING_GET_VALUE);
            userService.update(user);

            bot.sendMessage(chatId, "💎 Вы выбрали: " + valueType.getDisplayName() +
                    "\n\nВведите сумму/количество, которое хотите ПОЛУЧИТЬ:");
        } catch (IllegalArgumentException e) {
            bot.sendMessage(chatId, "❌ Ошибка выбора типа");
        }
    }

    private void processGiveTypeSelection(Long chatId, User user, Application application, String callbackData, MyBot bot) {
        String typeName = callbackData.replace("give_type_", "");
        try {
            ValueType giveType = ValueType.valueOf(typeName);
            application.setUserValueGiveType(giveType);

            // Рассчитываем рекомендуемую сумму отдачи на основе курсов
            Double calculatedGiveValue = calculateExchange(
                    application.getUserValueGetType(),
                    application.getUserValueGetValue(),
                    giveType
            );

            // Сохраняем расчетное значение для использования
            Map<String, Object> userCalcData = calculatorData.computeIfAbsent(user.getId(), k -> new HashMap<>());
            userCalcData.put("calculatedGiveValue", calculatedGiveValue);

            String message = String.format("""
                💎 Вы выбрали: %s
                
                🧮 На основе текущих курсов:
                💰 Для получения %.2f %s
                💸 Рекомендуется отдать: %.2f %s
                
                Введите сумму, которую хотите ОТДАТЬ:
                (или используйте рекомендуемую)
                """,
                    giveType.getDisplayName(),
                    application.getUserValueGetValue(),
                    application.getUserValueGetType().getDisplayName(),
                    calculatedGiveValue,
                    giveType.getDisplayName()
            );

            InlineKeyboardMarkup keyboard = createCalculatorKeyboard();
            bot.sendMessageWithKeyboard(chatId, message, keyboard);

            user.setState(UserState.ENTERING_GIVE_VALUE);
            userService.update(user);

        } catch (IllegalArgumentException e) {
            bot.sendMessage(chatId, "❌ Ошибка выбора типа");
        }
    }

    private void processUseCalculatedValue(Long chatId, User user, Application application, MyBot bot) {
        Map<String, Object> userCalcData = calculatorData.get(user.getId());
        if (userCalcData != null) {
            Double calculatedValue = (Double) userCalcData.get("calculatedGiveValue");
            if (calculatedValue != null) {
                application.setUserValueGiveValue(calculatedValue);
                user.setState(UserState.CONFIRMING_APPLICATION);
                userService.update(user);

                String confirmationText = String.format("""
                    ✅ Заявка готова к созданию!
                    
                    💰 Получаю: %.2f %s
                    💸 Отдаю: %.2f %s
                    
                    Подтверждаете создание заявки?
                    """,
                        application.getUserValueGetValue(),
                        application.getUserValueGetType().getDisplayName(),
                        application.getUserValueGiveValue(),
                        application.getUserValueGiveType().getDisplayName()
                );

                InlineKeyboardMarkup keyboard = createConfirmationKeyboard();
                bot.sendMessageWithKeyboard(chatId, confirmationText, keyboard);
                return;
            }
        }

        bot.sendMessage(chatId, "❌ Не удалось использовать расчетное значение. Введите сумму вручную:");
    }

    private Double calculateExchange(ValueType getType, Double getValue, ValueType giveType) {
        try {
            Double exchangeRate = exchangeRateCache.getExchangeRate(getType, giveType);
            if (exchangeRate != null) {
                return getValue * exchangeRate;
            }
        } catch (Exception e) {
            // В случае ошибки используем fallback
        }

        // Fallback логика
        return getFallbackExchange(getType, getValue, giveType);
    }

    private Double getFallbackExchange(ValueType getType, Double getValue, ValueType giveType) {
        // Упрощенные курсы для fallback
        Map<ValueType, Double> usdRates = Map.of(
                ValueType.BTC, 45000.0,
                ValueType.ETH, 3000.0,
                ValueType.USDT, 1.0,
                ValueType.USDC, 1.0,
                ValueType.RUB, 0.011,
                ValueType.EUR, 1.08
        );

        Double getUsdRate = usdRates.get(getType);
        Double giveUsdRate = usdRates.get(giveType);

        if (getUsdRate != null && giveUsdRate != null && giveUsdRate != 0) {
            return (getValue * getUsdRate) / giveUsdRate;
        }

        return getValue; // Если не удалось рассчитать, возвращаем исходное значение
    }

    private void processApplicationConfirmation(Long chatId, User user, Application application, MyBot bot) {
        try {
            // Устанавливаем автоматическое название и описание
            application.setTitle("Обмен " + application.getUserValueGetType() + " на " + application.getUserValueGiveType());
            application.setDescription("Автоматически созданная заявка");

            applicationService.create(application);
            user.setState(UserState.START);
            userService.update(user);
            temporaryApplications.remove(user.getId());
            calculatorData.remove(user.getId());

            String successText = String.format("""
                ✅ Заявка успешно создана!
                
                💰 Получаю: %.2f %s
                💸 Отдаю: %.2f %s
                
                🆔 ID заявки: %d
                """,
                    application.getUserValueGetValue(),
                    application.getUserValueGetType().getDisplayName(),
                    application.getUserValueGiveValue(),
                    application.getUserValueGiveType().getDisplayName(),
                    application.getId()
            );

            bot.sendMessage(chatId, successText);
        } catch (Exception e) {
            bot.sendMessage(chatId, "❌ Ошибка при создании заявки");
        }
    }

    private void processApplicationCancellation(Long chatId, User user, MyBot bot) {
        user.setState(UserState.START);
        userService.update(user);
        temporaryApplications.remove(user.getId());
        calculatorData.remove(user.getId());

        String mainMenuText = """
            ❌ Действие отменено.
            
            Возврат в главное меню.
            Используйте /start для просмотра команд.
            """;
        bot.sendMessage(chatId, mainMenuText);
    }

    private Application getOrCreateApplication(User user) {
        return temporaryApplications.computeIfAbsent(user.getId(), k -> {
            Application app = new Application();
            app.setUser(user);
            return app;
        });
    }

    private long getUniqueUsersCount(List<Application> applications) {
        return applications.stream()
                .map(Application::getUser)
                .distinct()
                .count();
    }

    private void processRatesCommand(Update update, MyBot bot) {
        Long chatId = update.getMessage().getChatId();

        try {
            StringBuilder ratesMessage = new StringBuilder("💱 Текущие курсы криптовалют:\n\n");

            // BTC
            Map<String, Double> btcPrices = cryptoPriceService.getMultiplePrices("BTC");
            ratesMessage.append("₿ Bitcoin (BTC):\n");
            ratesMessage.append(String.format("  💵 %.2f USD\n", btcPrices.get("USD")));
            ratesMessage.append(String.format("  💶 %.2f EUR\n", btcPrices.get("EUR")));
            ratesMessage.append(String.format("  💷 %.0f RUB\n\n", btcPrices.get("RUB")));

            // ETH
            Map<String, Double> ethPrices = cryptoPriceService.getMultiplePrices("ETH");
            ratesMessage.append("Ξ Ethereum (ETH):\n");
            ratesMessage.append(String.format("  💵 %.2f USD\n", ethPrices.get("USD")));
            ratesMessage.append(String.format("  💶 %.2f EUR\n", ethPrices.get("EUR")));
            ratesMessage.append(String.format("  💷 %.0f RUB\n\n", ethPrices.get("RUB")));

            // USDT
            Map<String, Double> usdtPrices = cryptoPriceService.getMultiplePrices("USDT");
            ratesMessage.append("💵 Tether (USDT):\n");
            ratesMessage.append(String.format("  💵 %.4f USD\n", usdtPrices.get("USD")));
            ratesMessage.append(String.format("  💶 %.4f EUR\n", usdtPrices.get("EUR")));
            ratesMessage.append(String.format("  💷 %.4f RUB\n\n", usdtPrices.get("RUB")));

            ratesMessage.append("🕒 Курсы обновляются каждую минуту\n");
            ratesMessage.append("📝 Для создания заявки: /new_application");

            bot.sendMessage(chatId, ratesMessage.toString());
        } catch (Exception e) {
            bot.sendMessage(chatId, "❌ Не удалось получить текущие курсы. Попробуйте позже.");
        }
    }

    // Клавиатуры
    private InlineKeyboardMarkup createCurrencyKeyboardWithCancel(String callbackPrefix) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        ValueType[] types = ValueType.values();
        for (int i = 0; i < types.length; i += 2) {
            List<InlineKeyboardButton> row = new ArrayList<>();

            ValueType type1 = types[i];
            InlineKeyboardButton button1 = new InlineKeyboardButton();
            button1.setText(type1.getDisplayName());
            button1.setCallbackData(callbackPrefix + "_" + type1.name());
            row.add(button1);

            if (i + 1 < types.length) {
                ValueType type2 = types[i + 1];
                InlineKeyboardButton button2 = new InlineKeyboardButton();
                button2.setText(type2.getDisplayName());
                button2.setCallbackData(callbackPrefix + "_" + type2.name());
                row.add(button2);
            }

            rows.add(row);
        }

        // Добавляем кнопку отмены
        List<InlineKeyboardButton> cancelRow = new ArrayList<>();
        InlineKeyboardButton cancelButton = new InlineKeyboardButton();
        cancelButton.setText("❌ Отмена");
        cancelButton.setCallbackData("cancel_application");
        cancelRow.add(cancelButton);
        rows.add(cancelRow);

        markup.setKeyboard(rows);
        return markup;
    }

    private InlineKeyboardMarkup createConfirmationKeyboard() {
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton confirmButton = new InlineKeyboardButton();
        confirmButton.setText("✅ Подтвердить");
        confirmButton.setCallbackData("confirm_application");
        row1.add(confirmButton);

        InlineKeyboardButton cancelButton = new InlineKeyboardButton();
        cancelButton.setText("❌ Отменить");
        cancelButton.setCallbackData("cancel_application");
        row1.add(cancelButton);

        rows.add(row1);
        keyboard.setKeyboard(rows);
        return keyboard;
    }

    private InlineKeyboardMarkup createCalculatorKeyboard() {
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton useCalculatedButton = new InlineKeyboardButton();
        useCalculatedButton.setText("✅ Использовать расчет");
        useCalculatedButton.setCallbackData("use_calculated_value");
        row1.add(useCalculatedButton);

        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton cancelButton = new InlineKeyboardButton();
        cancelButton.setText("❌ Отмена");
        cancelButton.setCallbackData("cancel_application");
        row2.add(cancelButton);

        rows.add(row1);
        rows.add(row2);
        keyboard.setKeyboard(rows);
        return keyboard;
    }
}