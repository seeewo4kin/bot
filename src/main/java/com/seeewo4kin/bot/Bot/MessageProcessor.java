package com.seeewo4kin.bot.Bot;

import com.seeewo4kin.bot.Config.AdminConfig;
import com.seeewo4kin.bot.Entity.Application;
import com.seeewo4kin.bot.Entity.Coupon;
import com.seeewo4kin.bot.Entity.ReferralCode;
import com.seeewo4kin.bot.Entity.User;
import com.seeewo4kin.bot.Enums.ApplicationStatus;
import com.seeewo4kin.bot.Enums.UserState;
import com.seeewo4kin.bot.Enums.ValueType;
import com.seeewo4kin.bot.ValueGettr.CryptoPriceService;
import com.seeewo4kin.bot.ValueGettr.ExchangeRateCache;
import com.seeewo4kin.bot.service.*;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class MessageProcessor {
    private final UserService userService;
    private final ApplicationService applicationService;
    private final CryptoPriceService cryptoPriceService;
    private final ExchangeRateCache exchangeRateCache;
    private final CaptchaService captchaService;
    private final CouponService couponService;
    private final AdminConfig adminConfig;
    private final CommissionService commissionService;
    private final ReferralService referralService;

    private final Map<Long, Application> temporaryApplications = new ConcurrentHashMap<>();
    private final Map<Long, String> currentOperation = new ConcurrentHashMap<>();
    private final Map<Long, Integer> lastMessageId = new ConcurrentHashMap<>();
    private final Map<Long, Long> selectedApplication = new ConcurrentHashMap<>(); // Для админов

    public MessageProcessor(UserService userService,
                            ApplicationService applicationService,
                            CryptoPriceService cryptoPriceService,
                            ExchangeRateCache exchangeRateCache,
                            CaptchaService captchaService,
                            CouponService couponService,
                            AdminConfig adminConfig,
                            CommissionService commissionService,
                            ReferralService referralService) { // Добавляем в конструктор
        this.userService = userService;
        this.applicationService = applicationService;
        this.cryptoPriceService = cryptoPriceService;
        this.exchangeRateCache = exchangeRateCache;
        this.captchaService = captchaService;
        this.couponService = couponService;
        this.adminConfig = adminConfig;
        this.commissionService = commissionService;
        this.referralService = referralService;
    }

    public void processUpdate(Update update, MyBot bot) {
        // Удаляем предыдущее сообщение бота
        if (update.hasMessage()) {
            Long chatId = update.getMessage().getChatId();
            deletePreviousBotMessage(chatId, bot);
        } else if (update.hasCallbackQuery()) {
            Long chatId = update.getCallbackQuery().getMessage().getChatId();
            deletePreviousBotMessage(chatId, bot);
        }

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

    private void deletePreviousBotMessage(Long chatId, MyBot bot) {
        Integer previousMessageId = lastMessageId.get(chatId);
        if (previousMessageId != null) {
            bot.deleteMessage(chatId, previousMessageId);
        }
    }

    private void processTextMessage(Update update, MyBot bot) {
        String text = update.getMessage().getText();
        Long chatId = update.getMessage().getChatId();
        Long telegramId = update.getMessage().getFrom().getId();

        // Удаляем сообщение пользователя
        bot.deleteMessage(chatId, update.getMessage().getMessageId());

        User user = userService.findByTelegramId(telegramId);

        // Проверка на отмену в любом состоянии
        if (text.equalsIgnoreCase("отмена") || text.equalsIgnoreCase("cancel") ||
                text.equals("/cancel") || text.equals("Главное меню")) {
            processMainMenu(chatId, user, bot);
            return;
        }

        if (user == null || user.getState() == UserState.START) {
            processCommand(update, bot);
        } else {
            processUserState(update, user, bot);
        }
    }

    private void processCommand(Update update, MyBot bot) {
        String text = update.getMessage().getText();
        Long chatId = update.getMessage().getChatId();
        Long telegramId = update.getMessage().getFrom().getId();

        User user = userService.findByTelegramId(telegramId);
        if (user == null) {
            user = userService.findOrCreateUser(update.getMessage().getFrom());
        }

        if ("/start".equals(text)) {
            processStartCommand(update, bot);
        } else {
            bot.sendMessageWithKeyboard(chatId, "❌ Используйте /start для начала", createMainMenuKeyboard(user));
        }
    }

    private void processUserState(Update update, User user, MyBot bot) {
        Long chatId = update.getMessage().getChatId();
        String text = update.getMessage().getText();

        switch (user.getState()) {
            case CAPTCHA_CHECK:
                break;
            case MAIN_MENU:
                processMainMenuCommand(chatId, user, text, bot);
                break;
            case BUY_MENU:
                processBuyMenu(chatId, user, text, bot);
                break;
            case SELL_MENU:
                processSellMenu(chatId, user, text, bot);
                break;
            case ENTERING_BUY_AMOUNT_RUB:
                processEnteringBuyAmountRub(chatId, user, text, bot);
                break;
            case ENTERING_BUY_AMOUNT_BTC:
                processEnteringBuyAmountBtc(chatId, user, text, bot);
                break;
            case ENTERING_SELL_AMOUNT:
                processEnteringSellAmount(chatId, user, text, bot);
                break;
            case CONFIRMING_APPLICATION:
                processConfirmingApplication(chatId, user, text, bot);
                break;
            case VIEWING_APPLICATIONS:
                processViewingApplications(chatId, user, bot);
                break;
            case VIEWING_COUPONS:
                processViewingCoupons(chatId, user, bot);
                break;
            case VIEWING_QUEUE:
                processViewingQueue(chatId, user, bot);
                break;
            case ADMIN_MAIN_MENU:
                processAdminMainMenu(chatId, user, text, bot);
                break;
            case ADMIN_VIEWING_ALL_APPLICATIONS:
                processAdminApplicationSelection(chatId, user, text, bot);
                break;
            case ADMIN_VIEWING_APPLICATION_DETAILS:
                processAdminApplicationActions(chatId, user, text, bot);
                break;

            // Реферальная система
            case REFERRAL_MENU:
                processReferralMenu(chatId, user, text, bot);
                break;
            case CREATING_REFERRAL_CODE:
                processCreatingReferralCode(chatId, user, text, bot);
                break;
            case ENTERING_REFERRAL_CODE:
                processEnteringReferralCode(chatId, user, text, bot);
                break;

            // Прочее
            case OTHER_MENU:
                processOtherMenu(chatId, user, text, bot);
                break;
        }
    }
    private void processAdminApplicationActions(Long chatId, User user, String text, MyBot bot) {
        Long applicationId = selectedApplication.get(user.getId());
        if (applicationId == null) {
            processAdminViewingAllApplications(chatId, user, bot);
            return;
        }

        Application application = applicationService.find(applicationId);
        if (application == null) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, "❌ Заявка не найдена", createAdminMainMenuKeyboard()));
            return;
        }

        ApplicationStatus previousStatus = application.getStatus();

        switch (text) {
            case "🟡 В работу":
                application.setStatus(ApplicationStatus.IN_WORK);
                break;
            case "🔵 Закрыть":
                application.setStatus(ApplicationStatus.CLOSED);
                // Обновляем статистику пользователя при закрытии заявки
                updateUserStatistics(application);
                // Начисляем реферальное вознаграждение
                referralService.processReferralReward(application);
                break;
            case "🔴 Отменить":
                application.setStatus(ApplicationStatus.CANCELLED);
                break;
            case "🟢 Свободна":
                application.setStatus(ApplicationStatus.FREE);
                break;
            case "📋 Все заявки":
                user.setState(UserState.ADMIN_VIEWING_ALL_APPLICATIONS);
                userService.update(user);
                processAdminViewingAllApplications(chatId, user, bot);
                return;
            case "🔙 Назад":
                user.setState(UserState.ADMIN_VIEWING_ALL_APPLICATIONS);
                userService.update(user);
                processAdminViewingAllApplications(chatId, user, bot);
                return;
            default:
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                        "❌ Пожалуйста, используйте кнопки", createAdminApplicationActionsKeyboard()));
                return;
        }

        applicationService.update(application);

        // Если заявка закрыта или отменена, возвращаем админа к списку заявок
        if (application.getStatus() == ApplicationStatus.CLOSED ||
                application.getStatus() == ApplicationStatus.CANCELLED) {

            String message = String.format("✅ Статус заявки #%d изменен на: %s",
                    applicationId, application.getStatus().getDisplayName());
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createBackKeyboard()));

            // Возвращаем к списку заявок
            user.setState(UserState.ADMIN_VIEWING_ALL_APPLICATIONS);
            userService.update(user);
            processAdminViewingAllApplications(chatId, user, bot);
        } else {
            String message = String.format("✅ Статус заявки #%d изменен на: %s",
                    applicationId, application.getStatus().getDisplayName());
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createAdminApplicationActionsKeyboard()));
        }
    }
    private void updateUserStatistics(Application application) {
        User user = application.getUser();

        if (application.getStatus() == ApplicationStatus.CLOSED) {
            if (application.getUserValueGetType() == ValueType.BTC) {
                // Покупка BTC
                user.setCompletedBuyApplications(user.getCompletedBuyApplications() + 1);
                user.setTotalBuyAmount(user.getTotalBuyAmount() + application.getCalculatedGiveValue());
            } else {
                // Продажа BTC
                user.setCompletedSellApplications(user.getCompletedSellApplications() + 1);
                user.setTotalSellAmount(user.getTotalSellAmount() + application.getCalculatedGetValue());
            }
            user.setTotalApplications(user.getTotalApplications() + 1);

            // Расчет комиссии (разница между суммой с комиссией и без)
            if (application.getUserValueGetType() == ValueType.BTC) {
                double amountWithoutCommission = application.getCalculatedGiveValue() /
                        (1 + commissionService.getCommissionPercent(application.getCalculatedGiveValue()) / 100);
                double commission = application.getCalculatedGiveValue() - amountWithoutCommission;
                user.setTotalCommissionPaid(user.getTotalCommissionPaid() + commission);
            }

            userService.update(user);
        }
    }


    private void processStartCommand(Update update, MyBot bot) {
        Long chatId = update.getMessage().getChatId();
        org.telegram.telegrambots.meta.api.objects.User telegramUser = update.getMessage().getFrom();

        User user = userService.findOrCreateUser(telegramUser);
        user.setState(UserState.CAPTCHA_CHECK);
        userService.update(user);

        showCaptcha(chatId, user, bot);
    }

    private void showCaptcha(Long chatId, User user, MyBot bot) {
        CaptchaService.CaptchaChallenge challenge = captchaService.generateCaptcha(user.getId());

        InlineKeyboardMarkup keyboard = createCaptchaKeyboard(challenge.getOptions());
        String message = "🔐 Для продолжения пройдите проверку:\n\nВыберите смайлик: \"" + challenge.getCorrectEmoji() + "\"";

        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, keyboard));
    }

    private void processCallback(Update update, MyBot bot) {
        String callbackData = update.getCallbackQuery().getData();
        Long chatId = update.getCallbackQuery().getMessage().getChatId();
        Long telegramId = update.getCallbackQuery().getFrom().getId();

        User user = userService.findByTelegramId(telegramId);
        if (user == null) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, "❌ Пользователь не найден", createMainMenuKeyboard(user)));
            return;
        }

        if (callbackData.startsWith("captcha_")) {
            processCaptchaSelection(chatId, user, callbackData, bot);
        }
    }

    private void processCaptchaSelection(Long chatId, User user, String callbackData, MyBot bot) {
        String selectedEmoji = callbackData.replace("captcha_", "");

        if (captchaService.verifyCaptcha(user.getId(), selectedEmoji)) {
            user.setState(UserState.MAIN_MENU);
            userService.update(user);
            showMainMenu(chatId, user, bot);
        } else {
            showCaptcha(chatId, user, bot);
        }
    }

    private void showMainMenu(Long chatId, User user, MyBot bot) {
        String message = "💎 Главное меню\n\nВыберите действие:";
        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createMainMenuKeyboard(user)));
    }

    private void processMainMenuCommand(Long chatId, User user, String text, MyBot bot) {
        switch (text) {
            case "💰 Купить":
                user.setState(UserState.BUY_MENU);
                userService.update(user);
                showBuyMenu(chatId, bot);
                break;
            case "💸 Продать":
                user.setState(UserState.SELL_MENU);
                userService.update(user);
                showSellMenu(chatId, bot);
                break;
            case "📋 Мои заявки":
                user.setState(UserState.VIEWING_APPLICATIONS);
                userService.update(user);
                processViewingApplications(chatId, user, bot);
                break;
            case "🎫 Мои купоны":
                user.setState(UserState.VIEWING_COUPONS);
                userService.update(user);
                processViewingCoupons(chatId, user, bot);
                break;
            case "📊 Очередь":
                user.setState(UserState.VIEWING_QUEUE);
                userService.update(user);
                processViewingQueue(chatId, user, bot);
                break;
            case "👨‍💼 Админ панель":
                if (adminConfig.isAdmin(user.getId())) {
                    user.setState(UserState.ADMIN_MAIN_MENU);
                    userService.update(user);
                    showAdminMainMenu(chatId, bot);
                } else {
                    lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, "❌ Доступ запрещен", createMainMenuKeyboard(user)));
                }
                break;
            case "⚙️ Прочее":
                user.setState(UserState.OTHER_MENU);
                userService.update(user);
                showOtherMenu(chatId, user, bot);
                break;
            default:
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, "❌ Пожалуйста, используйте кнопки меню", createMainMenuKeyboard(user)));
        }
    }
    private void showAdminMainMenu(Long chatId, MyBot bot) {
        String message = "👨‍💼 Админ панель\n\nВыберите действие:";
        ReplyKeyboardMarkup keyboard = createAdminMainMenuKeyboard();
        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, keyboard));
    }

    private void showBuyMenu(Long chatId, MyBot bot) {
        String message = "💰 Покупка Bitcoin\n\n" +
                "Вы хотите купить Bitcoin за рубли.\n\n" +
                "После ввода суммы вы увидите:\n" +
                "• Сколько рублей вы отдадите\n" +
                "• Сколько Bitcoin получите";

        ReplyKeyboardMarkup keyboard = createBuyMenuKeyboard();
        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, keyboard));
    }

    private void showSellMenu(Long chatId, MyBot bot) {
        String message = "💸 Продажа Bitcoin\n\n" +
                "Вы хотите продать Bitcoin за рубли.\n\n" +
                "После ввода суммы вы увидите:\n" +
                "• Сколько Bitcoin вы отдадите\n" +
                "• Сколько рублей получите";

        ReplyKeyboardMarkup keyboard = createSellMenuKeyboard();
        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, keyboard));
    }

    private void processBuyMenu(Long chatId, User user, String text, MyBot bot) {
        if ("Ввести сумму в RUB".equals(text)) {
            user.setState(UserState.ENTERING_BUY_AMOUNT_RUB);
            userService.update(user);
            currentOperation.put(user.getId(), "BUY_RUB");

            String message = "💎 Введите сумму в рублях, которую хотите потратить на покупку Bitcoin:";
            ReplyKeyboardMarkup keyboard = createAmountInputKeyboard();
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, keyboard));
        } else if ("Ввести количество в BTC".equals(text)) {
            user.setState(UserState.ENTERING_BUY_AMOUNT_BTC);
            userService.update(user);
            currentOperation.put(user.getId(), "BUY_BTC");

            String message = "💎 Введите количество Bitcoin, которое хотите купить:";
            ReplyKeyboardMarkup keyboard = createAmountInputKeyboard();
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, keyboard));
        } else if ("Главное меню".equals(text)) {
            processMainMenu(chatId, user, bot);
        } else {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, "❌ Пожалуйста, используйте кнопки", createBuyMenuKeyboard()));
        }
    }

    private void processSellMenu(Long chatId, User user, String text, MyBot bot) {
        if ("Ввести сумму".equals(text)) {
            user.setState(UserState.ENTERING_SELL_AMOUNT);
            userService.update(user);
            currentOperation.put(user.getId(), "SELL");

            String message = "💎 Введите количество Bitcoin, которое хотите продать:";
            ReplyKeyboardMarkup keyboard = createAmountInputKeyboard();
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, keyboard));
        } else if ("Главное меню".equals(text)) {
            processMainMenu(chatId, user, bot);
        } else {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, "❌ Пожалуйста, используйте кнопки", createSellMenuKeyboard()));
        }
    }


    private void processEnteringSellAmount(Long chatId, User user, String text, MyBot bot) {
        switch (text) {
            case "Калькулятор":
                String calculatorCommand = "@" + bot.getBotUsername() + " sell-BTC ";
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                        "🧮 Введите количество для расчета: " + calculatorCommand, createAmountInputKeyboard()));
                break;
            case "Назад":
                user.setState(UserState.SELL_MENU);
                userService.update(user);
                showSellMenu(chatId, bot);
                break;
            case "Главное меню":
                processMainMenu(chatId, user, bot);
                break;
            default:
                try {
                    double btcAmount = Double.parseDouble(text);
                    if (btcAmount <= 0) {
                        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                                "❌ Количество должно быть больше 0", createAmountInputKeyboard()));
                        return;
                    }

                    // Расчет стоимости в RUB
                    double btcPrice = cryptoPriceService.getCurrentPrice("BTC", "RUB");
                    double rubAmount = btcAmount * btcPrice;

                    // Для продажи комиссия не применяется (или применяется по-другому)
                    Application application = new Application();
                    application.setUser(user);
                    application.setUserValueGetType(ValueType.RUB);
                    application.setUserValueGiveType(ValueType.BTC);
                    application.setUserValueGetValue(rubAmount);
                    application.setUserValueGiveValue(btcAmount);
                    application.setCalculatedGetValue(rubAmount);
                    application.setCalculatedGiveValue(btcAmount);
                    application.setTitle("Продажа BTC за RUB");

                    temporaryApplications.put(user.getId(), application);

                    String calculationMessage = String.format("""
                        💰 Расчет операции:
                        
                        ₿ Вы отдадите: %.8f BTC
                        💸 Вы получите: %.2f ₽
                        
                        Подтверждаете создание заявки?
                        """, btcAmount, rubAmount);

                    ReplyKeyboardMarkup keyboard = createConfirmationKeyboard();
                    lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, calculationMessage, keyboard));

                    user.setState(UserState.CONFIRMING_APPLICATION);
                    userService.update(user);

                } catch (NumberFormatException e) {
                    lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                            "❌ Пожалуйста, введите корректное число", createAmountInputKeyboard()));
                }
        }
    }

    private void showExchangeRates(Long chatId, User user, MyBot bot) {
        double btcPrice = cryptoPriceService.getCurrentPrice("BTC", "RUB");
        double ethPrice = cryptoPriceService.getCurrentPrice("ETH", "RUB");

        String message = String.format("""
            📊 Текущие курсы:
            
            ₿ Bitcoin (BTC): %.2f ₽
            Ξ Ethereum (ETH): %.2f ₽
            
            *Курсы обновляются автоматически
            """, btcPrice, ethPrice);

        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createOtherMenuKeyboard()));
    }
    private void showProfile(Long chatId, User user, MyBot bot) {
        String message = String.format("""
            👤 Ваш профиль:
            
            📊 Статистика заявок:
            ✅ Успешно проведено: %d
            💰 Куплено: %.2f ₽
            💸 Продано: %.2f ₽
            📈 Всего заявок: %d
            💸 Комиссий уплачено: %.2f ₽
            
            📈 Реферальная система:
            👥 Приглашено: %d
            💰 Заработано: %.2f ₽
            """,
                user.getCompletedBuyApplications() + user.getCompletedSellApplications(),
                user.getTotalBuyAmount(),
                user.getTotalSellAmount(),
                user.getTotalApplications(),
                user.getTotalCommissionPaid(),
                user.getReferralCount(),
                user.getReferralEarnings()
        );

        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createOtherMenuKeyboard()));
    }

    private void processApplyingCoupon(Long chatId, User user, String text, MyBot bot) {
        Application application = temporaryApplications.get(user.getId());

        if (application == null) {
            processMainMenu(chatId, user, bot);
            return;
        }

        switch (text) {
            case "Применить купон":
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                        "🎫 Введите код купона:", createBackKeyboard()));
                break;
            case "Пропустить":
                createApplicationWithoutCoupon(chatId, user, application, bot);
                break;
            case "Назад":
                user.setState(UserState.ENTERING_BUY_AMOUNT);
                userService.update(user);
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                        "Введите сумму:", createAmountInputKeyboard()));
                break;
            case "Главное меню":
                processMainMenu(chatId, user, bot);
                break;
            default:
                // Обработка ввода кода купона
                processCouponCode(chatId, user, application, text, bot);
        }
    }

    private void processCouponCode(Long chatId, User user, Application application, String couponCode, MyBot bot) {
        try {
            Coupon coupon = couponService.findValidCoupon(couponCode, user)
                    .orElseThrow(() -> new IllegalArgumentException("Недействительный купон"));

            // Применяем купон к заявке
            double originalAmount = application.getCalculatedGiveValue();
            double discountedAmount = couponService.applyCoupon(originalAmount, coupon);

            application.setAppliedCoupon(coupon);
            application.setFinalAmountAfterDiscount(discountedAmount);

            String message = String.format("""
                ✅ Купон применен!
                
                🎫 Купон: %s
                💰 Скидка: %s
                💸 Итоговая сумма: %.2f ₽
                
                Заявка создана с применением купона!
                """,
                    coupon.getCode(),
                    coupon.getDiscountPercent() != null ?
                            coupon.getDiscountPercent() + "%" :
                            coupon.getDiscountAmount() + " ₽",
                    discountedAmount);

            // Сохраняем заявку
            applicationService.create(application);
            temporaryApplications.remove(user.getId());

            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createMainMenuKeyboard(user)));

            user.setState(UserState.MAIN_MENU);
            userService.update(user);

        } catch (IllegalArgumentException e) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                    "❌ " + e.getMessage() + "\n\nПопробуйте другой код или нажмите 'Пропустить'",
                    createCouponApplicationKeyboard()));
        }
    }

    private void createApplicationWithoutCoupon(Long chatId, User user, Application application, MyBot bot) {
        applicationService.create(application);
        temporaryApplications.remove(user.getId());

        // Обновляем статистику пользователя
        if (application.getUserValueGetType() == ValueType.BTC) {
            // Покупка BTC за RUB
            user.setTotalBuyAmount(user.getTotalBuyAmount() + application.getCalculatedGiveValue());
            user.setCompletedBuyApplications(user.getCompletedBuyApplications() + 1);
            // Комиссия уже включена в calculatedGiveValue, но мы можем ее выделить
            double commission = commissionService.calculateCommission(application.getCalculatedGiveValue());
            user.setTotalCommissionPaid(user.getTotalCommissionPaid() + commission);
        } else {
            // Продажа BTC за RUB
            user.setTotalSellAmount(user.getTotalSellAmount() + application.getCalculatedGetValue());
            user.setCompletedSellApplications(user.getCompletedSellApplications() + 1);
        }
        user.setTotalApplications(user.getTotalApplications() + 1);
        userService.update(user);

        // Обрабатываем реферальное вознаграждение
        referralService.processReferralReward(application);

        String message = "✅ Заявка успешно создана!\n\n";
        if (application.getUserValueGetType() == ValueType.BTC) {
            message += String.format("💸 Вы отдадите: %.2f ₽\n₿ Вы получите: %.8f BTC",
                    application.getCalculatedGiveValue(), application.getCalculatedGetValue());
        } else {
            message += String.format("₿ Вы отдадите: %.8f BTC\n💸 Вы получите: %.2f ₽",
                    application.getCalculatedGiveValue(), application.getCalculatedGetValue());
        }

        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createMainMenuKeyboard(user)));

        user.setState(UserState.MAIN_MENU);
        userService.update(user);
    }
    private void showOtherMenu(Long chatId, User user, MyBot bot) {
        String message = "⚙️ Прочее\n\nВыберите раздел:";
        ReplyKeyboardMarkup keyboard = createOtherMenuKeyboard();
        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, keyboard));
    }
    private void processViewingCoupons(Long chatId, User user, MyBot bot) {
        List<Coupon> userCoupons = couponService.getUserCoupons(user.getId());

        if (userCoupons.isEmpty()) {
            String message = "🎫 У вас пока нет доступных купонов.";
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createMainMenuKeyboard(user)));
        } else {
            StringBuilder response = new StringBuilder("🎫 Ваши купоны:\n\n");

            for (int i = 0; i < userCoupons.size(); i++) {
                Coupon coupon = userCoupons.get(i);
                response.append(String.format("""
                🔢 Номер: %d
                🎫 Код: %s
                💰 Скидка: %s
                📝 Описание: %s
                """,
                        i + 1,
                        coupon.getCode(),
                        coupon.getDiscountPercent() != null ?
                                coupon.getDiscountPercent() + "%" :
                                coupon.getDiscountAmount() + " ₽",
                        coupon.getDescription() != null ? coupon.getDescription() : "Без описания"
                ));

                if (coupon.getValidUntil() != null) {
                    response.append(String.format("📅 Действует до: %s\n", coupon.getValidUntil().toLocalDate()));
                }

                response.append("--------------------\n");
            }

            response.append("\nЧтобы использовать купон, введите его номер при создании заявки.");
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, response.toString(), createMainMenuKeyboard(user)));
        }

        user.setState(UserState.MAIN_MENU);
        userService.update(user);
    }

    private void processViewingApplications(Long chatId, User user, MyBot bot) {
        List<Application> applications = applicationService.findByUser(user.getId());

        if (applications.isEmpty()) {
            String message = "📭 У вас пока нет заявок.\nСоздайте первую с помощью кнопки '💰 Купить' или '💸 Продать'";
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createMainMenuKeyboard(user)));
        } else {
            StringBuilder response = new StringBuilder("📋 Ваши заявки:\n\n");

            for (int i = 0; i < applications.size(); i++) {
                Application app = applications.get(i);
                response.append(String.format("""
                    🆔 Заявка #%d
                    💰 Получаю: %.8f %s
                    💸 Отдаю: %.2f %s
                    📅 Создана: %s
                    """,
                        app.getId(),
                        app.getUserValueGetValue(),
                        app.getUserValueGetType().getDisplayName(),
                        app.getUserValueGiveValue(),
                        app.getUserValueGiveType().getDisplayName(),
                        app.getCreatedAt().toLocalDate()
                ));

                if (app.getAppliedCoupon() != null) {
                    response.append(String.format("🎫 Купон: %s\n", app.getAppliedCoupon().getCode()));
                }

                response.append("--------------------\n");
            }

            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, response.toString(), createMainMenuKeyboard(user)));
        }

        user.setState(UserState.MAIN_MENU);
        userService.update(user);
    }
    private void processEnteringBuyAmountRub(Long chatId, User user, String text, MyBot bot) {
        switch (text) {
            case "Калькулятор":
                String calculatorCommand = "@" + bot.getBotUsername() + " buy-BTC ";
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, "🧮 Введите сумму для расчета: " + calculatorCommand, createAmountInputKeyboard()));
                break;
            case "Назад":
                user.setState(UserState.BUY_MENU);
                userService.update(user);
                showBuyMenu(chatId, bot);
                break;
            case "Главное меню":
                processMainMenu(chatId, user, bot);
                break;
            default:
                try {
                    double rubAmount = Double.parseDouble(text);

                    // Проверяем минимальную сумму
                    if (rubAmount < 1000) {
                        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                                "❌ Минимальная сумма заявки 1000 рублей", createAmountInputKeyboard()));
                        return;
                    }

                    // Рассчитываем комиссию
                    double commission = commissionService.calculateCommission(rubAmount);
                    double totalAmount = commissionService.calculateTotalWithCommission(rubAmount);

                    // Расчет получения BTC (по сумме без комиссии)
                    double btcPrice = cryptoPriceService.getCurrentPrice("BTC", "RUB");
                    double btcAmount = rubAmount / btcPrice;

                    // Создаем временную заявку
                    Application application = new Application();
                    application.setUser(user);
                    application.setUserValueGetType(ValueType.BTC);
                    application.setUserValueGiveType(ValueType.RUB);
                    application.setUserValueGetValue(btcAmount);
                    application.setUserValueGiveValue(totalAmount); // Сумма с комиссией
                    application.setCalculatedGetValue(btcAmount);
                    application.setCalculatedGiveValue(totalAmount);
                    application.setTitle("Покупка BTC за RUB");

                    temporaryApplications.put(user.getId(), application);

                    String calculationMessage = String.format("""
                        💰 Расчет операции:
                        
                        💸 Вы отдадите: %.2f ₽
                        ₿ Вы получите: %.8f BTC
                        
                        Подтверждаете создание заявки?
                        """, totalAmount, btcAmount);

                    ReplyKeyboardMarkup keyboard = createConfirmationKeyboard();
                    lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, calculationMessage, keyboard));

                    user.setState(UserState.CONFIRMING_APPLICATION);
                    userService.update(user);

                } catch (NumberFormatException e) {
                    lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                            "❌ Пожалуйста, введите корректное число", createAmountInputKeyboard()));
                } catch (IllegalArgumentException e) {
                    lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                            "❌ " + e.getMessage(), createAmountInputKeyboard()));
                }
        }
    }

    private void processEnteringBuyAmountBtc(Long chatId, User user, String text, MyBot bot) {
        switch (text) {
            case "Калькулятор":
                String calculatorCommand = "@" + bot.getBotUsername() + " buy-BTC ";
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, "🧮 Введите количество для расчета: " + calculatorCommand, createAmountInputKeyboard()));
                break;
            case "Назад":
                user.setState(UserState.BUY_MENU);
                userService.update(user);
                showBuyMenu(chatId, bot);
                break;
            case "Главное меню":
                processMainMenu(chatId, user, bot);
                break;
            default:
                try {
                    double btcAmount = Double.parseDouble(text);
                    if (btcAmount <= 0) {
                        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, "❌ Количество должно быть больше 0", createAmountInputKeyboard()));
                        return;
                    }

                    // Расчет стоимости в RUB
                    double btcPrice = cryptoPriceService.getCurrentPrice("BTC", "RUB");
                    double rubAmount = btcAmount * btcPrice;

                    processBuyConfirmation(chatId, user, rubAmount, btcAmount, "BTC", "RUB", bot);

                } catch (NumberFormatException e) {
                    lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, "❌ Пожалуйста, введите корректное число", createAmountInputKeyboard()));
                }
        }
    }

    private void processBuyConfirmation(Long chatId, User user, double rubAmount, double btcAmount,
                                        String inputType, String outputType, MyBot bot) {

        // Создаем временную заявку
        Application application = new Application();
        application.setUser(user);
        application.setUserValueGetType(ValueType.BTC);
        application.setUserValueGiveType(ValueType.RUB);
        application.setUserValueGetValue(btcAmount);
        application.setUserValueGiveValue(rubAmount);
        application.setCalculatedGetValue(btcAmount);
        application.setCalculatedGiveValue(rubAmount);
        application.setTitle("Покупка BTC за RUB");

        temporaryApplications.put(user.getId(), application);

        String calculationMessage;
        if ("RUB".equals(inputType)) {
            calculationMessage = String.format("""
            💰 Расчет операции:
            
            💸 Вы отдадите: %.2f ₽
            ₿ Вы получите: %.8f BTC
            
            Подтверждаете создание заявки?
            """, rubAmount, btcAmount);
        } else {
            calculationMessage = String.format("""
            💰 Расчет операции:
            
            ₿ Вы отдадите: %.8f BTC
            💸 Вы получите: %.2f ₽
            
            Подтверждаете создание заявки?
            """, btcAmount, rubAmount);
        }

        ReplyKeyboardMarkup keyboard = createConfirmationKeyboard();
        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, calculationMessage, keyboard));

        user.setState(UserState.CONFIRMING_APPLICATION);
        userService.update(user);
    }
    private void processConfirmingApplication(Long chatId, User user, String text, MyBot bot) {
        Application application = temporaryApplications.get(user.getId());

        if (application == null) {
            processMainMenu(chatId, user, bot);
            return;
        }

        switch (text) {
            case "Подтвердить":
                // Сохраняем заявку
                applicationService.create(application);
                temporaryApplications.remove(user.getId());

                String message = String.format("""
                ✅ Заявка успешно создана!
                
                🆔 Номер заявки: %d
                💰 Сумма: %.2f ₽
                ₿ Bitcoin: %.8f BTC
                📅 Время: %s
                """,
                        application.getId(),
                        application.getCalculatedGiveValue(),
                        application.getCalculatedGetValue(),
                        application.getCreatedAt().toString()
                );

                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createMainMenuKeyboard(user)));

                user.setState(UserState.MAIN_MENU);
                userService.update(user);
                break;
            case "Отмена":
                temporaryApplications.remove(user.getId());
                processMainMenu(chatId, user, bot);
                break;
            default:
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, "❌ Пожалуйста, используйте кнопки", createConfirmationKeyboard()));
        }
    }

    private void processViewingQueue(Long chatId, User user, MyBot bot) {
        List<Application> allApplications = applicationService.findAll();
        int totalApplications = allApplications.size();
        int activeUsers = userService.getActiveUsersCount(); // Нужно добавить этот метод в UserService

        String message = String.format("""
            📊 Статистика системы:
            
            📈 Всего заявок: %d
            👥 Активных пользователей: %d
            
            💡 Система работает стабильно
            """, totalApplications, activeUsers);

        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createMainMenuKeyboard(user)));

        user.setState(UserState.MAIN_MENU);
        userService.update(user);
    }

    private void processMainMenu(Long chatId, User user, MyBot bot) {
        user.setState(UserState.MAIN_MENU);
        userService.update(user);
        showMainMenu(chatId, user, bot);
    }
    private void processAdminMainMenu(Long chatId, User user, String text, MyBot bot) {
        switch (text) {
            case "📋 Все заявки":
                user.setState(UserState.ADMIN_VIEWING_ALL_APPLICATIONS);
                userService.update(user);
                processAdminViewingAllApplications(chatId, user, bot);
                break;
            case "📊 Статистика":
                showAdminStatistics(chatId, user, bot);
                break;
            case "👥 Пользователи":
                showAdminUsers(chatId, user, bot);
                break;
            case "🎫 Купоны":
                showAdminCoupons(chatId, user, bot);
                break;
            case "🔙 Главное меню":
                processMainMenu(chatId, user, bot);
                break;
            default:
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, "❌ Пожалуйста, используйте кнопки", createAdminMainMenuKeyboard()));
        }
    }
    private void showAdminStatistics(Long chatId, User user, MyBot bot) {
        List<Application> allApplications = applicationService.findAll();
        Map<ApplicationStatus, Long> statusCount = allApplications.stream()
                .collect(Collectors.groupingBy(Application::getStatus, Collectors.counting()));

        int totalUsers = userService.getActiveUsersCount();
        int totalApplications = allApplications.size();

        String message = String.format("""
            📊 Статистика системы:
            
            👥 Всего пользователей: %d
            📋 Всего заявок: %d
            
            📈 Статусы заявок:
            🟢 Свободны: %d
            🟡 В работе: %d
            🔵 Закрыты: %d
            🔴 Отменены: %d
            """,
                totalUsers,
                totalApplications,
                statusCount.getOrDefault(ApplicationStatus.FREE, 0L),
                statusCount.getOrDefault(ApplicationStatus.IN_WORK, 0L),
                statusCount.getOrDefault(ApplicationStatus.CLOSED, 0L),
                statusCount.getOrDefault(ApplicationStatus.CANCELLED, 0L)
        );

        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createAdminMainMenuKeyboard()));
    }

    private void showAdminUsers(Long chatId, User user, MyBot bot) {
        // Здесь можно добавить логику для просмотра пользователей
        String message = "👥 Раздел управления пользователями в разработке";
        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createAdminMainMenuKeyboard()));
    }

    private void showAdminCoupons(Long chatId, User user, MyBot bot) {
        // Здесь можно добавить логику для управления купонами
        String message = "🎫 Раздел управления купонами в разработке";
        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createAdminMainMenuKeyboard()));
    }

    private void processAdminViewingAllApplications(Long chatId, User user, MyBot bot) {
        // Показываем только активные заявки (FREE и IN_WORK)
        List<Application> activeApplications = applicationService.findActiveApplications();

        if (activeApplications.isEmpty()) {
            String message = "📭 Нет активных заявок.";
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createAdminMainMenuKeyboard()));
        } else {
            StringBuilder response = new StringBuilder("📋 Активные заявки:\n\n");

            for (Application app : activeApplications) {
                response.append(String.format("""
                🆔 Заявка #%d
                👤 Пользователь: %s (@%s)
                💰 Тип: %s
                📊 Статус: %s
                💸 Сумма: %.2f ₽
                ₿ Bitcoin: %.8f BTC
                📅 Создана: %s
                """,
                        app.getId(),
                        app.getUser().getFirstName(),
                        app.getUser().getUsername() != null ? app.getUser().getUsername() : "нет username",
                        app.getTitle(),
                        app.getStatus().getDisplayName(),
                        app.getCalculatedGiveValue(),
                        app.getCalculatedGetValue(),
                        app.getCreatedAt().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
                ));

                response.append("--------------------\n");
            }

            response.append("\nДля управления заявкой введите её номер:");

            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, response.toString(), createBackKeyboard()));
        }
    }


    // Обработка выбора заявки админом
    private void processAdminApplicationSelection(Long chatId, User user, String text, MyBot bot) {
        try {
            Long applicationId = Long.parseLong(text);
            Application application = applicationService.find(applicationId);

            if (application == null) {
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, "❌ Заявка не найдена", createAdminMainMenuKeyboard()));
                return;
            }

            selectedApplication.put(user.getId(), applicationId);
            user.setState(UserState.ADMIN_VIEWING_APPLICATION_DETAILS);
            userService.update(user);

            showAdminApplicationDetails(chatId, user, application, bot);

        } catch (NumberFormatException e) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, "❌ Введите корректный номер заявки", createBackKeyboard()));
        }
    }

    // Детали заявки для админа
    private void showAdminApplicationDetails(Long chatId, User user, Application application, MyBot bot) {
        String message = String.format("""
            📋 Детали заявки #%d
            
            👤 Пользователь: %s %s (@%s)
            📞 Telegram ID: %d
            💰 Тип операции: %s
            📊 Статус: %s
            
            💸 Отдает: %.2f %s
            💰 Получает: %.8f %s
            
            📅 Создана: %s
            """,
                application.getId(),
                application.getUser().getFirstName(),
                application.getUser().getLastName() != null ? application.getUser().getLastName() : "",
                application.getUser().getUsername() != null ? application.getUser().getUsername() : "нет username",
                application.getUser().getTelegramId(),
                application.getTitle(),
                application.getStatus().getDisplayName(),
                application.getCalculatedGiveValue(),
                application.getUserValueGiveType().getDisplayName(),
                application.getCalculatedGetValue(),
                application.getUserValueGetType().getDisplayName(),
                application.getCreatedAt().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
        );

        if (application.getAppliedCoupon() != null) {
            message += String.format("\n🎫 Применен купон: %s", application.getAppliedCoupon().getCode());
        }

        ReplyKeyboardMarkup keyboard = createAdminApplicationActionsKeyboard();
        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, keyboard));
    }
    private void processOtherMenu(Long chatId, User user, String text, MyBot bot) {
        switch (text) {
            case "📊 Курсы":
                showExchangeRates(chatId, user, bot);
                break;
            case "👤 Профиль":
                showProfile(chatId, user, bot);
                break;
            case "📈 Реферальная система":
                user.setState(UserState.REFERRAL_MENU);
                userService.update(user);
                showReferralMenu(chatId, user, bot);
                break;
            case "🔙 Главное меню":
                processMainMenu(chatId, user, bot);
                break;
            default:
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, "❌ Пожалуйста, используйте кнопки", createOtherMenuKeyboard()));
        }
    }
    private void processReferralMenu(Long chatId, User user, String text, MyBot bot) {
        switch (text) {
            case "Создать реферальный код":
                user.setState(UserState.CREATING_REFERRAL_CODE);
                userService.update(user);
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                        "Введите описание для вашего реферального кода:", createBackKeyboard()));
                break;
            case "Ввести реферальный код":
                if (user.getUsedReferralCode() != null) {
                    lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                            "❌ Вы уже использовали реферальный код.", createReferralMenuKeyboard()));
                    return;
                }
                user.setState(UserState.ENTERING_REFERRAL_CODE);
                userService.update(user);
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                        "Введите реферальный код:", createBackKeyboard()));
                break;
            case "🔙 Назад":
                user.setState(UserState.MAIN_MENU);
                userService.update(user);
                showOtherMenu(chatId, user, bot);
                break;
            default:
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                        "❌ Пожалуйста, используйте кнопки", createReferralMenuKeyboard()));
        }
    }
    private void showReferralMenu(Long chatId, User user, MyBot bot) {
        Long referralCount = referralService.getUserReferralCount(user.getId());
        List<ReferralCode> userCodes = referralService.getUserReferralCodes(user.getId());

        StringBuilder message = new StringBuilder();
        message.append("📈 Реферальная система\n\n");
        message.append(String.format("👥 Приглашено пользователей: %d\n", referralCount));
        message.append(String.format("💰 Заработано: %.2f ₽\n\n", user.getReferralEarnings()));

        if (user.getUsedReferralCode() != null) {
            message.append(String.format("✅ Вы использовали реферальный код: %s\n\n", user.getUsedReferralCode()));
        }

        message.append("Ваши реферальные коды:\n");
        if (userCodes.isEmpty()) {
            message.append("У вас пока нет реферальных кодов.\n");
        } else {
            for (ReferralCode code : userCodes) {
                message.append(String.format("🔸 %s - %s (Использований: %d)\n",
                        code.getCode(),
                        code.getDescription(),
                        code.getUsages().size()));
            }
        }

        ReplyKeyboardMarkup keyboard = createReferralMenuKeyboard();
        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message.toString(), keyboard));
    }
    private void processCreatingReferralCode(Long chatId, User user, String text, MyBot bot) {
        if (text.equals("Назад")) {
            user.setState(UserState.REFERRAL_MENU);
            userService.update(user);
            showReferralMenu(chatId, user, bot);
            return;
        }

        try {
            ReferralCode referralCode = referralService.createReferralCode(user, text);
            String message = String.format("✅ Реферальный код создан!\n\nКод: %s\nОписание: %s\n\nТеперь вы можете делиться этим кодом с друзьями. За каждую успешную заявку реферала вы будете получать %.2f%% от суммы заявки.",
                    referralCode.getCode(),
                    referralCode.getDescription(),
                    referralCode.getRewardPercent());

            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createReferralMenuKeyboard()));

            user.setState(UserState.REFERRAL_MENU);
            userService.update(user);

        } catch (Exception e) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                    "❌ Ошибка при создании реферального кода", createReferralMenuKeyboard()));
        }
    }
    private void processEnteringReferralCode(Long chatId, User user, String text, MyBot bot) {
        if (text.equals("Назад")) {
            user.setState(UserState.REFERRAL_MENU);
            userService.update(user);
            showReferralMenu(chatId, user, bot);
            return;
        }

        boolean success = referralService.useReferralCode(text, user);
        if (success) {
            String message = "✅ Реферальный код успешно активирован! Теперь вы будете получать бонусы за приглашенных друзей.";
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createReferralMenuKeyboard()));
        } else {
            String message = "❌ Неверный реферальный код или он уже был использован.";
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createReferralMenuKeyboard()));
        }

        user.setState(UserState.REFERRAL_MENU);
        userService.update(user);
    }

    private ReplyKeyboardMarkup createReferralMenuKeyboard() {
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(false);

        List<KeyboardRow> rows = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add("Создать реферальный код");
        row1.add("Ввести реферальный код");

        KeyboardRow row2 = new KeyboardRow();
        row2.add("🔙 Назад");

        rows.add(row1);
        rows.add(row2);

        keyboard.setKeyboard(rows);
        return keyboard;
    }


    // Методы создания клавиатур
    private InlineKeyboardMarkup createCaptchaKeyboard(List<String> options) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        for (int i = 0; i < options.size(); i += 2) {
            List<InlineKeyboardButton> row = new ArrayList<>();
            for (int j = i; j < i + 2 && j < options.size(); j++) {
                String emoji = options.get(j);
                InlineKeyboardButton button = new InlineKeyboardButton();
                button.setText(emoji);
                button.setCallbackData("captcha_" + emoji);
                row.add(button);
            }
            rows.add(row);
        }

        markup.setKeyboard(rows);
        return markup;
    }

    private ReplyKeyboardMarkup createMainMenuKeyboard(User user) {
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(false);

        List<KeyboardRow> rows = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add("💰 Купить");
        row1.add("💸 Продать");

        KeyboardRow row2 = new KeyboardRow();
        row2.add("📋 Мои заявки");
        row2.add("🎫 Мои купоны");

        KeyboardRow row3 = new KeyboardRow();
        row3.add("📊 Очередь");

        // Добавляем админскую панель для админов
        if (adminConfig.isAdmin(user.getId())) {
            KeyboardRow adminRow = new KeyboardRow();
            adminRow.add("👨‍💼 Админ панель");
            rows.add(adminRow);
        }

        KeyboardRow row4 = new KeyboardRow();
        row4.add("⚙️ Прочее");

        rows.add(row1);
        rows.add(row2);
        rows.add(row3);
        rows.add(row4);

        keyboard.setKeyboard(rows);
        return keyboard;
    }

    private ReplyKeyboardMarkup createAdminApplicationActionsKeyboard() {
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(false);

        List<KeyboardRow> rows = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add("🟡 В работу");
        row1.add("🔵 Закрыть");

        KeyboardRow row2 = new KeyboardRow();
        row2.add("🔴 Отменить");
        row1.add("🟢 Свободна");

        KeyboardRow row3 = new KeyboardRow();
        row3.add("📋 Все заявки");
        row3.add("🔙 Назад");

        rows.add(row1);
        rows.add(row2);
        rows.add(row3);

        keyboard.setKeyboard(rows);
        return keyboard;
    }


    private ReplyKeyboardMarkup createBuyMenuKeyboard() {
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(false);

        List<KeyboardRow> rows = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add("Ввести сумму в RUB");
        row1.add("Ввести количество в BTC");

        KeyboardRow row2 = new KeyboardRow();
        row2.add("Главное меню");

        rows.add(row1);
        rows.add(row2);

        keyboard.setKeyboard(rows);
        return keyboard;
    }

    private ReplyKeyboardMarkup createSellMenuKeyboard() {
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(false);

        List<KeyboardRow> rows = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add("Ввести сумму");

        KeyboardRow row2 = new KeyboardRow();
        row2.add("Главное меню");

        rows.add(row1);
        rows.add(row2);

        keyboard.setKeyboard(rows);
        return keyboard;
    }

    private ReplyKeyboardMarkup createAmountInputKeyboard() {
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(false);

        List<KeyboardRow> rows = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add("Калькулятор");

        KeyboardRow row2 = new KeyboardRow();
        row2.add("Назад");
        row2.add("Главное меню");

        rows.add(row1);
        rows.add(row2);

        keyboard.setKeyboard(rows);
        return keyboard;
    }

    private ReplyKeyboardMarkup createCouponApplicationKeyboard() {
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(false);

        List<KeyboardRow> rows = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add("Применить купон");
        row1.add("Пропустить");

        KeyboardRow row2 = new KeyboardRow();
        row2.add("Назад");
        row2.add("Главное меню");

        rows.add(row1);
        rows.add(row2);

        keyboard.setKeyboard(rows);
        return keyboard;
    }

    private ReplyKeyboardMarkup createBackKeyboard() {
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(false);

        List<KeyboardRow> rows = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add("Назад");

        rows.add(row1);

        keyboard.setKeyboard(rows);
        return keyboard;
    }
    private ReplyKeyboardMarkup createConfirmationKeyboard() {
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(false);

        List<KeyboardRow> rows = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add("Подтвердить");
        row1.add("Отмена");

        rows.add(row1);

        keyboard.setKeyboard(rows);
        return keyboard;
    }

    private ReplyKeyboardMarkup createOtherMenuKeyboard() {
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(false);

        List<KeyboardRow> rows = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add("📊 Курсы");
        row1.add("👤 Профиль");

        KeyboardRow row2 = new KeyboardRow();
        row2.add("📈 Реферальная система");

        KeyboardRow row3 = new KeyboardRow();
        row3.add("🔙 Главное меню");

        rows.add(row1);
        rows.add(row2);
        rows.add(row3);

        keyboard.setKeyboard(rows);
        return keyboard;
    }
    // В админском меню добавим кнопку для управления комиссиями
    private ReplyKeyboardMarkup createAdminMainMenuKeyboard() {
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(false);

        List<KeyboardRow> rows = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add("📋 Все заявки");
        row1.add("📊 Статистика");

        KeyboardRow row2 = new KeyboardRow();
        row2.add("👥 Пользователи");
        row2.add("🎫 Купоны");

        KeyboardRow row3 = new KeyboardRow();
        row3.add("💰 Комиссии");
        row3.add("🔙 Главное меню");

        rows.add(row1);
        rows.add(row2);
        rows.add(row3);

        keyboard.setKeyboard(rows);
        return keyboard;
    }

    // Обработка управления комиссиями
    private void processAdminCommissionSettings(Long chatId, User user, String text, MyBot bot) {
        if (text.equals("🔙 Назад")) {
            user.setState(UserState.ADMIN_MAIN_MENU);
            userService.update(user);
            showAdminMainMenu(chatId, bot);
            return;
        }

        // Здесь можно добавить логику для изменения комиссий
        // Например, парсинг команд типа "1000-1999 5"

        String message = "💰 Управление комиссиями\n\n" +
                "Текущие настройки комиссий:\n" +
                "• 1000-1999 ₽: 5%\n" +
                "• 2000-2999 ₽: 4%\n" +
                "• 3000-4999 ₽: 3%\n" +
                "• 5000+ ₽: 2%\n\n" +
                "Для изменения используйте формат: МИН-МАКС ПРОЦЕНТ\n" +
                "Пример: 1000-1999 5";

        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createBackKeyboard()));
    }
}