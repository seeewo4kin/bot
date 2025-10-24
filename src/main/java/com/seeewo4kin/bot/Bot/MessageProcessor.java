package com.seeewo4kin.bot.Bot;

import com.seeewo4kin.bot.Config.AdminConfig;
import com.seeewo4kin.bot.Config.CommissionConfig;
import com.seeewo4kin.bot.Entity.Application;
import com.seeewo4kin.bot.Entity.Coupon;
import com.seeewo4kin.bot.Entity.ReferralCode;
import com.seeewo4kin.bot.Entity.User;
import com.seeewo4kin.bot.Enums.ApplicationStatus;
import com.seeewo4kin.bot.Enums.UserState;
import com.seeewo4kin.bot.Enums.ValueType;
import com.seeewo4kin.bot.ValueGettr.CryptoPriceService;
import com.seeewo4kin.bot.service.*;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class MessageProcessor {
    private final UserService userService;
    private final ApplicationService applicationService;
    private final CryptoPriceService cryptoPriceService;
    private final CaptchaService captchaService;
    private final CouponService couponService;
    private final AdminConfig adminConfig;
    private final CommissionService commissionService;
    private final ReferralService referralService;
    private final CommissionConfig commissionConfig;

    private final Map<Long, Application> temporaryApplications = new ConcurrentHashMap<>();
    private final Map<Long, String> currentOperation = new ConcurrentHashMap<>();
    private final Map<Long, Integer> lastMessageId = new ConcurrentHashMap<>();
    private final Map<Long, Long> selectedApplication = new ConcurrentHashMap<>();

    public MessageProcessor(UserService userService,
                            ApplicationService applicationService,
                            CryptoPriceService cryptoPriceService,
                            CaptchaService captchaService,
                            CouponService couponService,
                            AdminConfig adminConfig,
                            CommissionService commissionService,
                            ReferralService referralService,
                            CommissionConfig commissionConfig) {
        this.userService = userService;
        this.applicationService = applicationService;
        this.cryptoPriceService = cryptoPriceService;
        this.captchaService = captchaService;
        this.couponService = couponService;
        this.adminConfig = adminConfig;
        this.commissionService = commissionService;
        this.referralService = referralService;
        this.commissionConfig = commissionConfig;
    }


    public void processUpdate(Update update, MyBot bot) {
        // Удаляем предыдущее сообщение бота только для текстовых сообщений
        if (update.hasMessage()) {
            Long chatId = update.getMessage().getChatId();
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

        // Обработка отмены в любом состоянии
        if (text.equalsIgnoreCase("отмена") || text.equalsIgnoreCase("cancel") ||
                text.equals("/cancel") || text.equals("💎 Главное меню")) {
            // Удаляем предыдущее сообщение бота
            deletePreviousBotMessage(chatId, bot);
            processMainMenu(chatId, user, bot);
            return;
        }

        if (text.equals("🔙 Назад")) {
            handleBackButton(chatId, user, bot);
            return;
        }

        // Проверяем, не является ли сообщение отменой заявки
        if (text.startsWith("❌ Отменить заявку #")) {
            try {
                Long applicationId = Long.parseLong(text.replace("❌ Отменить заявку #", "").trim());
                cancelUserApplication(chatId, user, applicationId, bot);
                return;
            } catch (NumberFormatException e) {
                // Продолжаем обычную обработку
            }
        }

        if (text.equals("📞 Написать оператору")) {
            String message = """
                    📞 Связь с оператором:
                    
                    👤 Оператор: @cosanostra_support
                    ⏰ Время работы: 24/7
                    💬 Напишите оператору и отправьте ID вашей заявки
                    
                    ⚠️ Помните: оператор НИКОГДА не пишет первым!
                    """;
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createMainMenuKeyboard(user)));
            return;
        }

        if (user == null || user.getState() == UserState.START) {
            processCommand(update, bot);
        } else {
            processUserState(update, user, bot);
        }
    }

    private void handleBackButton(Long chatId, User user, MyBot bot) {
        switch (user.getState()) {
            case BUY_MENU:
            case SELL_MENU:
            case OTHER_MENU:
            case REFERRAL_MENU:
            case ADMIN_MAIN_MENU:
                processMainMenu(chatId, user, bot);
                break;
            case ENTERING_BUY_AMOUNT_RUB:
            case ENTERING_BUY_AMOUNT_BTC:
                user.setState(UserState.BUY_MENU);
                userService.update(user);
                showBuyMenu(chatId, bot);
                break;
            case ENTERING_SELL_AMOUNT:
                user.setState(UserState.SELL_MENU);
                userService.update(user);
                showSellMenu(chatId, bot);
                break;
            case APPLYING_COUPON:
                // Возврат в меню ввода суммы
                if ("BUY_RUB".equals(currentOperation.get(user.getId())) ||
                        "BUY_BTC".equals(currentOperation.get(user.getId()))) {
                    user.setState(UserState.BUY_MENU);
                    showBuyMenu(chatId, bot);
                } else {
                    user.setState(UserState.SELL_MENU);
                    showSellMenu(chatId, bot);
                }
                break;
            case ADMIN_VIEWING_ALL_APPLICATIONS:
            case ADMIN_COMMISSION_SETTINGS:
                user.setState(UserState.ADMIN_MAIN_MENU);
                userService.update(user);
                showAdminMainMenu(chatId, bot);
                break;
            case ADMIN_VIEWING_APPLICATION_DETAILS:
                user.setState(UserState.ADMIN_VIEWING_ALL_APPLICATIONS);
                userService.update(user);
                processAdminViewingAllApplications(chatId, user, bot);
                break;
            case CREATING_REFERRAL_CODE:
            case ENTERING_REFERRAL_CODE:
                user.setState(UserState.REFERRAL_MENU);
                userService.update(user);
                showReferralMenu(chatId, user, bot);
                break;
            default:
                processMainMenu(chatId, user, bot);
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
            case START:
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
            case APPLYING_COUPON:
                processApplyingCoupon(chatId, user, text, bot);
                break;
            case APPLYING_COUPON_FINAL:
                processApplyingCouponFinal(chatId, user, text, bot);
                break;
            case VIEWING_APPLICATIONS:
                processViewingApplications(chatId, user, bot);
                break;
            case VIEWING_COUPONS:
                processViewingCoupons(chatId, user, bot);
                break;
            case REFERRAL_MENU:
                processReferralMenu(chatId, user, text, bot);
                break;
            case CREATING_REFERRAL_CODE:
                processCreatingReferralCode(chatId, user, text, bot);
                break;
            case ENTERING_REFERRAL_CODE:
                processEnteringReferralCode(chatId, user, text, bot);
                break;
            case OTHER_MENU:
                processOtherMenu(chatId, user, text, bot);
                break;
            case CALCULATOR_MENU:
                processCalculatorMenu(chatId, user, text, bot);
                break;
            case CALCULATOR_BUY:
                processCalculatorBuy(chatId, user, text, bot);
                break;
            case CALCULATOR_SELL:
                processCalculatorSell(chatId, user, text, bot);
                break;
            case CONFIRMING_VIP:
                processVipConfirmation(chatId, user, text, bot);
                break;
            case ENTERING_WALLET:
                processEnteringWallet(chatId, user, text, bot);
                break;

            // Админские состояния
            case ADMIN_MAIN_MENU:
                processAdminMainMenu(chatId, user, text, bot);
                break;
            case ADMIN_VIEW_ALL_APPLICATIONS:
                if (text.equals("🔙 Назад")) {
                    user.setState(UserState.ADMIN_MAIN_MENU);
                    userService.update(user);
                    showAdminMainMenu(chatId, bot);
                } else if (text.equals("📊 Активные")) {
                    user.setState(UserState.ADMIN_VIEW_ACTIVE_APPLICATIONS);
                    userService.update(user);
                    showActiveApplications(chatId, user, bot);
                } else if (text.equals("⏭️ Следующая")) {
                    processNextApplication(chatId, user, bot);
                } else {
                    showAllApplications(chatId, user, bot);
                }
                break;
            case ADMIN_VIEW_ACTIVE_APPLICATIONS:
                if (text.equals("🔙 Назад")) {
                    user.setState(UserState.ADMIN_MAIN_MENU);
                    userService.update(user);
                    showAdminMainMenu(chatId, bot);
                } else {
                    processAdminActiveApplicationsSelection(chatId, user, text, bot);
                }
                break;
            case ADMIN_VIEWING_ALL_APPLICATIONS:
                processAdminApplicationSelection(chatId, user, text, bot);
                break;
            case ADMIN_VIEWING_APPLICATION_DETAILS:
                processAdminApplicationActions(chatId, user, text, bot);
                break;
            case ADMIN_COMMISSION_SETTINGS:
                processAdminCommissionSettings(chatId, user, text, bot);
                break;
            case ADMIN_VIEW_USER_DETAILS:
                processAdminUserSearch(chatId, user, text, bot);
                break;
            case ADMIN_CREATE_COUPON:
                processCreateCoupon(chatId, user, text, bot);
                break;
        }
    }

    private void processCalculatorSell(Long chatId, User user, String text, MyBot bot) {
        if (text.equals("🔙 Назад")) {
            user.setState(UserState.CALCULATOR_MENU);
            userService.update(user);
            showCalculatorMenu(chatId, user, bot);
            return;
        }

        try {
            double btcAmount = Double.parseDouble(text);

            if (btcAmount <= 0) {
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                        "❌ Количество должно быть больше 0", createCalculatorBackKeyboard()));
                return;
            }

            double btcPrice = cryptoPriceService.getCurrentPrice("BTC", "RUB");
            double rubAmount = btcAmount * btcPrice;

            // Для продажи комиссия рассчитывается от суммы продажи
            double commission = commissionService.calculateCommission(rubAmount);
            double totalReceived = rubAmount - commission; // При продаже комиссия вычитается

            String calculation = String.format("""
                            🧮 Расчет продажи:
                            
                            ₿ Продаете: %.8f BTC
                            💰 Сумма продажи: %.2f ₽
                            💸 Комиссия: %.2f ₽ (%.1f%%)
                            💵 Вы получите: %.2f ₽
                            
                            Курс BTC: %.2f ₽
                            
                            💡 Примечание: при реальной продаже будет учтен VIP-приоритет и купоны
                            """,
                    btcAmount,
                    rubAmount,
                    commission,
                    commissionService.getCommissionPercent(rubAmount),
                    totalReceived,
                    btcPrice
            );

            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, calculation, createCalculatorBackKeyboard()));

        } catch (NumberFormatException e) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                    "❌ Пожалуйста, введите корректное число", createCalculatorBackKeyboard()));
        }
    }

    private void processConfirmingApplication(Long chatId, User user, String text, MyBot bot) {
        Application application = temporaryApplications.get(user.getId());

        if (application == null) {
            processMainMenu(chatId, user, bot);
            return;
        }

        switch (text) {
            case "✅ Подтвердить":
                createApplicationFinal(chatId, user, application, bot);
                break;
            case "❌ Отменить":
                temporaryApplications.remove(user.getId());
                String cancelMessage = "❌ Создание заявки отменено.";
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, cancelMessage, createMainMenuKeyboard(user)));
                user.setState(UserState.MAIN_MENU);
                userService.update(user);
                break;
            case "🔙 Назад":
                user.setState(UserState.APPLYING_COUPON_FINAL);
                userService.update(user);
                processApplyingCouponFinal(chatId, user, "🔙 Назад", bot);
                break;
            default:
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, "❌ Пожалуйста, используйте кнопки", createFinalConfirmationKeyboard()));
        }
    }

    private void createApplicationFinal(Long chatId, User user, Application application, MyBot bot) {
        // Дорасчитываем финальные значения перед сохранением
        if (application.getUserValueGetType() == ValueType.BTC) {
            // Покупка BTC
            double rubAmount = application.getUserValueGiveValue();
            double btcPrice = cryptoPriceService.getCurrentPrice("BTC", "RUB");
            double btcAmount = rubAmount / btcPrice;
            application.setCalculatedGetValue(btcAmount);
        } else {
            // Продажа BTC
            double btcAmount = application.getUserValueGiveValue();
            double btcPrice = cryptoPriceService.getCurrentPrice("BTC", "RUB");
            double rubAmount = btcAmount * btcPrice;
            double commission = commissionService.calculateCommission(rubAmount);
            application.setCalculatedGetValue(rubAmount - commission);
        }

        // Сохраняем заявку сначала без messageId
        application.setStatus(ApplicationStatus.FREE);
        applicationService.create(application);
        temporaryApplications.remove(user.getId());

        // Формируем и отправляем сообщение с заявкой
        String applicationMessage = String.format("""
                        ✅ Заявка на %s создана!
                        📝 ID: %s
                        
                        %s Отдаете: %.8f %s
                        💰 Получаете: %.2f %s
                        %s: %s
                        %s
                        🕰️ Срок действия: до %s
                        
                        Статус: %s
                        
                        📩 Перешлите эту заявку оператору: @cosanostra_support
                        """,
                application.getUserValueGetType() == ValueType.BTC ? "покупки" : "продажи",
                application.getUuid().substring(0, 8),
                application.getUserValueGetType() == ValueType.BTC ? "💸" : "₿",
                application.getUserValueGetType() == ValueType.BTC ?
                        application.getCalculatedGiveValue() : application.getUserValueGiveValue(),
                application.getUserValueGetType() == ValueType.BTC ? "₽" : "BTC",
                application.getUserValueGetType() == ValueType.BTC ?
                        application.getCalculatedGetValue() : application.getCalculatedGetValue(),
                application.getUserValueGetType() == ValueType.BTC ? "BTC" : "₽",
                application.getUserValueGetType() == ValueType.BTC ? "🔐 Кошелек BTC" : "💳 Реквизиты для выплаты",
                application.getWalletAddress(),
                application.getIsVip() ? "👑 VIP-приоритет" : "🔹 Обычный приоритет",
                application.getFormattedExpiresAt(),
                application.getStatus().getDisplayName()
        );

        application.setStatus(ApplicationStatus.FREE);
        applicationService.create(application);
        temporaryApplications.remove(user.getId());

        // Формируем сообщение
        String applicationMessagee = formatApplicationMessage(application);

        // Отправляем сообщение с заявкой и сохраняем его ID в заявке
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, applicationMessagee, createApplicationInlineKeyboard(application.getId()));
        application.setTelegramMessageId(messageId);
        applicationService.update(application);

        user.setState(UserState.MAIN_MENU);
        userService.update(user);
    }

    private String formatApplicationMessage(Application application) {
        String operationType = application.getUserValueGetType() == ValueType.BTC ? "покупки" : "продажи";
        String walletLabel = application.getUserValueGetType() == ValueType.BTC ? "🔐 Кошелек BTC" : "💳 Реквизиты для выплаты";

        return String.format("""
                        ✅ Заявка на %s создана!
                        📝 ID: %s
                        
                        %s Отдаете: %.8f %s
                        💰 Получаете: %.2f %s
                        %s: %s
                        %s
                        🕰️ Срок действия: до %s
                        
                        Статус: %s
                        """,
                operationType,
                application.getUuid().substring(0, 8),
                application.getUserValueGetType() == ValueType.BTC ? "💸" : "₿",
                application.getUserValueGetType() == ValueType.BTC ?
                        application.getCalculatedGiveValue() : application.getUserValueGiveValue(),
                application.getUserValueGetType() == ValueType.BTC ? "₽" : "BTC",
                application.getUserValueGetType() == ValueType.BTC ?
                        application.getCalculatedGetValue() : application.getCalculatedGetValue(),
                application.getUserValueGetType() == ValueType.BTC ? "BTC" : "₽",
                walletLabel,
                application.getWalletAddress(),
                application.getIsVip() ? "👑 VIP-приоритет" : "🔹 Обычный приоритет",
                application.getFormattedExpiresAt(),
                application.getStatus().getDisplayName()
        );
    }

    private void updateApplicationMessage(Long chatId, Application application, MyBot bot) {
        if (application.getTelegramMessageId() == null) return;

        String updatedMessage = formatApplicationMessage(application);
        InlineKeyboardMarkup keyboard = createApplicationInlineKeyboard(application.getId());

        try {
            bot.editMessageText(chatId, application.getTelegramMessageId(), updatedMessage, keyboard);
        } catch (Exception e) {
            System.err.println("Не удалось обновить сообщение заявки: " + e.getMessage());
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

        switch (text) {
            case "🟡 В работу":
                application.setStatus(ApplicationStatus.IN_WORK);
                break;
            case "✅ Выполнено": // ЗАМЕНА
                application.setStatus(ApplicationStatus.COMPLETED);
                updateUserStatistics(application);
                referralService.processReferralReward(application);
                // Удаляем сообщение у пользователя
                if (application.getTelegramMessageId() != null) {
                    bot.deleteMessage(application.getUser().getTelegramId(), application.getTelegramMessageId());
                }
                break;
            case "🔴 Отменить":
                application.setStatus(ApplicationStatus.CANCELLED);
                // Удаляем сообщение у пользователя
                if (application.getTelegramMessageId() != null) {
                    bot.deleteMessage(application.getUser().getTelegramId(), application.getTelegramMessageId());
                }
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
            case "🔙 Главное меню":
                user.setState(UserState.MAIN_MENU);
                userService.update(user);
                showMainMenu(chatId, user, bot);
                return;
            default:
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                        "❌ Пожалуйста, используйте кнопки", createAdminApplicationActionsKeyboard()));
                return;
        }

        applicationService.update(application);

        String message = String.format("✅ Статус заявки #%d изменен на: %s",
                applicationId, application.getStatus().getDisplayName());
        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createBackToAdminKeyboard()));

        user.setState(UserState.ADMIN_VIEWING_ALL_APPLICATIONS);
        userService.update(user);
    }

    private void updateUserStatistics(Application application) {
        User user = application.getUser();

        if (application.getStatus() == ApplicationStatus.COMPLETED) {
            double cashback = 0.0;

            if (application.getUserValueGetType() == ValueType.BTC) {
                // Покупка BTC - начисляем 3% кешбека от суммы
                user.setCompletedBuyApplications(user.getCompletedBuyApplications() + 1);
                user.setTotalBuyAmount(user.getTotalBuyAmount() + application.getCalculatedGiveValue());
                cashback = application.getCalculatedGiveValue() * 0.03;
            } else {
                // Продажа BTC - начисляем 3% кешбека от суммы
                user.setCompletedSellApplications(user.getCompletedSellApplications() + 1);
                user.setTotalSellAmount(user.getTotalSellAmount() + application.getCalculatedGetValue());
                cashback = application.getCalculatedGetValue() * 0.03;
            }

            user.setBonusBalance(user.getBonusBalance() + cashback);
            user.setTotalApplications(user.getTotalApplications() + 1);
            userService.update(user);
        }
    }

    private void processAdminCommissionSettings(Long chatId, User user, String text, MyBot bot) {
        if (text.equals("🔙 Назад")) {
            user.setState(UserState.ADMIN_MAIN_MENU);
            userService.update(user);
            showAdminMainMenu(chatId, bot);
            return;
        }

        try {
            String[] parts = text.split(" ");
            if (parts.length == 2) {
                String rangeStr = parts[0];
                double percent = Double.parseDouble(parts[1]);

                if (rangeStr.contains("-")) {
                    String[] rangeParts = rangeStr.split("-");
                    double min = Double.parseDouble(rangeParts[0]);
                    double max = Double.parseDouble(rangeParts[1]);
                    commissionConfig.updateCommissionRange(min, percent);

                    String message = String.format("✅ Комиссия обновлена!\n\nДиапазон: %.0f-%.0f ₽\nКомиссия: %.1f%%",
                            min, max, percent);
                    lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createBackToAdminKeyboard()));
                } else {
                    double min = Double.parseDouble(rangeStr);
                    commissionConfig.updateCommissionRange(min, percent);

                    String message = String.format("✅ Комиссия обновлена!\n\nОт %.0f ₽\nКомиссия: %.1f%%",
                            min, percent);
                    lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createBackToAdminKeyboard()));
                }
                return;
            }
        } catch (Exception e) {
            // Не удалось распарсить
        }

        String message = "💰 Управление комиссиями\n\n" +
                "Текущие настройки:\n" +
                "• 1000-1999 ₽: " + commissionConfig.getCommissionPercent(1000) + "%\n" +
                "• 2000-2999 ₽: " + commissionConfig.getCommissionPercent(2000) + "%\n" +
                "• 3000-4999 ₽: " + commissionConfig.getCommissionPercent(3000) + "%\n" +
                "• 5000+ ₽: " + commissionConfig.getCommissionPercent(5000) + "%\n\n" +
                "Для изменения введите:\n" +
                "• Для диапазона: 1000-1999 5\n" +
                "• Для минимальной суммы: 5000 2\n\n" +
                "Используйте '🔙 Назад' для возврата";

        ReplyKeyboardMarkup keyboard = createBackToAdminKeyboard();
        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, keyboard));
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
        String message = "🔐 Для продолжения пройдите проверку безопасности\n\n" +
                "Выберите смайлик: \"" + challenge.getCorrectEmoji() + "\"";

        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, keyboard));
    }

    private void processCallback(Update update, MyBot bot) {
        String callbackData = update.getCallbackQuery().getData();
        Long chatId = update.getCallbackQuery().getMessage().getChatId();
        Long telegramId = update.getCallbackQuery().getFrom().getId();
        String callbackQueryId = update.getCallbackQuery().getId();
        Integer messageId = update.getCallbackQuery().getMessage().getMessageId();

        User user = userService.findByTelegramId(telegramId);
        if (user == null) {
            return;
        }

        if (callbackData.startsWith("captcha_")) {
            processCaptchaSelection(chatId, user, callbackData, bot, callbackQueryId, messageId);
        } else if (callbackData.startsWith("cancel_app_")) {
            processCancelApplicationCallback(chatId, user, callbackData, bot, callbackQueryId);
        } else if (callbackData.startsWith("queue_app_")) {
            processQueuePositionCallback(chatId, user, callbackData, bot, callbackQueryId);
        } else if (callbackData.startsWith("inline_")) {
            processInlineButton(chatId, user, callbackData, bot, callbackQueryId);
        }
    }
    private void processInlineButton(Long chatId, User user, String callbackData, MyBot bot, String callbackQueryId) {
        // Удаляем предыдущее сообщение бота
        deletePreviousBotMessage(chatId, bot);

        // Отвечаем на callback
        bot.answerCallbackQuery(callbackQueryId, "Обработка...");

        switch (callbackData) {
            case "inline_buy":
                user.setState(UserState.BUY_MENU);
                userService.update(user);
                showBuyMenu(chatId, bot);
                break;
            case "inline_sell":
                user.setState(UserState.SELL_MENU);
                userService.update(user);
                showSellMenu(chatId, bot);
                break;
            case "inline_commissions":
                showCommissionInfo(chatId, user, bot);
                break;
            case "inline_other":
                user.setState(UserState.OTHER_MENU);
                userService.update(user);
                showOtherMenu(chatId, user, bot);
                break;
            case "inline_referral":
                if (user.getUsedReferralCode() != null) {
                    lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                            "❌ Вы уже использовали реферальный код.", createMainMenuKeyboard(user)));
                    return;
                }
                user.setState(UserState.ENTERING_REFERRAL_CODE);
                userService.update(user);
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                        "Введите реферальный код:", createBackKeyboard()));
                break;
        }
    }

    private void processCancelApplicationCallback(Long chatId, User user, String callbackData, MyBot bot, String callbackQueryId) {
        try {
            Long applicationId = Long.parseLong(callbackData.replace("cancel_app_", ""));
            Application application = applicationService.find(applicationId);

            if (application == null || !application.getUser().getId().equals(user.getId())) {
                bot.answerCallbackQuery(callbackQueryId, "❌ Заявка не найдена");
                return;
            }

            if (application.getStatus() != ApplicationStatus.FREE && application.getStatus() != ApplicationStatus.IN_WORK) {
                bot.answerCallbackQuery(callbackQueryId, "❌ Нельзя отменить заявку с текущим статусом");
                return;
            }

            application.setStatus(ApplicationStatus.CANCELLED);
            applicationService.update(application);

            // УДАЛЯЕМ сообщение с заявкой у пользователя
            if (application.getTelegramMessageId() != null) {
                bot.deleteMessage(chatId, application.getTelegramMessageId());
            }

            bot.answerCallbackQuery(callbackQueryId, "✅ Заявка отменена");

            // Отправляем уведомление об отмене
            String cancelMessage = "❌ Заявка #" + applicationId + " отменена.";
            bot.sendMessage(chatId, cancelMessage);

        } catch (Exception e) {
            e.printStackTrace();
            bot.answerCallbackQuery(callbackQueryId, "❌ Ошибка при отмене заявки");
        }
    }


    // Обработка запроса номера в очереди
    private void processQueuePositionCallback(Long chatId, User user, String callbackData, MyBot bot, String callbackQueryId) {
        try {
            Long applicationId = Long.parseLong(callbackData.replace("queue_app_", ""));
            Application application = applicationService.find(applicationId);

            if (application == null || !application.getUser().getId().equals(user.getId())) {
                bot.answerCallbackQuery(callbackQueryId, "❌ Заявка не найдена");
                return;
            }

            int queuePosition = calculateQueuePosition(application);
            String message = "📊 Ваша заявка находится на " + queuePosition + " месте в очереди";

            bot.answerCallbackQuery(callbackQueryId, message);

        } catch (Exception e) {
            e.printStackTrace();
            bot.answerCallbackQuery(callbackQueryId, "❌ Ошибка при получении номера в очереди");
        }
    }


    // Расчет позиции в очереди
    private int calculateQueuePosition(Application application) {
        List<Application> activeApplications = applicationService.findActiveApplications();

        // Сортируем: VIP сначала, затем по времени создания
        List<Application> sortedApplications = activeApplications.stream()
                .sorted(Comparator.comparing(Application::getIsVip).reversed()
                        .thenComparing(Application::getCreatedAt))
                .collect(Collectors.toList());

        for (int i = 0; i < sortedApplications.size(); i++) {
            if (sortedApplications.get(i).getId().equals(application.getId())) {
                return i + 1;
            }
        }
        return -1;
    }


    private void processCaptchaSelection(Long chatId, User user, String callbackData, MyBot bot, String callbackQueryId, Integer messageId) {
        String selectedEmoji = callbackData.replace("captcha_", "");

        if (captchaService.verifyCaptcha(user.getId(), selectedEmoji)) {
            user.setState(UserState.MAIN_MENU);
            userService.update(user);

            // Удаляем сообщение с капчей
            bot.deleteMessage(chatId, messageId);

            // Отвечаем на callback
            bot.answerCallbackQuery(callbackQueryId, "✅ Проверка пройдена!");

            showMainMenu(chatId, user, bot);
        } else {
            // Показываем новую капчу при неверном выборе
            bot.answerCallbackQuery(callbackQueryId, "❌ Неверный выбор, попробуйте снова");
            showCaptcha(chatId, user, bot);
        }
    }

    public void updateApplicationStatus(Long applicationId, ApplicationStatus newStatus, MyBot bot) {
        Application application = applicationService.find(applicationId);
        if (application == null) return;

        application.setStatus(newStatus);
        applicationService.update(application);

        // Обновляем сообщение у пользователя
        if (application.getTelegramMessageId() != null) {
            String updatedMessage = formatApplicationMessage(application);
            InlineKeyboardMarkup keyboard = createApplicationInlineKeyboard(application.getId());

            try {
                bot.editMessageText(application.getUser().getTelegramId(),
                        application.getTelegramMessageId(),
                        updatedMessage,
                        keyboard);
            } catch (Exception e) {
                System.err.println("Не удалось обновить сообщение заявки: " + e.getMessage());
            }
        }
    }

    private void showMainMenu(Long chatId, User user, MyBot bot) {
        String message = """
            💼 Добро пожаловать в обменник — 𝐂𝐎𝐒𝐀 𝐍𝐎𝐒𝐓𝐑𝐀 𝐂𝐇𝐀𝐍𝐆𝐄
            🚀 Быстрый и надёжный обмен RUB → BTC
            ⚖️ Честные курсы, без задержек и скрытых комиссий.
            💸 БОНУС: после каждой операции получаете 3% кешбэк на свой баланс!
            
            📲 Как всё работает: 
            1️⃣ Нажмите 💵 Купить или 💰 Продать 
            2️⃣ Введите нужную сумму 🪙 
            3️⃣ Укажите свой кошелёк 🔐
            4️⃣ Выберите приоритет (🔹обычный / 👑 VIP) 
            5️⃣ Подтвердите заявку ✅ 
            6️⃣ Если готовы оплачивать — перешлите заявку оператору ☎️
            
            ⚙️ Дополнительная информация: 
            👑 VIP-приоритет — всего 300₽, заявка проходит мгновенно
            📊 Загруженность сети BTC: низкая 🚥 
            🕒 Время подтверждения: 5–20 минут 
            💬 Отзывы клиентов: [@cosanostra_feedback] 
            🧰 Техподдержка 24/7: всегда онлайн, решим любой вопрос 🔧
            
            COSA NOSTRA CHANGE — тут уважают тех, кто ценит скорость, честность и результат. ⚡
            """;

        // Отправляем только с inline-клавиатурой
        InlineKeyboardMarkup inlineKeyboard = createMainMenuInlineKeyboard(user);
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);
    }

    private void processBuyConfirmation(Long chatId, User user, double rubAmount, double btcAmount,
                                        String inputType, String outputType, MyBot bot) {

        if (rubAmount < 1000) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                    "❌ Минимальная сумма заявки 1000 рублей", createAmountInputKeyboard()));
            return;
        }

        double commission = commissionService.calculateCommission(rubAmount);
        double totalAmount = commissionService.calculateTotalWithCommission(rubAmount);

        Application application = new Application();
        application.setUser(user);
        application.setUserValueGetType(ValueType.BTC);
        application.setUserValueGiveType(ValueType.RUB);
        application.setUserValueGetValue(btcAmount);
        application.setUserValueGiveValue(totalAmount);
        application.setCalculatedGetValue(btcAmount);
        application.setCalculatedGiveValue(totalAmount);
        application.setTitle("Покупка BTC за RUB");
        application.setStatus(ApplicationStatus.FREE);

        temporaryApplications.put(user.getId(), application);

        String calculationMessage = String.format("""
                    💰 Расчет операции:
                    
                    💸 Сумма: %s ₽
                    💰 Комиссия: %s ₽ (%.1f%%)
                    💸 Итого к оплате: %s ₽
                    ₿ Вы получите: %s BTC
                    
                    Хотите добавить 👑 VIP-приоритет за 300₽?
                    Ваша заявка будет обрабатываться в первую очередь!
                    """,
                formatRubAmount(rubAmount),
                formatRubAmount(commission),
                commissionService.getCommissionPercent(rubAmount),
                formatRubAmount(totalAmount),
                formatBtcAmount(btcAmount));

        ReplyKeyboardMarkup keyboard = createVipConfirmationKeyboard();
        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, calculationMessage, keyboard));

        user.setState(UserState.CONFIRMING_VIP);
        userService.update(user);
    }


    private void processVipConfirmation(Long chatId, User user, String text, MyBot bot) {
        Application application = temporaryApplications.get(user.getId());

        if (application == null) {
            processMainMenu(chatId, user, bot);
            return;
        }

        switch (text) {
            case "👑 Да, добавить VIP":
                application.setIsVip(true);
                break;
            case "🔹 Нет, обычный приоритет":
                application.setIsVip(false);
                break;
            case "🔙 Назад":
                user.setState(UserState.ENTERING_WALLET);
                userService.update(user);
                processEnteringWallet(chatId, user, "🔙 Назад", bot);
                return;
            case "🔙 Главное меню":
                processMainMenu(chatId, user, bot);
                return;
            default:
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                        "❌ Пожалуйста, выберите вариант приоритета", createVipConfirmationKeyboard()));
                return;
        }

        // Переходим к применению купонов
        String message = """
                🎫 Хотите применить купон для скидки?
                
                Если у вас есть купон, вы можете применить его сейчас.
                """;

        ReplyKeyboardMarkup keyboard = createCouponApplicationKeyboard();
        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, keyboard));

        user.setState(UserState.APPLYING_COUPON_FINAL);
        userService.update(user);
    }

    private void processApplyingCouponFinal(Long chatId, User user, String text, MyBot bot) {
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
                showFinalApplicationConfirmation(chatId, user, application, bot);
                break;
            case "🔙 Назад":
                user.setState(UserState.CONFIRMING_VIP);
                userService.update(user);
                processVipConfirmation(chatId, user, "🔙 Назад", bot);
                break;
            case "🔙 Главное меню":
                processMainMenu(chatId, user, bot);
                break;
            default:
                processCouponCodeFinal(chatId, user, application, text, bot);
        }
    }

    private void processCouponCodeFinal(Long chatId, User user, Application application, String couponCode, MyBot bot) {
        try {
            Coupon coupon = couponService.findValidCoupon(couponCode, user)
                    .orElseThrow(() -> new IllegalArgumentException("Недействительный купон"));

            application.setAppliedCoupon(coupon);
            showFinalApplicationConfirmation(chatId, user, application, bot);

        } catch (IllegalArgumentException e) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                    "❌ " + e.getMessage() + "\n\nПопробуйте другой код или нажмите 'Пропустить'",
                    createCouponApplicationKeyboard()));
        }
    }

    private void showFinalApplicationConfirmation(Long chatId, User user, Application application, MyBot bot) {
        // Рассчитываем финальную стоимость в зависимости от типа операции
        double calculatedAmount;
        double finalAmount;
        String currencyFrom, currencyTo;

        if (application.getUserValueGetType() == ValueType.BTC) {
            // Покупка BTC
            double rubAmount = application.getUserValueGiveValue();
            double commission = commissionService.calculateCommission(rubAmount);
            calculatedAmount = commissionService.calculateTotalWithCommission(rubAmount);
            double btcPrice = cryptoPriceService.getCurrentPrice("BTC", "RUB");
            double btcAmount = rubAmount / btcPrice;

            currencyFrom = "₽";
            currencyTo = "BTC";
            finalAmount = btcAmount;

        } else {
            // Продажа BTC
            double btcAmount = application.getUserValueGiveValue();
            double btcPrice = cryptoPriceService.getCurrentPrice("BTC", "RUB");
            double rubAmount = btcAmount * btcPrice;
            double commission = commissionService.calculateCommission(rubAmount);
            calculatedAmount = rubAmount - commission; // При продаже комиссия вычитается

            currencyFrom = "BTC";
            currencyTo = "₽";
            finalAmount = calculatedAmount;
        }

        // Добавляем VIP стоимость если выбрано
        if (application.getIsVip()) {
            calculatedAmount += 300;
        }

        // Применяем купон если есть
        if (application.getAppliedCoupon() != null) {
            calculatedAmount = couponService.applyCoupon(calculatedAmount, application.getAppliedCoupon());
        }

        // Обновляем заявку
        if (application.getUserValueGetType() == ValueType.BTC) {
            application.setCalculatedGiveValue(calculatedAmount);
            application.setCalculatedGetValue(finalAmount);
            application.setUserValueGetValue(finalAmount);
        } else {
            application.setCalculatedGiveValue(application.getUserValueGiveValue()); // BTC amount
            application.setCalculatedGetValue(calculatedAmount); // RUB amount after commission
            application.setUserValueGetValue(calculatedAmount);
        }

        String operationType = application.getUserValueGetType() == ValueType.BTC ? "покупки" : "продажи";
        String walletLabel = application.getUserValueGetType() == ValueType.BTC ? "🔐 Кошелек BTC" : "💳 Реквизиты для выплаты";

        String applicationDetails = String.format("""
                        📋 Ваша заявка на %s:
                        
                        %s Отдаете: %.8f %s
                        💰 Получаете: %.2f %s
                        💸 Комиссия: учтена в расчете
                        %s
                        %s
                        💵 Итоговая сумма: %.2f %s
                        %s: %s
                        🕰️ Срок действия: 5 минут
                        
                        Подтверждаете создание заявки?
                        """,
                operationType,
                application.getUserValueGetType() == ValueType.BTC ? "💸" : "₿",
                application.getUserValueGetType() == ValueType.BTC ?
                        application.getUserValueGiveValue() : application.getUserValueGiveValue(),
                application.getUserValueGetType() == ValueType.BTC ? "₽" : "BTC",
                application.getUserValueGetType() == ValueType.BTC ?
                        finalAmount : calculatedAmount,
                application.getUserValueGetType() == ValueType.BTC ? "BTC" : "₽",
                application.getIsVip() ? "👑 VIP-приоритет: +300 ₽" : "🔹 Приоритет: обычный",
                application.getAppliedCoupon() != null ?
                        String.format("🎫 Купон: %s", application.getAppliedCoupon().getCode()) : "",
                application.getUserValueGetType() == ValueType.BTC ?
                        calculatedAmount : calculatedAmount,
                application.getUserValueGetType() == ValueType.BTC ? "₽" : "₽",
                walletLabel,
                application.getWalletAddress()
        );

        ReplyKeyboardMarkup keyboard = createFinalConfirmationKeyboard();
        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, applicationDetails, keyboard));

        user.setState(UserState.CONFIRMING_APPLICATION);
        userService.update(user);
    }


    private void processEnteringWallet(Long chatId, User user, String text, MyBot bot) {
        Application application = temporaryApplications.get(user.getId());

        if (application == null) {
            processMainMenu(chatId, user, bot);
            return;
        }

        if (text.equals("🔙 Назад")) {
            // Определяем, из какого меню пришли
            if (application.getUserValueGetType() == ValueType.BTC) {
                user.setState(UserState.BUY_MENU);
                showBuyMenu(chatId, bot);
            } else {
                user.setState(UserState.SELL_MENU);
                showSellMenu(chatId, bot);
            }
            userService.update(user);
            return;
        }

        if (text.equals("🔙 Главное меню")) {
            processMainMenu(chatId, user, bot);
            return;
        }

        // Для продажи валидация отличается (банковские реквизиты)
//        if (application.getUserValueGetType() == ValueType.RUB) {
//            // Валидация банковских реквизитов (упрощенная)
//            if (text.length() < 16 && text.length() > 20) {
//                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
//                        "❌ Неверный формат банковских реквизитов. Пожалуйста, проверьте и введите снова:",
//                        createBackKeyboard()));
//                return;
//            }
//        } else {
//            if (text.length() < 26 || text.length() > 35) {
//                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
//                        "❌ Неверный формат Bitcoin-адреса. Пожалуйста, проверьте и введите снова:",
//                        createBackKeyboard()));
//                return;
//            }
//        }

        application.setWalletAddress(text);

        // Переходим к выбору VIP (одинаково для покупки и продажи)
        String message = """
                💎 Хотите добавить 👑 VIP-приоритет за 300₽?
                
                👑 VIP-приоритет обеспечивает:
                • Первоочередную обработку
                • Ускоренное выполнение  
                • Приоритет в очереди
                • Личного оператора
                
                Выберите вариант:
                """;

        ReplyKeyboardMarkup keyboard = createVipConfirmationKeyboard();
        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, keyboard));

        user.setState(UserState.CONFIRMING_VIP);
        userService.update(user);
    }


    private void processMainMenuCommand(Long chatId, User user, String text, MyBot bot) {
        // Проверяем специальные команды
        if (text.startsWith("❌ Отменить заявку #")) {
            try {
                Long applicationId = Long.parseLong(text.replace("❌ Отменить заявку #", "").trim());
                cancelUserApplication(chatId, user, applicationId, bot);
                return;
            } catch (NumberFormatException e) {
                // Продолжаем
            }
        }

        if (text.equals("📞 Написать оператору")) {
            String message = "📞 Связь с оператором: @cosanostra_support";
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createMainMenuKeyboard(user)));
            return;
        }

        // Основные кнопки главного меню
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
            case "💳 Комиссии":
                showCommissionInfo(chatId, user, bot);
                break;
            case "🎫 Ввести реф. код":
                if (user.getUsedReferralCode() != null) {
                    lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                            "❌ Вы уже использовали реферальный код.", createMainMenuKeyboard(user)));
                    return;
                }
                user.setState(UserState.ENTERING_REFERRAL_CODE);
                userService.update(user);
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                        "Введите реферальный код:", createBackKeyboard()));
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
            case "💎 Главное меню":
                deletePreviousBotMessage(chatId, bot);
                showMainMenu(chatId, user, bot);
                break;
            default:
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                        "❌ Пожалуйста, используйте кнопки меню", createMainMenuKeyboard(user)));
        }
    }


    private void showCommissionInfo(Long chatId, User user, MyBot bot) {
        String message = String.format("""
                        💰 Актуальные комиссии:
                        
                        • 1000-1999 ₽: %.1f%%
                        • 2000-2999 ₽: %.1f%%
                        • 3000-4999 ₽: %.1f%%
                        • 5000+ ₽: %.1f%%
                        
                        💡 Комиссия рассчитывается автоматически при создании заявки.
                        💸 VIP-приоритет: +300 ₽ к сумме заявки
                        
                        👑 VIP-приоритет обеспечивает:
                        • Первоочередную обработку
                        • Ускоренное выполнение
                        • Приоритет в очереди
                        """,
                commissionConfig.getCommissionPercent(1000),
                commissionConfig.getCommissionPercent(2000),
                commissionConfig.getCommissionPercent(3000),
                commissionConfig.getCommissionPercent(5000)
        );

        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createMainMenuKeyboard(user)));
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
        } else if ("🔙 Главное меню".equals(text)) {
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
        } else if ("🔙 Главное меню".equals(text)) {
            processMainMenu(chatId, user, bot);
        } else {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, "❌ Пожалуйста, используйте кнопки", createSellMenuKeyboard()));
        }
    }

    private void processEnteringSellAmount(Long chatId, User user, String text, MyBot bot) {
        switch (text) {
            case "🔙 Назад":
                user.setState(UserState.SELL_MENU);
                userService.update(user);
                showSellMenu(chatId, bot);
                break;
            case "🔙 Главное меню":
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

                    // Создаем временную заявку для продажи
                    Application application = new Application();
                    application.setUser(user);
                    application.setUserValueGetType(ValueType.RUB);
                    application.setUserValueGiveType(ValueType.BTC);
                    application.setUserValueGiveValue(btcAmount);
                    application.setTitle("Продажа BTC за RUB");
                    application.setStatus(ApplicationStatus.FREE);

                    temporaryApplications.put(user.getId(), application);

                    // Переходим к вводу кошелька (для получения RUB)
                    String message = "🔐 Введите номер карты или счет для получения рублей:";
                    ReplyKeyboardMarkup keyboard = createBackKeyboard();
                    lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, keyboard));

                    user.setState(UserState.ENTERING_WALLET);
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
        
        🆔 ID пользователя: %d
        📞 Telegram ID: %d
        👤 Имя: %s
        📱 Username: @%s
        
        💰 Бонусный баланс: %s
        
        📊 Статистика заявок:
        ✅ Успешно проведено: %d
        💸 Потрачено: %s
        💰 Получено: %s
        📈 Всего заявок: %d
        
        📈 Реферальная система:
        👥 Приглашено: %d
        💰 Заработано: %s
        """,
                user.getId(),
                user.getTelegramId(),
                user.getFirstName() + (user.getLastName() != null ? " " + user.getLastName() : ""),
                user.getUsername() != null ? user.getUsername() : "не указан",
                formatRubAmount(user.getBonusBalance()),
                user.getCompletedBuyApplications() + user.getCompletedSellApplications(),
                formatRubAmount(user.getTotalBuyAmount()),
                formatRubAmount(user.getTotalSellAmount()),
                user.getTotalApplications(),
                user.getReferralCount(),
                formatRubAmount(user.getReferralEarnings())
        );

        // Отправляем только с inline-клавиатурой
        InlineKeyboardMarkup inlineKeyboard = createProfileInlineKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);
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
            case "🔙 Назад":
                if ("BUY_RUB".equals(currentOperation.get(user.getId())) ||
                        "BUY_BTC".equals(currentOperation.get(user.getId()))) {
                    user.setState(UserState.BUY_MENU);
                } else {
                    user.setState(UserState.SELL_MENU);
                }
                userService.update(user);
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                        "Введите сумму:", createAmountInputKeyboard()));
                break;
            case "🔙 Главное меню":
                processMainMenu(chatId, user, bot);
                break;
            default:
                processCouponCode(chatId, user, application, text, bot);
        }
    }

    private void processCouponCode(Long chatId, User user, Application application, String couponCode, MyBot bot) {
        try {
            Coupon coupon = couponService.findValidCoupon(couponCode, user)
                    .orElseThrow(() -> new IllegalArgumentException("Недействительный купон"));

            double originalAmount = application.getCalculatedGiveValue();
            double discountedAmount = couponService.applyCoupon(originalAmount, coupon);

            application.setAppliedCoupon(coupon);
            application.setFinalAmountAfterDiscount(discountedAmount);
            application.setStatus(ApplicationStatus.FREE);

            applicationService.create(application);
            temporaryApplications.remove(user.getId());

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
        application.setStatus(ApplicationStatus.FREE);
        applicationService.create(application);
        temporaryApplications.remove(user.getId());

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

        // Сортируем по дате создания (новые сначала) и берем только последние 3
        List<Application> recentApplications = applications.stream()
                .sorted((a1, a2) -> a2.getCreatedAt().compareTo(a1.getCreatedAt()))
                .limit(3)
                .collect(Collectors.toList());

        if (recentApplications.isEmpty()) {
            String message = "📭 У вас пока нет заявок.\nСоздайте первую с помощью кнопки '💰 Купить' или '💸 Продать'";
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createMainMenuKeyboard(user)));
        } else {
            StringBuilder response = new StringBuilder("📋 Ваши последние заявки:\n\n");

            for (int i = 0; i < recentApplications.size(); i++) {
                Application app = recentApplications.get(i);
                response.append(String.format("""
                                🆔 Заявка #%d
                                📊 Статус: %s
                                💰 Тип: %s
                                💸 Сумма: %.2f ₽
                                ₿ Bitcoin: %.8f BTC
                                📅 Создана: %s
                                """,
                        app.getId(),
                        app.getStatus().getDisplayName(),
                        app.getTitle(),
                        app.getCalculatedGiveValue(),
                        app.getCalculatedGetValue(),
                        app.getCreatedAt().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
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
            case "🔙 Назад":
                user.setState(UserState.BUY_MENU);
                userService.update(user);
                showBuyMenu(chatId, bot);
                break;
            case "🔙 Главное меню":
                processMainMenu(chatId, user, bot);
                break;
            default:
                try {
                    double rubAmount = Double.parseDouble(text);

                    if (rubAmount < 1000) {
                        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                                "❌ Минимальная сумма заявки 1000 рублей", createAmountInputKeyboard()));
                        return;
                    }

                    // Создаем временную заявку
                    Application application = new Application();
                    application.setUser(user);
                    application.setUserValueGetType(ValueType.BTC);
                    application.setUserValueGiveType(ValueType.RUB);
                    application.setUserValueGiveValue(rubAmount);
                    application.setTitle("Покупка BTC за RUB");
                    application.setStatus(ApplicationStatus.FREE);

                    temporaryApplications.put(user.getId(), application);

                    // Переходим к вводу кошелька
                    String message = "🔐 Теперь введите адрес Bitcoin-кошелька, на который поступит крипта:";
                    ReplyKeyboardMarkup keyboard = createBackKeyboard();
                    lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, keyboard));

                    user.setState(UserState.ENTERING_WALLET);
                    userService.update(user);

                } catch (NumberFormatException e) {
                    lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                            "❌ Пожалуйста, введите корректное число", createAmountInputKeyboard()));
                }
        }
    }

    private void processEnteringBuyAmountBtc(Long chatId, User user, String text, MyBot bot) {
        switch (text) {
            case "🔙 Назад":
                user.setState(UserState.BUY_MENU);
                userService.update(user);
                showBuyMenu(chatId, bot);
                break;
            case "🔙 Главное меню":
                processMainMenu(chatId, user, bot);
                break;
            default:
                try {
                    double btcAmount = Double.parseDouble(text);
                    if (btcAmount <= 0) {
                        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, "❌ Количество должно быть больше 0", createAmountInputKeyboard()));
                        return;
                    }

                    double btcPrice = cryptoPriceService.getCurrentPrice("BTC", "RUB");
                    double rubAmount = btcAmount * btcPrice;

                    processBuyConfirmation(chatId, user, rubAmount, btcAmount, "BTC", "RUB", bot);

                } catch (NumberFormatException e) {
                    lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, "❌ Пожалуйста, введите корректное число", createAmountInputKeyboard()));
                }
        }
    }


    private void processMainMenu(Long chatId, User user, MyBot bot) {
        user.setState(UserState.MAIN_MENU);
        userService.update(user);
        showMainMenu(chatId, user, bot);
    }

    private void showAllApplications(Long chatId, User user, MyBot bot) {
        List<Application> allApplications = applicationService.findAll();

        if (allApplications.isEmpty()) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                    "📭 Нет заявок в системе", createAdminMainMenuKeyboard()));
            return;
        }

        StringBuilder message = new StringBuilder("📋 Все заявки:\n\n");

        for (int i = 0; i < Math.min(allApplications.size(), 10); i++) {
            Application app = allApplications.get(i);
            String userInfo = String.format("@%s (ID: %d)",
                    app.getUser().getUsername() != null ? app.getUser().getUsername() : "нет_username",
                    app.getUser().getId());

            message.append(String.format("""
                            🆔 #%d | %s
                            👤 %s
                            %s
                            💰 %.2f ₽ | %s
                            📊 %s
                            🕒 %s
                            --------------------
                            """,
                    app.getId(),
                    app.getTitle(),
                    app.getUser().getFirstName(),
                    userInfo, // ДОБАВЛЕНО
                    app.getCalculatedGiveValue(),
                    app.getIsVip() ? "👑 VIP" : "🔹 Обычная",
                    app.getStatus().getDisplayName(),
                    app.getCreatedAt().format(DateTimeFormatter.ofPattern("dd.MM.yy HH:mm"))
            ));
        }

        if (allApplications.size() > 10) {
            message.append("\n⚠️ Показано 10 из " + allApplications.size() + " заявок");
        }

        message.append("\n🔍 Для фильтрации введите команду:\n");
        message.append("• /filter_status [статус] - по статусу\n");
        message.append("• /filter_user [username] - по пользователю\n");
        message.append("• /filter_amount [сумма] - по сумме\n");

        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message.toString(), createAdminApplicationsKeyboard()));
    }


    private void showActiveApplications(Long chatId, User user, MyBot bot) {
        List<Application> activeApplications = applicationService.findActiveApplications();

        List<Application> sortedApplications = activeApplications.stream()
                .sorted(Comparator.comparing(Application::getIsVip).reversed()
                        .thenComparing(Application::getCreatedAt))
                .collect(Collectors.toList());

        if (sortedApplications.isEmpty()) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                    "📭 Нет активных заявок", createAdminMainMenuKeyboard()));
            return;
        }

        StringBuilder message = new StringBuilder("📊 Активные заявки (отсортированы по приоритету):\n\n");

        for (int i = 0; i < sortedApplications.size(); i++) {
            Application app = sortedApplications.get(i);
            String userInfo = String.format("@%s (ID: %d)",
                    app.getUser().getUsername() != null ? app.getUser().getUsername() : "нет_username",
                    app.getUser().getId());

            message.append(String.format("""
                            %d. %s #%d
                            👤 %s
                            %s
                            💰 %.2f ₽
                            🕒 %s
                            --------------------
                            """,
                    i + 1,
                    app.getIsVip() ? "👑" : "🔹",
                    app.getId(),
                    app.getUser().getFirstName(),
                    userInfo, // ДОБАВЛЕНО
                    app.getCalculatedGiveValue(),
                    app.getCreatedAt().format(DateTimeFormatter.ofPattern("dd.MM.yy HH:mm"))
            ));
        }

        message.append("\nВведите номер заявки из списка для управления:");

        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message.toString(), createBackToAdminKeyboard()));
    }

    // Обработка выбора заявки по номеру в очереди
    private void processAdminActiveApplicationsSelection(Long chatId, User user, String text, MyBot bot) {
        try {
            int queueNumber = Integer.parseInt(text);
            List<Application> activeApplications = applicationService.findActiveApplications();

            List<Application> sortedApplications = activeApplications.stream()
                    .sorted(Comparator.comparing(Application::getIsVip).reversed()
                            .thenComparing(Application::getCreatedAt))
                    .collect(Collectors.toList());

            if (queueNumber < 1 || queueNumber > sortedApplications.size()) {
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                        "❌ Неверный номер заявки", createBackToAdminKeyboard()));
                return;
            }

            Application application = sortedApplications.get(queueNumber - 1);
            selectedApplication.put(user.getId(), application.getId());
            user.setState(UserState.ADMIN_VIEWING_APPLICATION_DETAILS);
            userService.update(user);
            showAdminApplicationDetails(chatId, user, application, bot);

        } catch (NumberFormatException e) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                    "❌ Введите корректный номер", createBackToAdminKeyboard()));
        }
    }

    // Обработка "Следующая заявка"
    private void processNextApplication(Long chatId, User user, MyBot bot) {
        List<Application> activeApplications = applicationService.findActiveApplications();

        if (activeApplications.isEmpty()) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                    "📭 Нет активных заявок", createAdminMainMenuKeyboard()));
            return;
        }

        // Берем первую заявку из отсортированного списка
        Application nextApplication = activeApplications.stream()
                .sorted(Comparator.comparing(Application::getIsVip).reversed()
                        .thenComparing(Application::getCreatedAt))
                .findFirst()
                .orElse(null);

        if (nextApplication == null) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                    "❌ Ошибка при поиске заявки", createAdminMainMenuKeyboard()));
            return;
        }

        selectedApplication.put(user.getId(), nextApplication.getId());
        user.setState(UserState.ADMIN_VIEWING_APPLICATION_DETAILS);
        userService.update(user);
        showAdminApplicationDetails(chatId, user, nextApplication, bot);
    }

    // Поиск пользователя
    private void processAdminUserSearch(Long chatId, User user, String text, MyBot bot) {
        if (text.equals("🔙 Назад") || text.equals("🔙 Главное меню")) {
            user.setState(UserState.ADMIN_MAIN_MENU);
            userService.update(user);
            showAdminMainMenu(chatId, bot);
            return;
        }

        User foundUser = null;

        // Пробуем найти по username
        if (!text.startsWith("@")) {
            // Если не начинается с @, пробуем как username без @
            foundUser = userService.findByUsername(text);
        } else {
            // Если начинается с @, убираем его
            foundUser = userService.findByUsername(text.substring(1));
        }

        // Пробуем найти по ID
        if (foundUser == null) {
            try {
                Long userId = Long.parseLong(text);
                foundUser = userService.find(userId);
            } catch (NumberFormatException e) {
                // Не число
            }
        }

        // Пробуем найти по Telegram ID
        if (foundUser == null) {
            try {
                Long telegramId = Long.parseLong(text);
                foundUser = userService.findByTelegramId(telegramId);
            } catch (NumberFormatException e) {
                // Не число
            }
        }

        if (foundUser == null) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                    "❌ Пользователь не найден. Проверьте username или ID и попробуйте снова:",
                    createBackToAdminKeyboard()));
            return;
        }

        showUserDetails(chatId, foundUser, bot);
    }

    private void showUserDetails(Long chatId, User user, MyBot bot) {
        String message = String.format("""
                        👤 Информация о пользователе:
                        
                        🆔 ID: %d
                        📞 Telegram ID: %d
                        👤 Имя: %s %s
                        📱 Username: @%s
                        
                        📊 Статистика:
                        • Всего заявок: %d
                        • Успешных: %d
                        • Потрачено: %.2f ₽
                        • Получено: %.2f ₽
                        • Бонусный баланс: %.2f ₽
                        
                        📈 Реферальная система:
                        • Приглашено: %d
                        • Заработано: %.2f ₽
                        """,
                user.getId(),
                user.getTelegramId(),
                user.getFirstName(),
                user.getLastName() != null ? user.getLastName() : "",
                user.getUsername() != null ? user.getUsername() : "нет",
                user.getTotalApplications(),
                user.getCompletedBuyApplications() + user.getCompletedSellApplications(),
                user.getTotalBuyAmount(),
                user.getTotalSellAmount(),
                user.getBonusBalance(),
                user.getReferralCount(),
                user.getReferralEarnings()
        );

        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createBackToAdminKeyboard()));
    }


    private void processAdminMainMenu(Long chatId, User user, String text, MyBot bot) {
        switch (text) {
            case "📋 Все заявки":
                user.setState(UserState.ADMIN_VIEW_ALL_APPLICATIONS);
                userService.update(user);
                showAllApplications(chatId, user, bot);
                break;
            case "📊 Активные заявки":
                user.setState(UserState.ADMIN_VIEW_ACTIVE_APPLICATIONS);
                userService.update(user);
                showActiveApplications(chatId, user, bot);
                break;
            case "⏭️ Следующая заявка":
                processNextApplication(chatId, user, bot);
                break;
            case "👥 Поиск пользователя":
                user.setState(UserState.ADMIN_VIEW_USER_DETAILS);
                userService.update(user);
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                        "Введите username (без @) или ID пользователя:", createBackToAdminKeyboard()));
                break;
            case "🎫 Создать купон":
                user.setState(UserState.ADMIN_CREATE_COUPON);
                userService.update(user);
                showCreateCouponMenu(chatId, bot);
                break;
            case "💰 Комиссии":
                user.setState(UserState.ADMIN_COMMISSION_SETTINGS);
                userService.update(user);
                showAdminCommissionSettings(chatId, user, bot);
                break;
            case "🔙 Главное меню":
                deletePreviousBotMessage(chatId, bot);
                user.setState(UserState.MAIN_MENU);
                userService.update(user);
                showMainMenu(chatId, user, bot);
                break;
            default:
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                        "❌ Пожалуйста, используйте кнопки", createAdminMainMenuKeyboard()));
        }
    }

    private void showAdminCommissionSettings(Long chatId, User user, MyBot bot) {
        String message = "💰 Управление комиссиями\n\n" +
                "Текущие настройки:\n" +
                "• 1000-1999 ₽: " + commissionConfig.getCommissionPercent(1000) + "%\n" +
                "• 2000-2999 ₽: " + commissionConfig.getCommissionPercent(2000) + "%\n" +
                "• 3000-4999 ₽: " + commissionConfig.getCommissionPercent(3000) + "%\n" +
                "• 5000+ ₽: " + commissionConfig.getCommissionPercent(5000) + "%\n\n" +
                "Для изменения введите:\n" +
                "• Для диапазона: 1000-1999 5\n" +
                "• Для минимальной суммы: 5000 2\n\n" +
                "Используйте '🔙 Назад' для возврата";

        ReplyKeyboardMarkup keyboard = createBackToAdminKeyboard();
        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, keyboard));
    }

    private void showCreateCouponMenu(Long chatId, MyBot bot) {
        String message = """
                🎫 Создание купона
                
                Введите данные купона в формате:
                код тип значение описание
                
                Примеры:
                SUMMER percent 10 Скидка 10% на лето
                BONUS amount 500 Бонус 500 рублей
                VIP percent 15 VIP скидка 15%
                
                Типы: percent (процент) или amount (фиксированная сумма)
                """;

        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createBackToAdminKeyboard()));
    }

    private void processCreateCoupon(Long chatId, User user, String text, MyBot bot) {
        if (text.equals("🔙 Назад") || text.equals("🔙 Главное меню")) {
            user.setState(UserState.ADMIN_MAIN_MENU);
            userService.update(user);
            showAdminMainMenu(chatId, bot);
            return;
        }

        try {
            String[] parts = text.split(" ", 4);
            if (parts.length < 4) {
                throw new IllegalArgumentException("Недостаточно параметров. Формат: код тип значение описание");
            }

            String code = parts[0];
            String type = parts[1];
            double value = Double.parseDouble(parts[2]);
            String description = parts[3];

            // Проверяем, существует ли уже купон с таким кодом
            if (couponService.findByCode(code).isPresent()) {
                throw new IllegalArgumentException("Купон с кодом " + code + " уже существует");
            }

            Coupon coupon = new Coupon();
            coupon.setCode(code.toUpperCase());
            coupon.setDescription(description);
            coupon.setIsActive(true);
            coupon.setIsUsed(false);

            if ("percent".equalsIgnoreCase(type)) {
                if (value <= 0 || value > 100) {
                    throw new IllegalArgumentException("Процент скидки должен быть от 1 до 100");
                }
                coupon.setDiscountPercent(value);
            } else if ("amount".equalsIgnoreCase(type)) {
                if (value <= 0) {
                    throw new IllegalArgumentException("Сумма скидки должна быть больше 0");
                }
                coupon.setDiscountAmount(value);
            } else {
                throw new IllegalArgumentException("Неверный тип скидки. Используйте 'percent' или 'amount'");
            }

            // Сохраняем купон
            couponService.createCoupon(coupon);

            String message = String.format("""
                            ✅ Купон создан!
                            
                            🎫 Код: %s
                            💰 Скидка: %s
                            📝 Описание: %s
                            """,
                    coupon.getCode(),
                    coupon.getDiscountPercent() != null ?
                            coupon.getDiscountPercent() + "%" : coupon.getDiscountAmount() + " ₽",
                    coupon.getDescription()
            );

            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createAdminMainMenuKeyboard()));

            user.setState(UserState.ADMIN_MAIN_MENU);
            userService.update(user);

        } catch (Exception e) {
            String errorMessage = "❌ Ошибка при создании купона: " + e.getMessage() +
                    "\n\nПравильный формат:\n" +
                    "код тип значение описание\n\n" +
                    "Примеры:\n" +
                    "SUMMER percent 10 Скидка 10% на лето\n" +
                    "BONUS amount 500 Бонус 500 рублей\n\n" +
                    "Попробуйте снова:";

            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, errorMessage, createBackToAdminKeyboard()));
        }

        try {
            String[] parts = text.split(" ", 4);
            if (parts.length < 4) {
                throw new IllegalArgumentException("Недостаточно параметров");
            }

            String code = parts[0];
            String type = parts[1];
            double value = Double.parseDouble(parts[2]);
            String description = parts[3];

            Coupon coupon = new Coupon();
            coupon.setCode(code.toUpperCase());
            coupon.setDescription(description);
            coupon.setIsActive(true);
            coupon.setIsUsed(false);

            if ("percent".equalsIgnoreCase(type)) {
                coupon.setDiscountPercent(value);
            } else if ("amount".equalsIgnoreCase(type)) {
                coupon.setDiscountAmount(value);
            } else {
                throw new IllegalArgumentException("Неверный тип скидки");
            }

            // Сохраняем купон
            couponService.createCoupon(coupon);

            String message = String.format("""
                            ✅ Купон создан!
                            
                            🎫 Код: %s
                            💰 Скидка: %s
                            📝 Описание: %s
                            """,
                    coupon.getCode(),
                    coupon.getDiscountPercent() != null ?
                            coupon.getDiscountPercent() + "%" : coupon.getDiscountAmount() + " ₽",
                    coupon.getDescription()
            );

            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createBackToAdminKeyboard()));

            user.setState(UserState.ADMIN_MAIN_MENU);
            userService.update(user);

        } catch (Exception e) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                    "❌ Ошибка при создании купона: " + e.getMessage() + "\nПопробуйте снова:",
                    createBackToAdminKeyboard()));
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
                statusCount.getOrDefault(ApplicationStatus.COMPLETED, 0L),
                statusCount.getOrDefault(ApplicationStatus.CANCELLED, 0L)
        );

        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createAdminMainMenuKeyboard()));
    }

    private void showAdminUsers(Long chatId, User user, MyBot bot) {
        String message = "👥 Раздел управления пользователями в разработке";
        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createAdminMainMenuKeyboard()));
    }

    private void processAdminViewingAllApplications(Long chatId, User user, MyBot bot) {
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

            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, response.toString(), createBackToAdminKeyboard()));
        }
    }

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
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, "❌ Введите корректный номер заявки", createBackToAdminKeyboard()));
        }
    }

    private void showAdminApplicationDetails(Long chatId, User user, Application application, MyBot bot) {
        String userInfo = String.format("@%s (ID: %d, TG: %d)",
                application.getUser().getUsername() != null ? application.getUser().getUsername() : "нет_username",
                application.getUser().getId(),
                application.getUser().getTelegramId());

        String message = String.format("""
                        📋 Детали заявки #%d
                        
                        👤 Пользователь: %s %s
                        %s
                        💰 Тип операции: %s
                        📊 Статус: %s
                        
                        💸 Отдает: %.2f %s
                        💰 Получает: %.8f %s
                        
                        %s
                        🔐 Кошелек: %s
                        
                        📅 Создана: %s
                        🕰️ Истекает: %s
                        """,
                application.getId(),
                application.getUser().getFirstName(),
                application.getUser().getLastName() != null ? application.getUser().getLastName() : "",
                userInfo, // ДОБАВЛЕНО
                application.getTitle(),
                application.getStatus().getDisplayName(),
                application.getCalculatedGiveValue(),
                application.getUserValueGiveType().getDisplayName(),
                application.getCalculatedGetValue(),
                application.getUserValueGetType().getDisplayName(),
                application.getIsVip() ? "👑 VIP-приоритет" : "🔹 Обычный приоритет",
                application.getWalletAddress(),
                application.getCreatedAt().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")),
                application.getFormattedExpiresAt()
        );

        if (application.getAppliedCoupon() != null) {
            message += String.format("\n🎫 Применен купон: %s", application.getAppliedCoupon().getCode());
        }

        ReplyKeyboardMarkup keyboard = createAdminApplicationActionsKeyboard();
        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, keyboard));
    }

    private void processOtherMenu(Long chatId, User user, String text, MyBot bot) {
        switch (text) {
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
            case "🧮 Калькулятор":
                user.setState(UserState.CALCULATOR_MENU);
                userService.update(user);
                showCalculatorMenu(chatId, user, bot);
                break;
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
            case "💎 Главное меню":
                deletePreviousBotMessage(chatId, bot);
                user.setState(UserState.MAIN_MENU);
                userService.update(user);
                showMainMenu(chatId, user, bot);
                break;
            default:
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                        "❌ Пожалуйста, используйте кнопки", createOtherMenuKeyboard()));
        }
    }

    private void processCalculatorBuy(Long chatId, User user, String text, MyBot bot) {
        if (text.equals("🔙 Назад")) {
            user.setState(UserState.CALCULATOR_MENU);
            userService.update(user);
            showCalculatorMenu(chatId, user, bot);
            return;
        }

        try {
            double rubAmount = Double.parseDouble(text);
            double btcPrice = cryptoPriceService.getCurrentPrice("BTC", "RUB");
            double btcAmount = rubAmount / btcPrice;
            double commission = commissionService.calculateCommission(rubAmount);
            double totalAmount = commissionService.calculateTotalWithCommission(rubAmount);

            String calculation = String.format("""
                            🧮 Расчет покупки:
                            
                            💰 Вводимая сумма: %.2f ₽
                            💸 Комиссия: %.2f ₽ (%.1f%%)
                            💵 Итого к оплате: %.2f ₽
                            ₿ Вы получите: %.8f BTC
                            
                            Курс BTC: %.2f ₽
                            """,
                    rubAmount, commission, commissionService.getCommissionPercent(rubAmount),
                    totalAmount, btcAmount, btcPrice
            );

            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, calculation, createCalculatorBackKeyboard()));

        } catch (NumberFormatException e) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                    "❌ Пожалуйста, введите корректное число", createCalculatorBackKeyboard()));
        }
    }

    // Обновляем метод отмены через текстовую команду
    private void cancelUserApplication(Long chatId, User user, Long applicationId, MyBot bot) {
        Application application = applicationService.find(applicationId);

        if (application == null || !application.getUser().getId().equals(user.getId())) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                    "❌ Заявка не найдена или у вас нет прав для её отмены", createMainMenuKeyboard(user)));
            return;
        }

        if (application.getStatus() != ApplicationStatus.FREE && application.getStatus() != ApplicationStatus.IN_WORK) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                    "❌ Невозможно отменить заявку с текущим статусом: " + application.getStatus().getDisplayName(),
                    createMainMenuKeyboard(user)));
            return;
        }

        application.setStatus(ApplicationStatus.CANCELLED);
        applicationService.update(application);

        // УДАЛЯЕМ сообщение с заявкой если оно есть
        if (application.getTelegramMessageId() != null) {
            bot.deleteMessage(chatId, application.getTelegramMessageId());
        }

        String message = "✅ Заявка #" + applicationId + " успешно отменена.";
        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createMainMenuKeyboard(user)));
    }

    private void processCalculatorMenu(Long chatId, User user, String text, MyBot bot) {
        switch (text) {
            case "💰 Купить BTC":
                user.setState(UserState.CALCULATOR_BUY);
                userService.update(user);
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                        "💎 Введите сумму в рублях для расчета:", createCalculatorBackKeyboard()));
                break;
            case "💸 Продать BTC":
                user.setState(UserState.CALCULATOR_SELL);
                userService.update(user);
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                        "💎 Введите количество BTC для расчета:", createCalculatorBackKeyboard()));
                break;
            case "🔙 Назад":
                user.setState(UserState.OTHER_MENU);
                userService.update(user);
                showOtherMenu(chatId, user, bot);
                break;
            default:
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                        "❌ Пожалуйста, используйте кнопки", createCalculatorMenuKeyboard()));
        }
    }

    private void showCalculatorMenu(Long chatId, User user, MyBot bot) {
        String message = "🧮 Калькулятор\n\nВыберите тип расчета:";
        ReplyKeyboardMarkup keyboard = createCalculatorMenuKeyboard();
        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, keyboard));
    }

    private void processReferralMenu(Long chatId, User user, String text, MyBot bot) {
        switch (text) {
            case "Создать реферальный код":
                user.setState(UserState.CREATING_REFERRAL_CODE);
                userService.update(user);
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                        "Введите описание для вашего реферального кода (например: 'Для друзей' или 'Специальное предложение'):",
                        createBackKeyboard()));
                break;
            case "🔙 Назад":
                user.setState(UserState.OTHER_MENU);
                userService.update(user);
                showOtherMenu(chatId, user, bot);
                break;
            case "🔙 Главное меню":
                processMainMenu(chatId, user, bot);
                break;
            default:
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                        "❌ Пожалуйста, используйте кнопки", createReferralMenuKeyboard()));
        }
    }

    private void showReferralMenu(Long chatId, User user, MyBot bot) {
        user = userService.find(user.getId());
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
                message.append(String.format("🔸 Код: %s (Использований: %d)\n",
                        code.getCode(), code.getUsages().size()));
            }
        }

        ReplyKeyboardMarkup keyboard = createReferralMenuKeyboard();
        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message.toString(), keyboard));
    }

    private void processCreatingReferralCode(Long chatId, User user, String text, MyBot bot) {
        if (text.equals("🔙 Назад") || text.equals("🔙 Главное меню")) {
            user.setState(UserState.REFERRAL_MENU);
            userService.update(user);
            showReferralMenu(chatId, user, bot);
            return;
        }

        try {
            ReferralCode referralCode = referralService.createReferralCode(user);

            String message = String.format("""
                            ✅ Реферальный код создан!
                            
                            🔸 Ваш код: %s
                            
                            Теперь вы можете делиться этим кодом с друзьями. 
                            За каждую успешную заявку реферала вы будете получать %.2f%% от суммы заявки.
                            """,
                    referralCode.getCode(),
                    referralCode.getRewardPercent());

            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createReferralMenuKeyboard()));

            user.setState(UserState.REFERRAL_MENU);
            userService.update(user);

        } catch (Exception e) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                    "❌ Ошибка при создании реферального кода. Попробуйте позже.",
                    createBackKeyboard()));
        }
    }

    private void processEnteringReferralCode(Long chatId, User user, String text, MyBot bot) {
        if (text.equals("🔙 Назад") || text.equals("🔙 Главное меню")) {
            user.setState(UserState.MAIN_MENU);
            userService.update(user);
            showMainMenu(chatId, user, bot);
            return;
        }

        boolean success = referralService.useReferralCode(text.trim(), user);
        if (success) {
            user = userService.find(user.getId());

            String message = "✅ Реферальный код успешно активирован!\n\n" +
                    "Теперь вы будете получать бонусы за приглашенных друзей.\n" +
                    "Спасибо за участие в реферальной программе!";

            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createMainMenuKeyboard(user)));

            user.setState(UserState.MAIN_MENU);
            userService.update(user);
        } else {
            String message = "❌ Неверный реферальный код или он уже был использован.\n\n" +
                    "Пожалуйста, проверьте код и попробуйте еще раз.";
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createBackKeyboard()));
        }
    }

    // Методы создания клавиатур с кнопками выхода
    private InlineKeyboardMarkup createCaptchaKeyboard(List<String> options) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // Создаем 4 строки по 2 кнопки в каждой (итого 8 кнопок)
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

        // Первый ряд - основные кнопки
        KeyboardRow row1 = new KeyboardRow();
        row1.add("💰 Купить");
        row1.add("💸 Продать");

        // Второй ряд
        KeyboardRow row2 = new KeyboardRow();
        row2.add("💳 Комиссии");
        row2.add("⚙️ Прочее");

        rows.add(row1);
        rows.add(row2);

        // Реферальная кнопка (если не использована)
        if (user.getUsedReferralCode() == null) {
            KeyboardRow referralRow = new KeyboardRow();
            referralRow.add("🎫 Ввести реф. код");
            rows.add(referralRow);
        }

        // Админ панель
        if (adminConfig.isAdmin(user.getId())) {
            KeyboardRow adminRow = new KeyboardRow();
            adminRow.add("👨‍💼 Админ панель");
            rows.add(adminRow);
        }

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
        row1.add("✅ Выполнено"); // ЗАМЕНА: было "🔵 Закрыть"

        KeyboardRow row2 = new KeyboardRow();
        row2.add("🔴 Отменить");
        row2.add("🟢 Свободна");

        KeyboardRow row3 = new KeyboardRow();
        row3.add("📋 Все заявки");
        row3.add("🔙 Назад");

        KeyboardRow row4 = new KeyboardRow();
        row4.add("🔙 Главное меню");

        rows.add(row1);
        rows.add(row2);
        rows.add(row3);
        rows.add(row4);

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
        row2.add("🔙 Главное меню");

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
        row2.add("🔙 Главное меню");

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
        row1.add("🔙 Назад");
        row1.add("🔙 Главное меню");

        rows.add(row1);

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
        row2.add("🔙 Назад");
        row2.add("🔙 Главное меню");

        rows.add(row1);
        rows.add(row2);

        keyboard.setKeyboard(rows);
        return keyboard;
    }

    private ReplyKeyboardMarkup createCalculatorMenuKeyboard() {
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(false);

        List<KeyboardRow> rows = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add("💰 Купить BTC");
        row1.add("💸 Продать BTC");

        KeyboardRow row2 = new KeyboardRow();
        row2.add("🔙 Назад");

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
        row1.add("🔙 Назад");
        row1.add("🔙 Главное меню");

        rows.add(row1);

        keyboard.setKeyboard(rows);
        return keyboard;
    }

    private ReplyKeyboardMarkup createBackToAdminKeyboard() {
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(false);

        List<KeyboardRow> rows = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add("🔙 Назад");
        row1.add("🔙 Главное меню");

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
        row1.add("📋 Мои заявки");
        row1.add("🎫 Мои купоны");

        KeyboardRow row2 = new KeyboardRow();
        row2.add("🧮 Калькулятор");
        row2.add("📊 Курсы");

        KeyboardRow row3 = new KeyboardRow();
        row3.add("👤 Профиль");
        row3.add("📈 Реферальная система");

        KeyboardRow row4 = new KeyboardRow();
        row4.add("💎 Главное меню");

        rows.add(row1);
        rows.add(row2);
        rows.add(row3);
        rows.add(row4);

        keyboard.setKeyboard(rows);
        return keyboard;
    }

    private ReplyKeyboardMarkup createAdminMainMenuKeyboard() {
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(false);

        List<KeyboardRow> rows = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add("📋 Все заявки");
        row1.add("📊 Активные заявки");

        KeyboardRow row2 = new KeyboardRow();
        row2.add("⏭️ Следующая заявка");
        row2.add("👥 Поиск пользователя");

        KeyboardRow row3 = new KeyboardRow();
        row3.add("🎫 Создать купон");
        row3.add("💰 Комиссии");

        KeyboardRow row4 = new KeyboardRow();
        row4.add("🔙 Главное меню");

        rows.add(row1);
        rows.add(row2);
        rows.add(row3);
        rows.add(row4);

        keyboard.setKeyboard(rows);
        return keyboard;
    }

    private ReplyKeyboardMarkup createVipConfirmationKeyboard() {
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(false);

        List<KeyboardRow> rows = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add("👑 Да, добавить VIP");
        row1.add("🔹 Нет, обычный приоритет");

        KeyboardRow row2 = new KeyboardRow();
        row2.add("🔙 Назад");
        row2.add("🔙 Главное меню");

        rows.add(row1);
        rows.add(row2);

        keyboard.setKeyboard(rows);
        return keyboard;
    }

    private ReplyKeyboardMarkup createWalletInputKeyboard() {
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(false);

        List<KeyboardRow> rows = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add("🔙 Назад");
        row1.add("🔙 Главное меню");

        rows.add(row1);

        keyboard.setKeyboard(rows);
        return keyboard;
    }

    private ReplyKeyboardMarkup createReferralMenuKeyboard() {
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(false);

        List<KeyboardRow> rows = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add("Создать реферальный код");

        KeyboardRow row2 = new KeyboardRow();
        row2.add("🔙 Назад");
        row2.add("🔙 Главное меню");

        rows.add(row1);
        rows.add(row2);

        keyboard.setKeyboard(rows);
        return keyboard;
    }

    private ReplyKeyboardMarkup createCalculatorBackKeyboard() {
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(false);

        List<KeyboardRow> rows = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add("🔙 Назад");

        rows.add(row1);

        keyboard.setKeyboard(rows);
        return keyboard;
    }

    private ReplyKeyboardMarkup createApplicationActionsKeyboard(Long applicationId) {
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(false);

        List<KeyboardRow> rows = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add("❌ Отменить заявку #" + applicationId);

        KeyboardRow row2 = new KeyboardRow();
        row2.add("📞 Написать оператору");

        KeyboardRow row3 = new KeyboardRow();
        row3.add("💎 Главное меню");

        rows.add(row1);
        rows.add(row2);
        rows.add(row3);

        keyboard.setKeyboard(rows);
        return keyboard;
    }

    private ReplyKeyboardMarkup createFinalConfirmationKeyboard() {
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(false);

        List<KeyboardRow> rows = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add("✅ Подтвердить");
        row1.add("❌ Отменить");

        KeyboardRow row2 = new KeyboardRow();
        row2.add("🔙 Назад");

        rows.add(row1);
        rows.add(row2);

        keyboard.setKeyboard(rows);
        return keyboard;
    }

    private InlineKeyboardMarkup createApplicationInlineKeyboard(Long applicationId) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // Первая строка: отмена и номер в очереди
        List<InlineKeyboardButton> row1 = new ArrayList<>();

        InlineKeyboardButton cancelButton = new InlineKeyboardButton();
        cancelButton.setText("❌ Отменить заявку");
        cancelButton.setCallbackData("cancel_app_" + applicationId);
        row1.add(cancelButton);

        InlineKeyboardButton queueButton = new InlineKeyboardButton();
        queueButton.setText("📊 Номер в очереди");
        queueButton.setCallbackData("queue_app_" + applicationId);
        row1.add(queueButton);

        // Вторая строка: оператор
        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton operatorButton = new InlineKeyboardButton();
        operatorButton.setText("📞 Написать оператору");
        operatorButton.setUrl("https://t.me/cosanostra_support");
        row2.add(operatorButton);

        rows.add(row1);
        rows.add(row2);

        markup.setKeyboard(rows);
        return markup;
    }

    private ReplyKeyboardMarkup createAdminApplicationsKeyboard() {
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(false);

        List<KeyboardRow> rows = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add("📋 Все заявки");
        row1.add("📊 Активные заявки");

        KeyboardRow row2 = new KeyboardRow();
        row2.add("⏭️ Следующая заявка");
        row2.add("👥 Поиск пользователя");

        KeyboardRow row3 = new KeyboardRow();
        row3.add("🎫 Создать купон");
        row3.add("💰 Комиссии");

        KeyboardRow row4 = new KeyboardRow();
        row4.add("📅 Фильтр по времени");
        row4.add("🔙 Главное меню");

        rows.add(row1);
        rows.add(row2);
        rows.add(row3);
        rows.add(row4);

        keyboard.setKeyboard(rows);
        return keyboard;
    }

    private InlineKeyboardMarkup createMainMenuInlineKeyboard(User user) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // Первый ряд - основные кнопки
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton buyButton = new InlineKeyboardButton();
        buyButton.setText("💰 Купить BTC");
        buyButton.setCallbackData("inline_buy");
        row1.add(buyButton);

        InlineKeyboardButton sellButton = new InlineKeyboardButton();
        sellButton.setText("💸 Продать BTC");
        sellButton.setCallbackData("inline_sell");
        row1.add(sellButton);

        // Второй ряд
        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton commissionsButton = new InlineKeyboardButton();
        commissionsButton.setText("💳 Комиссии");
        commissionsButton.setCallbackData("inline_commissions");
        row2.add(commissionsButton);

        InlineKeyboardButton otherButton = new InlineKeyboardButton();
        otherButton.setText("⚙️ Прочее");
        otherButton.setCallbackData("inline_other");
        row2.add(otherButton);

        rows.add(row1);
        rows.add(row2);

        // Третий ряд - реферальная кнопка (если не использована)
        if (user.getUsedReferralCode() == null) {
            List<InlineKeyboardButton> row3 = new ArrayList<>();
            InlineKeyboardButton referralButton = new InlineKeyboardButton();
            referralButton.setText("🎫 Ввести реф. код");
            referralButton.setCallbackData("inline_referral");
            row3.add(referralButton);
            rows.add(row3);
        }

        // Четвертый ряд - админ панель
        if (adminConfig.isAdmin(user.getId())) {
            List<InlineKeyboardButton> row4 = new ArrayList<>();
            InlineKeyboardButton adminButton = new InlineKeyboardButton();
            adminButton.setText("👨‍💼 Админ панель");
            adminButton.setCallbackData("inline_admin");
            row4.add(adminButton);
            rows.add(row4);
        }

        markup.setKeyboard(rows);
        return markup;
    }
    private void processAdminTimeFilter(Long chatId, User user, MyBot bot) {
        String message = "📅 Фильтр заявок по времени:\n\n" +
                "Выберите период для просмотра заявок:";

        ReplyKeyboardMarkup keyboard = createTimeFilterKeyboard();
        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, keyboard));
    }

    private ReplyKeyboardMarkup createTimeFilterKeyboard() {
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(false);

        List<KeyboardRow> rows = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add("📅 Сегодня");
        row1.add("📅 За неделю");

        KeyboardRow row2 = new KeyboardRow();
        row2.add("📅 За месяц");
        row2.add("📅 Все время");

        KeyboardRow row3 = new KeyboardRow();
        row3.add("🔙 Назад в админку");

        rows.add(row1);
        rows.add(row2);
        rows.add(row3);

        keyboard.setKeyboard(rows);
        return keyboard;
    }
    private String formatRubAmount(double amount) {
        return String.format("%.2f", amount);
    }

    private String formatBtcAmount(double amount) {
        return String.format("%.8f", amount);
    }
    private InlineKeyboardMarkup createProfileInlineKeyboard() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton backButton = new InlineKeyboardButton();
        backButton.setText("🔙 Назад");
        backButton.setCallbackData("inline_back");
        row1.add(backButton);

        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton mainMenuButton = new InlineKeyboardButton();
        mainMenuButton.setText("💎 Главное меню");
        mainMenuButton.setCallbackData("inline_main_menu");
        row2.add(mainMenuButton);

        rows.add(row1);
        rows.add(row2);

        markup.setKeyboard(rows);
        return markup;
    }
}