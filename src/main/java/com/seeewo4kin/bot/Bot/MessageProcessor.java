package com.seeewo4kin.bot.Bot;

import com.seeewo4kin.bot.Config.AdminConfig;
import com.seeewo4kin.bot.Config.CommissionConfig;
import com.seeewo4kin.bot.Entity.*;
import com.seeewo4kin.bot.Enums.ApplicationStatus;
import com.seeewo4kin.bot.Enums.UserState;
import com.seeewo4kin.bot.Enums.ValueType;
import com.seeewo4kin.bot.ValueGettr.CryptoPriceService;
import com.seeewo4kin.bot.service.*;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
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

    private String formatRubAmount(double amount) {
        return String.format("%.2f ₽", amount).replace(",", ".");
    }

    private String formatBtcAmount(double amount) {
        return String.format("%.8f BTC", amount).replace(",", ".");
    }

    private String formatDouble(double value) {
        return String.format("%.2f", value).replace(",", ".");
    }

    private String formatPercent(double value) {
        return String.format("%.1f%%", value).replace(",", ".");
    }


    public void processUpdate(Update update, MyBot bot) {
        if (update.hasMessage()) {
            Long chatId = update.getMessage().getChatId();
            deletePreviousBotMessage(chatId, bot);
        }

        if (update.hasCallbackQuery()) {
            processCallback(update, bot);
            return;
        }

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

    // Обновляем метод processTextMessage для лучшей обработки текстовых команд
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
            deletePreviousBotMessage(chatId, bot);
            processMainMenu(chatId, user, bot);
            return;
        }

        if (text.equals("🔙 Назад")) {
            handleBackButton(chatId, user, bot);
            return;
        }

        // Обработка текстовых команд для админ-панели
        if (text.startsWith("/admin")) {
            if (adminConfig.isAdmin(user.getId())) {
                user.setState(UserState.ADMIN_MAIN_MENU);
                userService.update(user);
                showAdminMainMenu(chatId, bot);
                return;
            }
        }

        // Если пользователь не найден или в состоянии START
        if (user == null || user.getState() == UserState.START) {
            processCommand(update, bot);
        } else {
            // Если пользователь в главном меню, обрабатываем текстовые команды
            if (user.getState() == UserState.MAIN_MENU) {
                processMainMenuCommand(chatId, user, text, bot);
            } else {
                processUserState(update, user, bot);
            }
        }
    }

    private void handleBackButton(Long chatId, User user, MyBot bot) {
        deletePreviousBotMessage(chatId, bot);

        switch (user.getState()) {

            case ADMIN_MY_APPLICATIONS:
                user.setState(UserState.ADMIN_MAIN_MENU);
                userService.update(user);
                showAdminMainMenu(chatId, bot);
                break;
            // Основные меню возвращают в главное меню
            case BUY_MENU:
            case SELL_MENU:
            case OTHER_MENU:
            case REFERRAL_MENU:
            case ADMIN_MAIN_MENU:
                processMainMenu(chatId, user, bot);
                break;

            // Ввод суммы возвращает в соответствующее меню
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

            case USING_BONUS_BALANCE:
                user.setState(UserState.CONFIRMING_VIP);
                userService.update(user);
                Application appBonus = temporaryApplications.get(user.getId());
                if (appBonus != null) {
                    showVipConfirmation(chatId, user, appBonus, bot);
                }
                break;

            // Применение купонов возвращает на предыдущий шаг
            case APPLYING_COUPON:
                if ("BUY_RUB".equals(currentOperation.get(user.getId())) ||
                        "BUY_BTC".equals(currentOperation.get(user.getId()))) {
                    user.setState(UserState.BUY_MENU);
                    showBuyMenu(chatId, bot);
                } else {
                    user.setState(UserState.SELL_MENU);
                    showSellMenu(chatId, bot);
                }
                break;

            case APPLYING_COUPON_FINAL:
                user.setState(UserState.CONFIRMING_VIP);
                userService.update(user);
                Application application = temporaryApplications.get(user.getId());
                if (application != null) {
                    showVipConfirmation(chatId, user, application, bot);
                }
                break;

            case CONFIRMING_VIP:
                user.setState(UserState.ENTERING_WALLET);
                userService.update(user);
                showWalletInput(chatId, bot);
                break;

            case ENTERING_WALLET:
                // Определяем, из какого меню пришли
                Application app = temporaryApplications.get(user.getId());
                if (app != null) {
                    if (app.getUserValueGetType() == ValueType.BTC) {
                        user.setState(UserState.BUY_MENU);
                        showBuyMenu(chatId, bot);
                    } else {
                        user.setState(UserState.SELL_MENU);
                        showSellMenu(chatId, bot);
                    }
                } else {
                    processMainMenu(chatId, user, bot);
                }
                break;

            case CONFIRMING_APPLICATION:
                user.setState(UserState.APPLYING_COUPON_FINAL);
                userService.update(user);
                Application appConfirm = temporaryApplications.get(user.getId());
                if (appConfirm != null) {
                    showCouponApplication(chatId, user, appConfirm, bot);
                }
                break;

            // Админские состояния
            case ADMIN_VIEWING_ALL_APPLICATIONS:
            case ADMIN_COMMISSION_SETTINGS:
            case ADMIN_VIEW_ALL_APPLICATIONS:
            case ADMIN_VIEW_ACTIVE_APPLICATIONS:
                user.setState(UserState.ADMIN_MAIN_MENU);
                userService.update(user);
                showAdminMainMenu(chatId, bot);
                break;

            case ADMIN_VIEWING_APPLICATION_DETAILS:
                user.setState(UserState.ADMIN_VIEWING_ALL_APPLICATIONS);
                userService.update(user);
                processAdminViewingAllApplications(chatId, user, bot);
                break;

            case ADMIN_VIEW_USER_DETAILS:
            case ADMIN_CREATE_COUPON:
                user.setState(UserState.ADMIN_MAIN_MENU);
                userService.update(user);
                showAdminMainMenu(chatId, bot);
                break;

            // Создание реферального кода
            case CREATING_REFERRAL_CODE:
                user.setState(UserState.REFERRAL_MENU);
                userService.update(user);
                showReferralMenu(chatId, user, bot);
                break;

            // Ввод реферального кода
            case ENTERING_REFERRAL_CODE:
                user.setState(UserState.MAIN_MENU);
                userService.update(user);
                showMainMenu(chatId, user, bot);
                break;

            // Калькулятор
            case CALCULATOR_MENU:
                user.setState(UserState.OTHER_MENU);
                userService.update(user);
                showOtherMenu(chatId, user, bot);
                break;

            case CALCULATOR_BUY:
            case CALCULATOR_SELL:
                user.setState(UserState.CALCULATOR_MENU);
                userService.update(user);
                showCalculatorMenu(chatId, user, bot);
                break;

            // По умолчанию - главное меню
            default:
                processMainMenu(chatId, user, bot);
        }
    }
    private void showWalletInput(Long chatId, MyBot bot) {
        String message = "🔐 Введите адрес Bitcoin-кошелька:";
        InlineKeyboardMarkup inlineKeyboard = createBackAndMainMenuKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);
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
            // Если пользователь отправил неизвестную команду, показываем главное меню
            user.setState(UserState.MAIN_MENU);
            userService.update(user);
            showMainMenu(chatId, user, bot);
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

            case ADMIN_VIEW_COUPONS:
                processAdminViewCoupons(chatId, user, text, bot);
                break;

            case ADMIN_CREATE_COUPON_ADVANCED:
                processAdminCreateCouponAdvanced(chatId, user, text, bot);
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
            case ADMIN_MY_APPLICATIONS:
                processAdminMyApplicationsSelection(chatId, user, text, bot);
                break;

            case ADMIN_MANAGE_BONUS_BALANCE:
                processAdminBonusBalanceManagement(chatId, user, text, bot);
                break;

            case USING_BONUS_BALANCE:
                processBonusUsageText(chatId, user, text, bot);
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
                        "❌ Количество должно быть больше 0", createCalculatorMenuInlineKeyboard()));
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

            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, calculation, createCalculatorMenuInlineKeyboard()));

        } catch (NumberFormatException e) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                    "❌ Пожалуйста, введите корректное число", createCalculatorMenuInlineKeyboard()));
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
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, cancelMessage, createBuyMenuInlineKeyboard()));
                user.setState(UserState.MAIN_MENU);
                userService.update(user);
                break;
            case "🔙 Назад":
                user.setState(UserState.APPLYING_COUPON_FINAL);
                userService.update(user);
                processApplyingCouponFinal(chatId, user, "🔙 Назад", bot);
                break;
            default:
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, "❌ Пожалуйста, используйте кнопки", createFinalConfirmationInlineKeyboard()));
        }
    }

    private void processBonusUsageText(Long chatId, User user, String text, MyBot bot) {
        if (text.equals("🔙 Назад")) {
            user.setState(UserState.CONFIRMING_VIP);
            userService.update(user);
            Application application = temporaryApplications.get(user.getId());
            if (application != null) {
                showVipConfirmation(chatId, user, application, bot);
            }
            return;
        }

        if (text.equals("🔙 Главное меню")) {
            processMainMenu(chatId, user, bot);
            return;
        }

        // Обработка числового ввода
        processBonusUsage(chatId, user, text, bot, null);
    }

    private void createApplicationFinal(Long chatId, User user, Application application, MyBot bot) {
        // ПРОВЕРКА НА НУЛЕВЫЕ ЗНАЧЕНИЯ
        if (application.getCalculatedGiveValue() <= 0 || application.getCalculatedGetValue() <= 0) {
            String errorMessage = "❌ Ошибка: некорректные значения в заявке. Пожалуйста, создайте заявку заново.";
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, errorMessage, createMainMenuInlineKeyboard(user)));
            temporaryApplications.remove(user.getId());
            user.setState(UserState.MAIN_MENU);
            userService.update(user);
            return;
        }

        // СПИСАНИЕ БОНУСНОГО БАЛАНСА
        if (application.getUsedBonusBalance() > 0) {
            if (user.getBonusBalance() >= application.getUsedBonusBalance()) {
                user.setBonusBalance(user.getBonusBalance() - application.getUsedBonusBalance());
                userService.update(user);
            } else {
                String errorMessage = "❌ Ошибка: недостаточно бонусного баланса. Пожалуйста, создайте заявку заново.";
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, errorMessage, createMainMenuInlineKeyboard(user)));
                temporaryApplications.remove(user.getId());
                user.setState(UserState.MAIN_MENU);
                userService.update(user);
                return;
            }
        }

        // Устанавливаем срок действия
        application.setExpiresAt(LocalDateTime.now().plusMinutes(5));

        // Сохраняем заявку
        application.setStatus(ApplicationStatus.FREE);
        applicationService.create(application);
        temporaryApplications.remove(user.getId());

        // Формируем сообщение
        String applicationMessage = formatApplicationMessage(application);

        // Отправляем сообщение с заявкой и сохраняем его ID в заявке
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, applicationMessage, createApplicationInlineKeyboard(application.getId()));
        application.setTelegramMessageId(messageId);
        applicationService.update(application);

        user.setState(UserState.MAIN_MENU);
        userService.update(user);
    }

    private String formatApplicationMessage(Application application) {
        String operationType = application.getUserValueGetType() == ValueType.BTC ? "покупкe" : "продажи";
        String walletLabel = application.getUserValueGetType() == ValueType.BTC ? "🔐 Кошелек BTC" : "💳 Реквизиты для выплаты";

        StringBuilder message = new StringBuilder();
        message.append(String.format("""
                ✅ Заявка на %s создана!
                📝 ID: %s

                %s Отдаете: %.2f %s
                💰 Получаете: %.8f %s
                %s: %s
                %s
                """,
                operationType,
                application.getUuid().substring(0, 8),
                application.getUserValueGetType() == ValueType.BTC ? "💸" : "₿",
                application.getCalculatedGiveValue(),
                application.getUserValueGetType() == ValueType.BTC ? "₽" : "BTC",
                application.getCalculatedGetValue(),
                application.getUserValueGetType() == ValueType.BTC ? "BTC" : "₽",
                walletLabel,
                application.getWalletAddress(),
                application.getIsVip() ? "👑 VIP-приоритет" : "🔹 Обычный приоритет"
        ));

        // Добавляем информацию о бонусном балансе, если использован
        if (application.getUsedBonusBalance() > 0) {
            message.append(String.format("🎁 Использовано бонусов: %.2f ₽\n", application.getUsedBonusBalance()));
        }

        message.append(String.format("""
                🕰️ Срок действия: до %s

                Перешлите эту заявку оператору: @SUP_CN

                Статус: %s
                """,
                application.getFormattedExpiresAt(),
                application.getStatus().getDisplayName()
        ));

        return message.toString();
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
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, "❌ Заявка не найдена", createAdminMainMenuInlineKeyboard()));
            return;
        }

        switch (text) {
            case "🟡 В работу":
                application.setStatus(ApplicationStatus.IN_WORK);
                break;
            case "🔵 Оплачен":
                application.setStatus(ApplicationStatus.PAID);
                break;
            case "✅ Выполнено":
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

                // ВОЗВРАЩАЕМ БОНУСНЫЙ БАЛАНС ПРИ ОТМЕНЕ
                if (application.getUsedBonusBalance() > 0) {
                    User applicationUser = application.getUser();
                    applicationUser.setBonusBalance(applicationUser.getBonusBalance() + application.getUsedBonusBalance());
                    userService.update(applicationUser);

                    // Уведомляем пользователя о возврате бонусов
                    String bonusReturnMessage = String.format(
                            "💸 Вам возвращен бонусный баланс: %.2f ₽\n" +
                                    "📝 Причина: отмена заявки #%d",
                            application.getUsedBonusBalance(), application.getId()
                    );
                    bot.sendMessage(applicationUser.getTelegramId(), bonusReturnMessage);
                }

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
                        "❌ Пожалуйста, используйте кнопки", createAdminApplicationsInlineKeyboard()));
                return;
        }

        applicationService.update(application);

        String message = String.format("✅ Статус заявки #%d изменен на: %s",
                applicationId, application.getStatus().getDisplayName());
        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createBackToAdminKeyboard()));

        user.setState(UserState.ADMIN_VIEWING_ALL_APPLICATIONS);
        userService.update(user);
    }

    private void showAdminBonusBalanceManagement(Long chatId, MyBot bot) {
        String message = "💳 Управление бонусными балансами\n\n" +
                "Выберите действие:";

        InlineKeyboardMarkup inlineKeyboard = createAdminBonusBalanceManagementInlineKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);
    }

    private InlineKeyboardMarkup createAdminBonusBalanceManagementInlineKeyboard() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton searchUserButton = new InlineKeyboardButton();
        searchUserButton.setText("👤 Найти пользователя");
        searchUserButton.setCallbackData("inline_admin_bonus_search");
        row1.add(searchUserButton);

        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton statsButton = new InlineKeyboardButton();
        statsButton.setText("📊 Статистика балансов");
        statsButton.setCallbackData("inline_admin_bonus_stats");
        row2.add(statsButton);

        List<InlineKeyboardButton> row3 = new ArrayList<>();
        InlineKeyboardButton backButton = new InlineKeyboardButton();
        backButton.setText("🔙 Назад");
        backButton.setCallbackData("inline_admin_back");
        row3.add(backButton);

        rows.add(row1);
        rows.add(row2);
        rows.add(row3);

        markup.setKeyboard(rows);
        return markup;
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

        InlineKeyboardMarkup keyboard = createBackToAdminKeyboard();
        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, keyboard));
    }

    private void processStartCommand(Update update, MyBot bot) {
        Long chatId = update.getMessage().getChatId();
        org.telegram.telegrambots.meta.api.objects.User telegramUser = update.getMessage().getFrom();
        String text = update.getMessage().getText();

        User user = userService.findOrCreateUser(telegramUser);

        // Обработка реферальной ссылки
        if (text.contains(" ")) {
            String[] parts = text.split(" ");
            if (parts.length > 1 && parts[1].startsWith("ref_")) {
                String refIdStr = parts[1].replace("ref_", "");
                try {
                    Long inviterTelegramId = Long.parseLong(refIdStr);
                    User inviter = userService.findByTelegramId(inviterTelegramId);

                    if (inviter != null && !inviter.getId().equals(user.getId())) {
                        user.setInvitedBy(inviter);
                        user.setInvitedAt(LocalDateTime.now());
                        referralService.processReferralRegistration(inviter, user);
                    }
                } catch (NumberFormatException e) {
                    // Неверный формат реферального ID
                }
            }
        }

        // ОТПРАВЛЯЕМ ПРИВЕТСТВЕННОЕ СООБЩЕНИЕ, КОТОРОЕ НЕ УДАЛЯЕТСЯ
        String welcomeMessage = """
        🎉 Добро пожаловать в COSA NOSTRA CHANGE!
        
        ⚠️ Будьте бдительны!
        Не подвергайтесь провокациям мошенников, наш оператор первым не пишет✍️

        Актуальные контакты:
        Бот:🤖 @COSANOSTRA24_bot
        ☎️Оператор 24/7: @SUP_CN
        
        Для продолжения пройдите проверку безопасности:
        """;

        // Отправляем приветственное сообщение (оно не будет удаляться)
        bot.sendMessage(chatId, welcomeMessage);

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
    private void showBonusBalanceApplication(Long chatId, User user, Application application, MyBot bot) {
        double availableBonus = user.getBonusBalance();
        double maxUsable = Math.min(availableBonus, application.getCalculatedGiveValue());

        String message = String.format("""
        💰 У вас есть бонусный баланс: %.2f ₽
        
        Вы можете использовать до %.2f ₽ для этой заявки.
        
        Введите сумму бонусного баланса для использования:
        (или 0, если не хотите использовать)
        """, availableBonus, maxUsable);

        InlineKeyboardMarkup inlineKeyboard = createBonusBalanceKeyboard(maxUsable);
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);

        user.setState(UserState.USING_BONUS_BALANCE);
        userService.update(user);
    }

    private InlineKeyboardMarkup createBonusBalanceKeyboard(double maxUsable) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // Кнопки с рекомендуемыми суммами
        if (maxUsable >= 50) {
            List<InlineKeyboardButton> row1 = new ArrayList<>();
            row1.add(createInlineButton("50 ₽", "inline_bonus_50"));

            if (maxUsable >= 100) {
                row1.add(createInlineButton("100 ₽", "inline_bonus_100"));
            }

            if (maxUsable >= 200) {
                row1.add(createInlineButton("200 ₽", "inline_bonus_200"));
            }
            rows.add(row1);
        }

        List<InlineKeyboardButton> row2 = new ArrayList<>();
        row2.add(createInlineButton("Максимум (" + String.format("%.0f", maxUsable) + " ₽)", "inline_bonus_max"));

        List<InlineKeyboardButton> row3 = new ArrayList<>();
        row3.add(createInlineButton("⏭️ Не использовать", "inline_bonus_skip"));

        List<InlineKeyboardButton> row4 = new ArrayList<>();
        row4.add(createInlineButton("🔙 Назад", "inline_back"));

        rows.add(row2);
        rows.add(row3);
        rows.add(row4);

        markup.setKeyboard(rows);
        return markup;
    }

    private List<InlineKeyboardButton> createButtonRow(InlineKeyboardButton... buttons) {
        return new ArrayList<>(Arrays.asList(buttons));
    }

    private InlineKeyboardButton createInlineButton(String text, String callbackData) {
        InlineKeyboardButton button = new InlineKeyboardButton();
        button.setText(text);
        button.setCallbackData(callbackData);
        return button;
    }
    private void showAdminCouponsMenu(Long chatId, MyBot bot) {
        List<Coupon> coupons = couponService.getAllCoupons();

        StringBuilder message = new StringBuilder("🎫 Управление купонами\n\n");

        if (coupons.isEmpty()) {
            message.append("Нет созданных купонов.");
        } else {
            for (Coupon coupon : coupons) {
                message.append(String.format("""
                🔸 Код: %s
                📝 Описание: %s
                💰 Скидка: %s
                📊 Использовано: %d/%s
                🎯 Статус: %s
                --------------------
                """,
                        coupon.getCode(),
                        coupon.getDescription() != null ? coupon.getDescription() : "Без описания",
                        coupon.getDiscountPercent() != null ?
                                coupon.getDiscountPercent() + "%" : coupon.getDiscountAmount() + " ₽",
                        coupon.getUsedCount(),
                        coupon.getUsageLimit() != null ? coupon.getUsageLimit().toString() : "∞",
                        coupon.getIsActive() ? "🟢 Активен" : "🔴 Неактивен"
                ));
            }
        }

        InlineKeyboardMarkup inlineKeyboard = createAdminCouponsMenuInlineKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message.toString(), inlineKeyboard);
        lastMessageId.put(chatId, messageId);
    }

    private InlineKeyboardMarkup createAdminCouponsMenuInlineKeyboard() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        rows.add(createButtonRow(
                createInlineButton("🆕 Создать купон", "inline_admin_create_coupon_advanced"),
                createInlineButton("📊 Статистика", "inline_admin_coupons_stats")
        ));

        rows.add(createButtonRow(
                createInlineButton("🔙 Назад", "inline_admin_back")
        ));

        markup.setKeyboard(rows);
        return markup;
    }

    private void showAdminCreateCouponAdvanced(Long chatId, MyBot bot) {
        String message = """
        🎫 Создание купона (расширенный режим)
        
        Введите данные в формате:
        код тип значение описание лимит_использований
        
        Примеры:
        SUMMER percent 10 Летняя скидка 10% 100
        BONUS amount 500 Бонус 500 рублей 50
        PERSONAL percent 15 Персональная скидка null
        
        Типы: percent (процент) или amount (фиксированная сумма)
        Лимит: число или null (без ограничений)
        """;

        InlineKeyboardMarkup inlineKeyboard = createBackToAdminKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);
    }

    private void processAdminCreateCouponAdvanced(Long chatId, User user, String text, MyBot bot) {
        if (text.equals("🔙 Назад")) {
            user.setState(UserState.ADMIN_VIEW_COUPONS);
            userService.update(user);
            showAdminCouponsMenu(chatId, bot);
            return;
        }

        try {
            String[] parts = text.split(" ", 5);
            if (parts.length < 5) {
                throw new IllegalArgumentException("Недостаточно параметров");
            }

            String code = parts[0];
            String type = parts[1];
            double value = Double.parseDouble(parts[2]);
            String description = parts[3];
            String limitStr = parts[4];

            // Проверяем существование купона
            if (couponService.findByCode(code).isPresent()) {
                throw new IllegalArgumentException("Купон с кодом " + code + " уже существует");
            }

            Coupon coupon = new Coupon();
            coupon.setCode(code.toUpperCase());
            coupon.setDescription(description);
            coupon.setIsActive(true);
            coupon.setIsUsed(false);
            coupon.setUsedCount(0);

            if (!"null".equalsIgnoreCase(limitStr)) {
                coupon.setUsageLimit(Integer.parseInt(limitStr));
            }

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

            couponService.createCoupon(coupon);

            String message = String.format("""
                        ✅ Купон создан!
                        
                        🎫 Код: %s
                        💰 Скидка: %s
                        📝 Описание: %s
                        📊 Лимит: %s
                        """,
                    coupon.getCode(),
                    coupon.getDiscountPercent() != null ?
                            coupon.getDiscountPercent() + "%" : coupon.getDiscountAmount() + " ₽",
                    coupon.getDescription(),
                    coupon.getUsageLimit() != null ? coupon.getUsageLimit().toString() : "без ограничений"
            );

            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createAdminCouponsMenuInlineKeyboard()));

            user.setState(UserState.ADMIN_VIEW_COUPONS);
            userService.update(user);

        } catch (Exception e) {
            String errorMessage = "❌ Ошибка при создании купона: " + e.getMessage() +
                    "\n\nПравильный формат:\n" +
                    "код тип значение описание лимит\n\n" +
                    "Примеры:\n" +
                    "SUMMER percent 10 Летняя скидка 100\n" +
                    "BONUS amount 500 Бонус 500 рублей 50\n" +
                    "PERSONAL percent 15 Персональная скидка null\n\n" +
                    "Попробуйте снова:";

            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, errorMessage, createBackToAdminKeyboard()));
        }
    }




    private void processCallback(Update update, MyBot bot) {
        String callbackData = update.getCallbackQuery().getData();
        Long chatId = update.getCallbackQuery().getMessage().getChatId();
        Long telegramId = update.getCallbackQuery().getFrom().getId();
        String callbackQueryId = update.getCallbackQuery().getId();
        Integer messageId = update.getCallbackQuery().getMessage().getMessageId();

        User user = userService.findByTelegramId(telegramId);
        if (user == null) {
            bot.answerCallbackQuery(callbackQueryId, "❌ Пользователь не найден");
            return;
        }

        try {
            if (callbackData.startsWith("captcha_")) {
                processCaptchaSelection(chatId, user, callbackData, bot, callbackQueryId, messageId);
            } else if (callbackData.startsWith("cancel_app_")) {
                processCancelApplicationCallback(chatId, user, callbackData, bot, callbackQueryId);
            } else if (callbackData.startsWith("queue_app_")) {
                processQueuePositionCallback(chatId, user, callbackData, bot, callbackQueryId);
            } else if (callbackData.startsWith("inline_")) {
                processInlineButton(chatId, user, callbackData, bot, callbackQueryId);
            } else {
                // Если callback data не распознана
                bot.answerCallbackQuery(callbackQueryId, "❌ Неизвестная команда");
            }
        } catch (Exception e) {
            // Логируем ошибку
            System.err.println("Ошибка обработки callback: " + e.getMessage());
            bot.answerCallbackQuery(callbackQueryId, "❌ Ошибка обработки команды");
        }
    }

    private void showCreatingReferralCode(Long chatId, MyBot bot) {
        String message = "Введите описание для вашего реферального кода:";
        InlineKeyboardMarkup inlineKeyboard = createBackAndMainMenuKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);
    }

    private void processAdminApplicationActionCallback(Long chatId, User user, String callbackData, MyBot bot, String callbackQueryId) {
        try {
            String[] parts = callbackData.split("_");
            Long applicationId = Long.parseLong(parts[parts.length - 1]);

            Application application = applicationService.find(applicationId);
            if (application == null) {
                bot.answerCallbackQuery(callbackQueryId, "❌ Заявка не найдена");
                return;
            }

            // Определяем действие
            String action = callbackData.contains("inwork") ? "inwork" :
                    callbackData.contains("paid") ? "paid" :
                            callbackData.contains("completed") ? "completed" :
                                    callbackData.contains("cancel") ? "cancel" :
                                            callbackData.contains("free") ? "free" :
                                                    callbackData.contains("userinfo") ? "userinfo" : null;

            if (action == null) {
                bot.answerCallbackQuery(callbackQueryId, "❌ Неизвестное действие");
                return;
            }

            // Обработка действий со статусами
            switch (action) {
                case "inwork":
                    application.setStatus(ApplicationStatus.IN_WORK);
                    application.setAdminId(user.getId()); // Назначаем текущего админа
                    break;
                case "paid":
                    application.setStatus(ApplicationStatus.PAID);
                    break;
                case "completed":
                    application.setStatus(ApplicationStatus.COMPLETED);
                    updateUserStatistics(application);
                    referralService.processReferralReward(application);
                    // Удаляем сообщение у пользователя
                    if (application.getTelegramMessageId() != null) {
                        bot.deleteMessage(application.getUser().getTelegramId(), application.getTelegramMessageId());
                    }
                    break;
                case "cancel":
                    application.setStatus(ApplicationStatus.CANCELLED);
                    // Удаляем сообщение у пользователя
                    if (application.getTelegramMessageId() != null) {
                        bot.deleteMessage(application.getUser().getTelegramId(), application.getTelegramMessageId());
                    }
                    break;
                case "free":
                    application.setStatus(ApplicationStatus.FREE);
                    application.setAdminId(0); // Снимаем привязку к админу
                    break;
                case "userinfo":
                    // Показываем информацию о пользователе
                    bot.answerCallbackQuery(callbackQueryId, "👤 Загрузка информации...");
                    showUserDetails(chatId, application.getUser(), bot);
                    return;
            }

            applicationService.update(application);

            String statusMessage = String.format("✅ Статус заявки #%d изменен на: %s",
                    applicationId, application.getStatus().getDisplayName());
            bot.answerCallbackQuery(callbackQueryId, statusMessage);

            // Обновляем меню управления заявкой
            showAdminApplicationManagementMenu(chatId, user, application, bot);

        } catch (Exception e) {
            bot.answerCallbackQuery(callbackQueryId, "❌ Ошибка при обработке");
        }
    }

    private void processAddBonusBalance(Long chatId, User user, double amount, MyBot bot, String callbackQueryId) {
        try {
            user.setBonusBalance(user.getBonusBalance() + amount);
            userService.update(user);

            bot.answerCallbackQuery(callbackQueryId, "✅ Бонусный баланс пополнен на " + amount + " ₽");
            showUserBonusManagement(chatId, user, bot);

        } catch (Exception e) {
            bot.answerCallbackQuery(callbackQueryId, "❌ Ошибка при пополнении баланса");
        }
    }

    private void processResetBonusBalance(Long chatId, User user, MyBot bot, String callbackQueryId) {
        try {
            user.setBonusBalance((double) 0);
            userService.update(user);

            bot.answerCallbackQuery(callbackQueryId, "✅ Бонусный баланс обнулен");
            showUserBonusManagement(chatId, user, bot);

        } catch (Exception e) {
            bot.answerCallbackQuery(callbackQueryId, "❌ Ошибка при обнулении баланса");
        }
    }

    private void showAdminBonusBalanceSearch(Long chatId, MyBot bot) {
        String message = "💳 Управление бонусными балансами\n\n" +
                "Введите username (без @) или ID пользователя:";

        InlineKeyboardMarkup inlineKeyboard = createBackToAdminKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);
    }

    private void processBonusBalanceOperation(Long chatId, User admin, String callbackData, MyBot bot, String callbackQueryId) {
        try {
            String[] parts = callbackData.split("_");
            String operation = parts[2]; // "add", "remove", "reset"
            double amount = 0;
            Long targetUserId = Long.parseLong(parts[4]);

            User targetUser = userService.find(targetUserId);
            if (targetUser == null) {
                bot.answerCallbackQuery(callbackQueryId, "❌ Пользователь не найден");
                return;
            }

            switch (operation) {
                case "add":
                    amount = Double.parseDouble(parts[3]);
                    targetUser.setBonusBalance(targetUser.getBonusBalance() + amount);
                    break;
                case "remove":
                    amount = Double.parseDouble(parts[3]);
                    targetUser.setBonusBalance(Math.max(0, targetUser.getBonusBalance() - amount));
                    break;
                case "reset":
                    targetUser.setBonusBalance((double) 0);
                    break;
            }

            userService.update(targetUser);

            String message = String.format("✅ Бонусный баланс %s на %.2f ₽\nНовый баланс: %.2f ₽",
                    operation.equals("reset") ? "обнулен" : (operation.equals("add") ? "пополнен" : "списан"),
                    amount, targetUser.getBonusBalance());

            bot.answerCallbackQuery(callbackQueryId, message);
            showUserBonusManagement(chatId, targetUser, bot);

        } catch (Exception e) {
            bot.answerCallbackQuery(callbackQueryId, "❌ Ошибка при операции с балансом");
        }
    }

    private void processBonusBalanceReset(Long chatId, User admin, String callbackData, MyBot bot, String callbackQueryId) {
        try {
            Long targetUserId = Long.parseLong(callbackData.split("_")[3]);
            User targetUser = userService.find(targetUserId);

            if (targetUser == null) {
                bot.answerCallbackQuery(callbackQueryId, "❌ Пользователь не найден");
                return;
            }

            targetUser.setBonusBalance((double) 0);
            userService.update(targetUser);

            bot.answerCallbackQuery(callbackQueryId, "✅ Бонусный баланс обнулен");
            showUserBonusManagement(chatId, targetUser, bot);

        } catch (Exception e) {
            bot.answerCallbackQuery(callbackQueryId, "❌ Ошибка при обнулении баланса");
        }
    }

    private void processInlineButton(Long chatId, User user, String callbackData, MyBot bot, String callbackQueryId) {
        deletePreviousBotMessage(chatId, bot);

        if (callbackQueryId != null) {
            bot.answerCallbackQuery(callbackQueryId, "🔄 Обработка...");
        }

        // Обработка админских действий с заявками
        if (callbackData.startsWith("inline_admin_app_")) {
            processAdminApplicationActionCallback(chatId, user, callbackData, bot, callbackQueryId);
            return;
        }

        // Обработка бонусных операций админа
        if (callbackData.startsWith("inline_bonus_add_") || callbackData.startsWith("inline_bonus_remove_") ||
                callbackData.startsWith("inline_bonus_reset_")) {
            processBonusBalanceOperation(chatId, user, callbackData, bot, callbackQueryId);
            return;
        }

        // Обработка использования бонусов в заявке
        if (callbackData.startsWith("inline_bonus_use_")) {
            processBonusUsageFromCallback(chatId, user, callbackData, bot, callbackQueryId);
            return;
        }

        // ОСНОВНЫЕ КЕЙСЫ
        switch (callbackData) {
            // === ОСНОВНОЕ МЕНЮ ===
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
                    bot.sendMessage(chatId, "❌ Вы уже использовали реферальный код.");
                    return;
                }
                user.setState(UserState.ENTERING_REFERRAL_CODE);
                userService.update(user);
                showEnterReferralCode(chatId, bot);
                break;

            case "inline_create_referral":
                user.setState(UserState.CREATING_REFERRAL_CODE);
                userService.update(user);
                showCreatingReferralCode(chatId, bot);
                break;

            case "inline_admin":
                if (adminConfig.isAdmin(user.getId())) {
                    user.setState(UserState.ADMIN_MAIN_MENU);
                    userService.update(user);
                    showAdminMainMenu(chatId, bot);
                } else {
                    bot.sendMessage(chatId, "❌ Доступ запрещен");
                }
                break;

            // === МЕНЮ ПОКУПКИ ===
            case "inline_buy_rub":
                user.setState(UserState.ENTERING_BUY_AMOUNT_RUB);
                userService.update(user);
                currentOperation.put(user.getId(), "BUY_RUB");
                showEnterAmountMenu(chatId, "рублях", bot);
                break;

            case "inline_buy_btc":
                user.setState(UserState.ENTERING_BUY_AMOUNT_BTC);
                userService.update(user);
                currentOperation.put(user.getId(), "BUY_BTC");
                showEnterAmountMenu(chatId, "BTC", bot);
                break;

            case "inline_sell_amount":
                user.setState(UserState.ENTERING_SELL_AMOUNT);
                userService.update(user);
                currentOperation.put(user.getId(), "SELL");
                showEnterAmountMenu(chatId, "BTC", bot);
                break;

            // === НАВИГАЦИЯ ===
            case "inline_back":
                handleBackButton(chatId, user, bot);
                break;

            case "inline_main_menu":
                processMainMenu(chatId, user, bot);
                break;

            // === АДМИН ПАНЕЛЬ ===
            case "inline_admin_all":
                user.setState(UserState.ADMIN_VIEW_ALL_APPLICATIONS);
                userService.update(user);
                showAllApplications(chatId, user, bot);
                break;

            case "inline_admin_active":
                user.setState(UserState.ADMIN_VIEW_ACTIVE_APPLICATIONS);
                userService.update(user);
                showActiveApplications(chatId, user, bot);
                break;

            case "inline_admin_my_applications":
                user.setState(UserState.ADMIN_MY_APPLICATIONS);
                userService.update(user);
                showAdminMyApplications(chatId, user, bot);
                break;

            case "inline_admin_next":
                processNextApplication(chatId, user, bot);
                break;

            case "inline_admin_take":
                processTakeApplication(chatId, user, bot, callbackQueryId);
                break;

            case "inline_admin_search":
                user.setState(UserState.ADMIN_VIEW_USER_DETAILS);
                userService.update(user);
                showAdminUserSearch(chatId, bot);
                break;

            case "inline_admin_coupon":
                user.setState(UserState.ADMIN_CREATE_COUPON);
                userService.update(user);
                showCreateCouponMenu(chatId, bot);
                break;

            case "inline_admin_commission":
                user.setState(UserState.ADMIN_COMMISSION_SETTINGS);
                userService.update(user);
                showAdminCommissionSettings(chatId, user, bot);
                break;

            case "inline_admin_time":
                processAdminTimeFilter(chatId, user, bot);
                break;

            case "inline_admin_today":
                showApplicationsByPeriod(chatId, user, "today", bot);
                break;

            case "inline_admin_week":
                showApplicationsByPeriod(chatId, user, "week", bot);
                break;

            case "inline_admin_month":
                showApplicationsByPeriod(chatId, user, "month", bot);
                break;

            case "inline_admin_all_time":
                showAllApplications(chatId, user, bot);
                break;

            case "inline_admin_back":
                user.setState(UserState.ADMIN_MAIN_MENU);
                userService.update(user);
                showAdminMainMenu(chatId, bot);
                break;

            case "inline_admin_coupons":
                user.setState(UserState.ADMIN_VIEW_COUPONS);
                userService.update(user);
                showAdminCouponsMenu(chatId, bot);
                break;

            case "inline_admin_create_coupon_advanced":
                user.setState(UserState.ADMIN_CREATE_COUPON_ADVANCED);
                userService.update(user);
                showAdminCreateCouponAdvanced(chatId, bot);
                break;

            case "inline_admin_bonus_manage":
                user.setState(UserState.ADMIN_MANAGE_BONUS_BALANCE);
                userService.update(user);
                showAdminBonusBalanceSearch(chatId, bot);
                break;

            // === ПОЛЬЗОВАТЕЛЬСКИЕ ФУНКЦИИ ===
            case "inline_my_applications":
                user.setState(UserState.VIEWING_APPLICATIONS);
                userService.update(user);
                processViewingApplications(chatId, user, bot);
                break;

            case "inline_my_coupons":
                user.setState(UserState.VIEWING_COUPONS);
                userService.update(user);
                processViewingCoupons(chatId, user, bot);
                break;

            case "inline_calculator":
                user.setState(UserState.CALCULATOR_MENU);
                userService.update(user);
                showCalculatorMenu(chatId, user, bot);
                break;

            case "inline_rates":
                showExchangeRates(chatId, user, bot);
                break;

            case "inline_profile":
                showProfile(chatId, user, bot);
                break;

            case "inline_referral_system":
                user.setState(UserState.REFERRAL_MENU);
                userService.update(user);
                showReferralMenu(chatId, user, bot);
                break;

            case "inline_calculator_buy":
                user.setState(UserState.CALCULATOR_BUY);
                userService.update(user);
                showCalculatorEnterAmount(chatId, "покупки", bot);
                break;

            case "inline_calculator_sell":
                user.setState(UserState.CALCULATOR_SELL);
                userService.update(user);
                showCalculatorEnterAmount(chatId, "продажи", bot);
                break;

            // === СОЗДАНИЕ ЗАЯВКИ ===
            case "inline_vip_yes":
                Application applicationYes = temporaryApplications.get(user.getId());
                if (applicationYes != null) {
                    applicationYes.setIsVip(true);
                    applicationYes.setCalculatedGiveValue(applicationYes.getCalculatedGiveValue() + 300);
                    showBonusBalanceUsage(chatId, user, applicationYes, bot);
                    user.setState(UserState.USING_BONUS_BALANCE);
                    userService.update(user);
                }
                break;

            case "inline_vip_no":
                Application applicationNo = temporaryApplications.get(user.getId());
                if (applicationNo != null) {
                    applicationNo.setIsVip(false);
                    showBonusBalanceUsage(chatId, user, applicationNo, bot);
                    user.setState(UserState.USING_BONUS_BALANCE);
                    userService.update(user);
                }
                break;

            case "inline_apply_coupon":
                user.setState(UserState.APPLYING_COUPON_FINAL);
                userService.update(user);
                showEnterCouponCode(chatId, bot);
                break;

            case "inline_skip_coupon":
                Application applicationSkip = temporaryApplications.get(user.getId());
                if (applicationSkip != null) {
                    showFinalApplicationConfirmation(chatId, user, applicationSkip, bot);
                }
                break;

            case "inline_confirm_application":
                Application applicationConfirm = temporaryApplications.get(user.getId());
                if (applicationConfirm != null) {
                    createApplicationFinal(chatId, user, applicationConfirm, bot);
                }
                break;

            case "inline_cancel_application":
                temporaryApplications.remove(user.getId());
                bot.sendMessage(chatId, "❌ Создание заявки отменено.");
                processMainMenu(chatId, user, bot);
                break;

            default:
                bot.sendMessage(chatId, "❌ Неизвестная команда");
                processMainMenu(chatId, user, bot);
        }
    }

    private void processBonusUsageFromCallback(Long chatId, User user, String callbackData, MyBot bot, String callbackQueryId) {
        try {
            String[] parts = callbackData.split("_");
            String amountType = parts[3]; // "50", "100", "200", "500", "max", "skip"

            String amountText;
            switch (amountType) {
                case "50":
                    amountText = "50";
                    break;
                case "100":
                    amountText = "100";
                    break;
                case "200":
                    amountText = "200";
                    break;
                case "500":
                    amountText = "500";
                    break;
                case "max":
                    Application appMax = temporaryApplications.get(user.getId());
                    if (appMax != null) {
                        double maxBonus = Math.min(user.getBonusBalance(), appMax.getCalculatedGiveValue());
                        amountText = String.valueOf(maxBonus);
                    } else {
                        amountText = "0";
                    }
                    break;
                case "skip":
                    amountText = "0";
                    break;
                default:
                    amountText = "0";
                    break;
            }

            processBonusUsage(chatId, user, amountText, bot, callbackQueryId);

        } catch (Exception e) {
            bot.answerCallbackQuery(callbackQueryId, "❌ Ошибка при использовании бонусов");
        }
    }

    private void processAdminMyApplicationsSelection(Long chatId, User user, String text, MyBot bot) {
        try {
            int listNumber = Integer.parseInt(text);
            List<Application> myApplications = applicationService.findByAdminId(user.getId());

            if (listNumber < 1 || listNumber > myApplications.size()) {
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                        "❌ Неверный номер заявки", createBackToAdminKeyboard()));
                return;
            }

            Application application = myApplications.get(listNumber - 1);
            selectedApplication.put(user.getId(), application.getId());
            user.setState(UserState.ADMIN_VIEWING_APPLICATION_DETAILS);
            userService.update(user);

            // ПОКАЗЫВАЕМ МЕНЮ УПРАВЛЕНИЯ
            showAdminApplicationManagementMenu(chatId, user, application, bot);

        } catch (NumberFormatException e) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                    "❌ Введите корректный номер", createBackToAdminKeyboard()));
        }
    }


    private void showAdminMyApplications(Long chatId, User admin, MyBot bot) {
        List<Application> myApplications = applicationService.findByAdminId(admin.getId());

        if (myApplications.isEmpty()) {
            String message = "📭 У вас нет взятых заявок.";
            InlineKeyboardMarkup inlineKeyboard = createBackToAdminKeyboard();
            int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
            lastMessageId.put(chatId, messageId);
            return;
        }

        StringBuilder message = new StringBuilder("👨‍💼 Ваши заявки:\n\n");

        for (int i = 0; i < myApplications.size(); i++) {
            Application app = myApplications.get(i);
            String userInfo = String.format("@%s (ID: %d)",
                    app.getUser().getUsername() != null ? app.getUser().getUsername() : "нет_username",
                    app.getUser().getId());

            message.append(String.format("""
                        %d. 🆔 #%d | %s
                        👤 %s
                        %s
                        💰 %.2f ₽ | %s
                        📊 %s
                        🕒 %s
                        --------------------
                        """,
                    i + 1,
                    app.getId(),
                    app.getTitle(),
                    app.getUser().getFirstName(),
                    userInfo,
                    app.getCalculatedGiveValue(),
                    app.getIsVip() ? "👑 VIP" : "🔹 Обычная",
                    app.getStatus().getDisplayName(),
                    app.getCreatedAt().format(DateTimeFormatter.ofPattern("dd.MM.yy HH:mm"))
            ));
        }

        message.append("\nВведите номер заявки из списка для управления:");

        InlineKeyboardMarkup inlineKeyboard = createBackToAdminKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message.toString(), inlineKeyboard);
        lastMessageId.put(chatId, messageId);
    }



    private void processTakeApplication(Long chatId, User admin, MyBot bot, String callbackQueryId) {
        List<Application> activeApplications = applicationService.findActiveApplications();

        if (activeApplications.isEmpty()) {
            if (callbackQueryId != null) {
                bot.answerCallbackQuery(callbackQueryId, "📭 Нет активных заявок");
            }
            return;
        }

        // Берем первую заявку из отсортированного списка (VIP сначала)
        Application nextApplication = activeApplications.stream()
                .sorted(Comparator.comparing(Application::getIsVip).reversed()
                        .thenComparing(Application::getCreatedAt))
                .findFirst()
                .orElse(null);

        if (nextApplication == null) {
            if (callbackQueryId != null) {
                bot.answerCallbackQuery(callbackQueryId, "❌ Ошибка при поиске заявки");
            }
            return;
        }

        // Устанавливаем статус "В работе" и привязываем админа
        nextApplication.setStatus(ApplicationStatus.IN_WORK);
        nextApplication.setAdminId(admin.getId());
        applicationService.update(nextApplication);

        selectedApplication.put(admin.getId(), nextApplication.getId());
        admin.setState(UserState.ADMIN_VIEWING_APPLICATION_DETAILS);
        userService.update(admin);

        if (callbackQueryId != null) {
            bot.answerCallbackQuery(callbackQueryId, "✅ Заявка взята в работу");
        }

        // ПОКАЗЫВАЕМ МЕНЮ УПРАВЛЕНИЯ ВМЕСТО ПРОСТО ДЕТАЛЕЙ
        showAdminApplicationManagementMenu(chatId, admin, nextApplication, bot);
    }


    private void processBonusUsage(Long chatId, User user, String amountText, MyBot bot, String callbackQueryId) {
        Application application = temporaryApplications.get(user.getId());

        if (application == null) {
            if (callbackQueryId != null) {
                bot.answerCallbackQuery(callbackQueryId, "❌ Заявка не найдена");
            }
            processMainMenu(chatId, user, bot);
            return;
        }

        try {
            double bonusAmount = Double.parseDouble(amountText);

            // Валидация
            if (bonusAmount < 0) {
                String errorMsg = "❌ Сумма не может быть отрицательной";
                if (callbackQueryId != null) {
                    bot.answerCallbackQuery(callbackQueryId, errorMsg);
                } else {
                    lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, errorMsg,
                            createBonusUsageKeyboard(user.getBonusBalance())));
                }
                return;
            }

            if (bonusAmount > user.getBonusBalance()) {
                String errorMsg = "❌ Недостаточно бонусного баланса";
                if (callbackQueryId != null) {
                    bot.answerCallbackQuery(callbackQueryId, errorMsg);
                } else {
                    lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, errorMsg,
                            createBonusUsageKeyboard(user.getBonusBalance())));
                }
                return;
            }

            if (bonusAmount > application.getCalculatedGiveValue()) {
                String errorMsg = "❌ Нельзя списать бонусов больше суммы заявки";
                if (callbackQueryId != null) {
                    bot.answerCallbackQuery(callbackQueryId, errorMsg);
                } else {
                    lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, errorMsg,
                            createBonusUsageKeyboard(user.getBonusBalance())));
                }
                return;
            }

            // Применяем бонусный баланс
            application.setUsedBonusBalance(bonusAmount);
            application.setCalculatedGiveValue(application.getCalculatedGiveValue() - bonusAmount);

            if (callbackQueryId != null) {
                bot.answerCallbackQuery(callbackQueryId, "✅ Бонусный баланс применен");
            }

            // Переходим к применению купонов
            showCouponApplication(chatId, user, application, bot);
            user.setState(UserState.APPLYING_COUPON_FINAL);
            userService.update(user);

        } catch (NumberFormatException e) {
            String errorMsg = "❌ Пожалуйста, введите корректное число";
            if (callbackQueryId != null) {
                bot.answerCallbackQuery(callbackQueryId, errorMsg);
            } else {
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, errorMsg,
                        createBonusUsageKeyboard(user.getBonusBalance())));
            }
        }
    }


    private void processTakeNextApplication(Long chatId, User admin, MyBot bot, String callbackQueryId) {
        List<Application> activeApplications = applicationService.findActiveApplications();

        if (activeApplications.isEmpty()) {
            bot.answerCallbackQuery(callbackQueryId, "📭 Нет активных заявок");
            return;
        }

        // Берем первую заявку из отсортированного списка (VIP сначала)
        Application nextApplication = activeApplications.stream()
                .sorted(Comparator.comparing(Application::getIsVip).reversed()
                        .thenComparing(Application::getCreatedAt))
                .findFirst()
                .orElse(null);

        if (nextApplication == null) {
            bot.answerCallbackQuery(callbackQueryId, "❌ Ошибка при поиске заявки");
            return;
        }

        // Устанавливаем статус "В работе"
        nextApplication.setStatus(ApplicationStatus.IN_WORK);
        applicationService.update(nextApplication);

        selectedApplication.put(admin.getId(), nextApplication.getId());
        admin.setState(UserState.ADMIN_VIEWING_APPLICATION_DETAILS);
        userService.update(admin);

        bot.answerCallbackQuery(callbackQueryId, "✅ Заявка взята в работу");
        showAdminApplicationDetails(chatId, admin, nextApplication, bot);
    }

    private void showEnterCouponCode(Long chatId, MyBot bot) {
        String message = "🎫 Введите код купона:";

        InlineKeyboardMarkup inlineKeyboard = createBackAndMainMenuKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);
    }

    private void showAdminUserSearch(Long chatId, MyBot bot) {
        String message = "Введите username (без @) или ID пользователя:";

        InlineKeyboardMarkup inlineKeyboard = createBackToAdminKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);
    }

    private void showCalculatorEnterAmount(Long chatId, String operation, MyBot bot) {
        String message = String.format("💎 Введите сумму для расчета %s:", operation);

        InlineKeyboardMarkup inlineKeyboard = createBackAndMainMenuKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);
    }


    private void showEnterReferralCode(Long chatId, MyBot bot) {
        String message = "Введите реферальный код:";

        InlineKeyboardMarkup inlineKeyboard = createEnterReferralCodeInlineKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);
    }

    private InlineKeyboardMarkup createEnterReferralCodeInlineKeyboard() {
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

            // ВОЗВРАЩАЕМ БОНУСНЫЙ БАЛАНС ПРИ ОТМЕНЕ
            if (application.getUsedBonusBalance() > 0) {
                user.setBonusBalance(user.getBonusBalance() + application.getUsedBonusBalance());
                userService.update(user);
            }

            applicationService.update(application);

            // УДАЛЯЕМ сообщение с заявкой если оно есть
            if (application.getTelegramMessageId() != null) {
                bot.deleteMessage(chatId, application.getTelegramMessageId());
            }

            bot.answerCallbackQuery(callbackQueryId, "✅ Заявка отменена");

            // Отправляем уведомление об отмене
            String cancelMessage = "❌ Заявка #" + applicationId + " отменена.";
            if (application.getUsedBonusBalance() > 0) {
                cancelMessage += String.format("\n💸 Вам возвращен бонусный баланс: %.2f ₽", application.getUsedBonusBalance());
            }
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

            int queuePosition = calculateQueuePosition(application) + 10;
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
                🚀 Быстрый и надёжный обмен RUB → BTC / LTC / XMR\s
                ⚖️ Честные курсы, без задержек и скрытых комиссий.
                💸 БОНУС: после каждой операции получаете 3% кешбэк на свой баланс!
                
                📲 Как всё работает:\s
                1️⃣ Нажмите 💵 Купить или 💰 Продать\s
                2️⃣ Введите нужную сумму 🪙\s
                3️⃣ Укажите свой кошелёк 🔐
                4️⃣ Выберите приоритет (🔹обычный / 👑 VIP)\s
                5️⃣ Подтвердите заявку ✅\s
                6️⃣ Если готовы оплачивать — перешлите заявку оператору ☎️
                
                ⚙️ Дополнительная информация:\s
                👑 VIP-приоритет — всего 300₽, заявка проходит мгновенно
                📊 Загруженность сети BTC: низкая 🚥\s
                🕒 Время подтверждения: 5–20 минут\s
                💬 Отзывы клиентов: t.me/CosaNostraChange24/4\s
                🧰 Техподдержка 24/7: @SUP_CN  всегда онлайн, решим любой вопрос 🔧
                
                💀Чат: https://t.me/CosaNostraChange24
                ☎️оператор: @SUP_CN
                📌Help: @CN_LUCKYY @CN_PAUL
                🔈SMM: @CN_ACCARDO
                🔨Вопросы по боту: @@CN_ADONIS
                
                COSA NOSTRA CHANGE — тут уважают тех, кто ценит скорость, честность и результат. ⚡️
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
                    "❌ Минимальная сумма заявки 1000 рублей", createEnterAmountInlineKeyboard()));
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

        InlineKeyboardMarkup keyboard = createVipConfirmationInlineKeyboard();
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
                // ДОБАВЛЯЕМ VIP стоимость
                application.setCalculatedGiveValue(application.getCalculatedGiveValue() + 300);
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
                        "❌ Пожалуйста, выберите вариант приоритета", createVipConfirmationInlineKeyboard()));
                return;
        }

        // ПЕРЕХОДИМ К ИСПОЛЬЗОВАНИЮ БОНУСНОГО БАЛАНСА
        showBonusBalanceUsage(chatId, user, application, bot);
        user.setState(UserState.USING_BONUS_BALANCE);
        userService.update(user);
    }

    private void showBonusBalanceUsage(Long chatId, User user, Application application, MyBot bot) {
        double availableBonus = user.getBonusBalance();
        double maxUsable = Math.min(availableBonus, application.getCalculatedGiveValue());

        String message = String.format("""
        💰 Ваш бонусный баланс: %.2f ₽
        
        Вы можете списать до %.2f ₽ для уменьшения суммы заявки.
        
        Введите сумму бонусного баланса для списания:
        (или 0, если не хотите использовать)
        
        💡 Доступные варианты:
        • Введите число (например: 100)
        • Нажмите кнопку "Максимум" для списания %.2f ₽
        • Нажмите "⏭️ Пропустить" для продолжения без списания
        """, availableBonus, maxUsable, maxUsable);

        InlineKeyboardMarkup inlineKeyboard = createBonusUsageKeyboard(maxUsable);
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);
    }

    private InlineKeyboardMarkup createBonusUsageKeyboard(double maxUsable) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // Кнопки с рекомендуемыми суммами
        if (maxUsable >= 50) {
            List<InlineKeyboardButton> row1 = new ArrayList<>();
            row1.add(createInlineButton("50 ₽", "inline_bonus_use_50"));
            row1.add(createInlineButton("100 ₽", "inline_bonus_use_100"));
            rows.add(row1);
        }

        if (maxUsable >= 200) {
            List<InlineKeyboardButton> row2 = new ArrayList<>();
            row2.add(createInlineButton("200 ₽", "inline_bonus_use_200"));
            row2.add(createInlineButton("500 ₽", "inline_bonus_use_500"));
            rows.add(row2);
        }

        List<InlineKeyboardButton> row3 = new ArrayList<>();
        row3.add(createInlineButton("Максимум (" + String.format("%.0f", maxUsable) + " ₽)", "inline_bonus_use_max"));

        List<InlineKeyboardButton> row4 = new ArrayList<>();
        row4.add(createInlineButton("⏭️ Пропустить", "inline_bonus_use_skip"));

        List<InlineKeyboardButton> row5 = new ArrayList<>();
        row5.add(createInlineButton("🔙 Назад", "inline_back"));

        rows.add(row3);
        rows.add(row4);
        rows.add(row5);

        markup.setKeyboard(rows);
        return markup;
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
                        "🎫 Введите код купона:", createBackInlineKeyboard()));
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
                    createCouponApplicationInlineKeyboard()));
        }
    }

    private void showFinalApplicationConfirmation(Long chatId, User user, Application application, MyBot bot) {
        // Рассчитываем финальную стоимость
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
            calculatedAmount = rubAmount - commission;

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

        String operationType = application.getUserValueGetType() == ValueType.BTC ? "покупки" : "продажи";
        String walletLabel = application.getUserValueGetType() == ValueType.BTC ? "🔐 Кошелек BTC" : "💳 Реквизиты для выплаты";

        String applicationDetails = String.format("""
                        📋 Ваша заявка на %s:
                        
                        %s Отдаете: %s %s
                        💰 Получаете: %s %s
                        💸 Комиссия: учтена в расчете
                        %s
                        %s
                        💵 Итоговая сумма: %s
                        %s: %s
                        🕰️ Срок действия: 40 минут
                        
                        Подтверждаете создание заявки?
                        """,
                operationType,
                application.getUserValueGetType() == ValueType.BTC ? "💸" : "₿",
                application.getUserValueGetType() == ValueType.BTC ?
                        formatRubAmount(application.getUserValueGiveValue()) : formatBtcAmount(application.getUserValueGiveValue()),
                application.getUserValueGetType() == ValueType.BTC ? "₽" : "BTC",
                application.getUserValueGetType() == ValueType.BTC ?
                        formatBtcAmount(finalAmount) : Double.valueOf(formatRubAmount(calculatedAmount)),
                application.getUserValueGetType() == ValueType.BTC ? "BTC" : "₽",
                application.getIsVip() ? "👑 VIP-приоритет: +300 ₽" : "🔹 Приоритет: обычный",
                application.getAppliedCoupon() != null ?
                        String.format("🎫 Купон: %s", application.getAppliedCoupon().getCode()) : "",

               formatRubAmount(calculatedAmount - application.getUsedBonusBalance()),
                    walletLabel,
                application.getWalletAddress()
        );

        InlineKeyboardMarkup inlineKeyboard = createFinalConfirmationInlineKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, applicationDetails, inlineKeyboard);
        lastMessageId.put(chatId, messageId);
    }

    private InlineKeyboardMarkup createFinalConfirmationInlineKeyboard() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton confirmButton = new InlineKeyboardButton();
        confirmButton.setText("✅ Подтвердить");
        confirmButton.setCallbackData("inline_confirm_application");
        row1.add(confirmButton);

        InlineKeyboardButton cancelButton = new InlineKeyboardButton();
        cancelButton.setText("❌ Отменить");
        cancelButton.setCallbackData("inline_cancel_application");
        row1.add(cancelButton);

        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton backButton = new InlineKeyboardButton();
        backButton.setText("🔙 Назад");
        backButton.setCallbackData("inline_back");
        row2.add(backButton);

        rows.add(row1);
        rows.add(row2);

        markup.setKeyboard(rows);
        return markup;
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

        InlineKeyboardMarkup keyboard = createVipConfirmationInlineKeyboard();
        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, keyboard));

        user.setState(UserState.CONFIRMING_VIP);
        userService.update(user);
    }

    private void processUsingBonusBalance(Long chatId, User user, String text, MyBot bot) {
        Application application = temporaryApplications.get(user.getId());

        if (application == null) {
            processMainMenu(chatId, user, bot);
            return;
        }

        try {
            double bonusAmount;

            if (text.equalsIgnoreCase("0") || text.equals("⏭️ Не использовать")) {
                bonusAmount = 0;
            } else {
                bonusAmount = Double.parseDouble(text.replace(" ₽", "").trim());

                if (bonusAmount < 0) {
                    throw new IllegalArgumentException("Сумма не может быть отрицательной");
                }

                if (bonusAmount > user.getBonusBalance()) {
                    throw new IllegalArgumentException("Недостаточно бонусного баланса");
                }

                if (bonusAmount > application.getCalculatedGiveValue()) {
                    throw new IllegalArgumentException("Нельзя использовать бонусов больше суммы заявки");
                }
            }

            // Применяем бонусный баланс
            application.setUsedBonusBalance(bonusAmount);
            application.setCalculatedGiveValue(application.getCalculatedGiveValue() - bonusAmount);

            // Обновляем пользователя (списываем бонусы)
            if (bonusAmount > 0) {
                user.setBonusBalance(user.getBonusBalance() - bonusAmount);
                userService.update(user);
            }

            // Переходим к подтверждению заявки
            showVipConfirmation(chatId, user, application, bot);
            user.setState(UserState.CONFIRMING_VIP);
            userService.update(user);

        } catch (NumberFormatException e) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                    "❌ Пожалуйста, введите корректное число", createBonusBalanceKeyboard(user.getBonusBalance())));
        } catch (IllegalArgumentException e) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                    "❌ " + e.getMessage(), createBonusBalanceKeyboard(user.getBonusBalance())));
        }
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

        if (text.equals("📞 Написать оператору @SUP_CN")) {
            String message = "📞 Связь с оператором: @SUP_CN";
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createMainMenuInlineKeyboard(user)));
            return;
        }

        // Основные кнопки главного меню - ДОБАВЛЯЕМ ОБРАБОТКУ КНОПОК
        switch (text) {
            case "💰 Купить":
            case "💰 Купить BTC":  // ДОБАВЛЕНО
                user.setState(UserState.BUY_MENU);
                userService.update(user);
                showBuyMenu(chatId, bot);
                break;
            case "💸 Продать":
            case "💸 Продать BTC":  // ДОБАВЛЕНО
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
                            "❌ Вы уже использовали реферальный код.", createMainMenuInlineKeyboard(user)));
                    return;
                }
                user.setState(UserState.ENTERING_REFERRAL_CODE);
                userService.update(user);
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                        "Введите реферальный код:", createBackInlineKeyboard()));
                break;
            case "👨‍💼 Админ панель":
                if (adminConfig.isAdmin(user.getId())) {
                    user.setState(UserState.ADMIN_MAIN_MENU);
                    userService.update(user);
                    showAdminMainMenu(chatId, bot);
                } else {
                    lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, "❌ Доступ запрещен", createMainMenuInlineKeyboard(user)));
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
                // Если команда не распознана, проверяем inline callback данные
                if (text.startsWith("inline_")) {
                    processInlineButton(chatId, user, text, bot, null);
                } else {
                    lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                            "❌ Пожалуйста, используйте кнопки меню", createMainMenuInlineKeyboard(user)));
                }
        }
    }


    private void showCommissionInfo(Long chatId, User user, MyBot bot) {
        String message = String.format("""
                        💰 Актуальные комиссии:
                        
                        • 1000-1999 ₽: %.1f%%
                        • 2000-2999 ₽: %.1f%%
                        • 3000-4999 ₽: %.1f%%
                        • 5000-9999 ₽: %.1f%%
                        • 10000-14999 ₽: %.1f%%
                        • 15000-19999 ₽: %.1f%%
                        • 20000-24999 ₽: %.1f%%
                        • 25000-29999 ₽: %.1f%%
                        • 30000+ ₽: %.1f%%
                        
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
                commissionConfig.getCommissionPercent(5000),
                commissionConfig.getCommissionPercent(10000),
                commissionConfig.getCommissionPercent(15000),
                commissionConfig.getCommissionPercent(20000),
                commissionConfig.getCommissionPercent(25000),
                commissionConfig.getCommissionPercent(30000)
        );

        InlineKeyboardMarkup inlineKeyboard = createCommissionInfoInlineKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);
    }

    private InlineKeyboardMarkup createCommissionInfoInlineKeyboard() {
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

    private void showAdminMainMenu(Long chatId, MyBot bot) {
        String message = "👨‍💼 Админ панель\n\nВыберите действие:";
        InlineKeyboardMarkup inlineKeyboard = createAdminMainMenuInlineKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);
    }

    private void showBuyMenu(Long chatId, MyBot bot) {
        String message = """
        💰 Покупка Bitcoin
        
        Вы хотите купить Bitcoin за рубли.
        
        После ввода суммы вы увидите:
        • Сколько рублей вы отдадите
        • Сколько Bitcoin получите
        • Комиссию за операцию
        
        Выберите способ ввода суммы:
        """;

        InlineKeyboardMarkup keyboard = createBuyMenuInlineKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, keyboard);
        lastMessageId.put(chatId, messageId);
    }

    private void showSellMenu(Long chatId, MyBot bot) {
        String message = "💸 Продажа Bitcoin\n\n" +
                "Вы хотите продать Bitcoin за рубли.\n\n" +
                "После ввода суммы вы увидите:\n" +
                "• Сколько Bitcoin вы отдадите\n" +
                "• Сколько рублей получите";

        InlineKeyboardMarkup inlineKeyboard = createSellMenuInlineKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);
    }

    private void processBuyMenu(Long chatId, User user, String text, MyBot bot) {
        if ("Ввести сумму в RUB".equals(text)) {
            user.setState(UserState.ENTERING_BUY_AMOUNT_RUB);
            userService.update(user);
            currentOperation.put(user.getId(), "BUY_RUB");

            String message = "💎 Введите сумму в рублях, которую хотите потратить на покупку Bitcoin:";
            InlineKeyboardMarkup inlineKeyboard = createEnterAmountInlineKeyboard();
            int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
            lastMessageId.put(chatId, messageId);
        } else if ("Ввести количество в BTC".equals(text)) {
            user.setState(UserState.ENTERING_BUY_AMOUNT_BTC);
            userService.update(user);
            currentOperation.put(user.getId(), "BUY_BTC");

            String message = "💎 Введите количество Bitcoin, которое хотите купить:";
            InlineKeyboardMarkup inlineKeyboard = createEnterAmountInlineKeyboard();
            int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
            lastMessageId.put(chatId, messageId);
        } else if ("🔙 Главное меню".equals(text)) {
            processMainMenu(chatId, user, bot);
        } else {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, "❌ Пожалуйста, используйте кнопки", createBuyMenuInlineKeyboard()));
        }
    }

    private void processSellMenu(Long chatId, User user, String text, MyBot bot) {
        if ("Ввести сумму".equals(text)) {
            user.setState(UserState.ENTERING_SELL_AMOUNT);
            userService.update(user);
            currentOperation.put(user.getId(), "SELL");

            String message = "💎 Введите количество Bitcoin, которое хотите продать:";
            InlineKeyboardMarkup keyboard = createEnterAmountInlineKeyboard();
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, keyboard));
        } else if ("🔙 Главное меню".equals(text)) {
            processMainMenu(chatId, user, bot);
        } else {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, "❌ Пожалуйста, используйте кнопки", createSellMenuInlineKeyboard()));
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
                                "❌ Количество должно быть больше 0", createEnterAmountInlineKeyboard()));
                        return;
                    }

                    // РАСЧЕТ ЗНАЧЕНИЙ ДЛЯ ПРОДАЖИ
                    double btcPrice = cryptoPriceService.getCurrentPrice("BTC", "RUB");
                    double rubAmount = btcAmount * btcPrice;
                    double commission = commissionService.calculateCommission(rubAmount);
                    double totalReceived = rubAmount - commission;

                    // Создаем временную заявку для продажи С РАССЧИТАННЫМИ ЗНАЧЕНИЯМИ
                    Application application = new Application();
                    application.setUser(user);
                    application.setUserValueGetType(ValueType.RUB);
                    application.setUserValueGiveType(ValueType.BTC);
                    application.setUserValueGiveValue(btcAmount);    // Продаваемое количество BTC
                    application.setUserValueGetValue(totalReceived); // Получаемая сумма RUB
                    application.setCalculatedGiveValue(btcAmount);   // Для отображения
                    application.setCalculatedGetValue(totalReceived); // Для отображения
                    application.setTitle("Продажа BTC за RUB");
                    application.setStatus(ApplicationStatus.FREE);

                    temporaryApplications.put(user.getId(), application);

                    // Переходим к вводу кошелька (для получения RUB)
                    String message = "🔐 Введите номер карты или счет для получения рублей:";
                    InlineKeyboardMarkup keyboard = createBackInlineKeyboard();
                    lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, keyboard));

                    user.setState(UserState.ENTERING_WALLET);
                    userService.update(user);

                } catch (NumberFormatException e) {
                    lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                            "❌ Пожалуйста, введите корректное число", createEnterAmountInlineKeyboard()));
                }
        }
    }

    private void showExchangeRates(Long chatId, User user, MyBot bot) {
        double btcPrice = cryptoPriceService.getCurrentPrice("BTC", "RUB");
        double ethPrice = cryptoPriceService.getCurrentPrice("ETH", "RUB");

        String message = String.format("""
                📊 Текущие курсы:
                
                ₿ Bitcoin (BTC): %s
                Ξ Ethereum (ETH): %s
                
                *Курсы обновляются автоматически
                """, formatRubAmount(btcPrice), formatRubAmount(ethPrice));

        InlineKeyboardMarkup inlineKeyboard = createBackAndMainMenuKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);
    }

    private InlineKeyboardMarkup createBackAndMainMenuKeyboard() {
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

    private InlineKeyboardMarkup createBackToAdminKeyboard() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton backButton = new InlineKeyboardButton();
        backButton.setText("🔙 Назад");
        backButton.setCallbackData("inline_admin_back");
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
                        "🎫 Введите код купона:", createBackInlineKeyboard()));
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
                        "Введите сумму:", createEnterAmountInlineKeyboard()));
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

            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createMainMenuInlineKeyboard(user)));

            user.setState(UserState.MAIN_MENU);
            userService.update(user);

        } catch (IllegalArgumentException e) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                    "❌ " + e.getMessage() + "\n\nПопробуйте другой код или нажмите 'Пропустить'",
                    createCouponApplicationInlineKeyboard()));
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

        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createMainMenuInlineKeyboard(user)));

        user.setState(UserState.MAIN_MENU);
        userService.update(user);
    }

    private void showOtherMenu(Long chatId, User user, MyBot bot) {
        String message = "⚙️ Прочее\n\nВыберите раздел:";

        InlineKeyboardMarkup inlineKeyboard = createOtherMenuInlineKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);
    }

    private InlineKeyboardMarkup createOtherMenuInlineKeyboard() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton applicationsButton = new InlineKeyboardButton();
        applicationsButton.setText("📋 Мои заявки");
        applicationsButton.setCallbackData("inline_my_applications");
        row1.add(applicationsButton);

        InlineKeyboardButton couponsButton = new InlineKeyboardButton();
        couponsButton.setText("🎫 Мои купоны");
        couponsButton.setCallbackData("inline_my_coupons");
        row1.add(couponsButton);

        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton calculatorButton = new InlineKeyboardButton();
        calculatorButton.setText("🧮 Калькулятор");
        calculatorButton.setCallbackData("inline_calculator");
        row2.add(calculatorButton);

        InlineKeyboardButton ratesButton = new InlineKeyboardButton();
        ratesButton.setText("📊 Курсы");
        ratesButton.setCallbackData("inline_rates");
        row2.add(ratesButton);

        List<InlineKeyboardButton> row3 = new ArrayList<>();
        InlineKeyboardButton profileButton = new InlineKeyboardButton();
        profileButton.setText("👤 Профиль");
        profileButton.setCallbackData("inline_profile");
        row3.add(profileButton);

        InlineKeyboardButton referralButton = new InlineKeyboardButton();
        referralButton.setText("📈 Реферальная система");
        referralButton.setCallbackData("inline_referral_system");
        row3.add(referralButton);

        List<InlineKeyboardButton> row4 = new ArrayList<>();
        InlineKeyboardButton backButton = new InlineKeyboardButton();
        backButton.setText("🔙 Назад");
        backButton.setCallbackData("inline_back");
        row4.add(backButton);

        List<InlineKeyboardButton> row5 = new ArrayList<>();
        InlineKeyboardButton mainMenuButton = new InlineKeyboardButton();
        mainMenuButton.setText("💎 Главное меню");
        mainMenuButton.setCallbackData("inline_main_menu");
        row5.add(mainMenuButton);

        rows.add(row1);
        rows.add(row2);
        rows.add(row3);
        rows.add(row4);
        rows.add(row5);

        markup.setKeyboard(rows);
        return markup;
    }

    private void processViewingCoupons(Long chatId, User user, MyBot bot) {
        List<Coupon> userCoupons = couponService.getUserCoupons(user.getId());

        if (userCoupons.isEmpty()) {
            String message = "🎫 У вас пока нет доступных купонов.";
            InlineKeyboardMarkup inlineKeyboard = createBackAndMainMenuKeyboard();
            int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
            lastMessageId.put(chatId, messageId);
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
            InlineKeyboardMarkup inlineKeyboard = createBackAndMainMenuKeyboard();
            int messageId = bot.sendMessageWithInlineKeyboard(chatId, response.toString(), inlineKeyboard);
            lastMessageId.put(chatId, messageId);
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
            InlineKeyboardMarkup inlineKeyboard = createBackAndMainMenuKeyboard();
            int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
            lastMessageId.put(chatId, messageId);
        } else {
            StringBuilder response = new StringBuilder("📋 Ваши последние заявки:\n\n");

            for (int i = 0; i < recentApplications.size(); i++) {
                Application app = recentApplications.get(i);
                response.append(String.format("""
                                🆔 Заявка #%d
                                📊 Статус: %s
                                💰 Тип: %s
                                💸 Сумма: %s
                                ₿ Bitcoin: %s
                                📅 Создана: %s
                                """,
                        app.getId(),
                        app.getStatus().getDisplayName(),
                        app.getTitle(),
                        formatRubAmount(app.getCalculatedGiveValue()),
                        formatBtcAmount(app.getCalculatedGetValue()),
                        app.getCreatedAt().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
                ));

                if (app.getAppliedCoupon() != null) {
                    response.append(String.format("🎫 Купон: %s\n", app.getAppliedCoupon().getCode()));
                }

                response.append("--------------------\n");
            }

            InlineKeyboardMarkup inlineKeyboard = createBackAndMainMenuKeyboard();
            int messageId = bot.sendMessageWithInlineKeyboard(chatId, response.toString(), inlineKeyboard);
            lastMessageId.put(chatId, messageId);
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
                                "❌ Минимальная сумма заявки 1000 рублей", createEnterAmountInlineKeyboard()));
                        return;
                    }

                    // РАСЧЕТ ЗНАЧЕНИЙ ПЕРЕД СОЗДАНИЕМ ЗАЯВКИ
                    double btcPrice = cryptoPriceService.getCurrentPrice("BTC", "RUB");
                    double btcAmount = rubAmount / btcPrice;
                    double commission = commissionService.calculateCommission(rubAmount);
                    double totalAmount = commissionService.calculateTotalWithCommission(rubAmount);

                    // Создаем временную заявку С РАССЧИТАННЫМИ ЗНАЧЕНИЯМИ
                    Application application = new Application();
                    application.setUser(user);
                    application.setUserValueGetType(ValueType.BTC);
                    application.setUserValueGiveType(ValueType.RUB);
                    application.setUserValueGiveValue(rubAmount); // Исходная сумма
                    application.setUserValueGetValue(btcAmount);  // Рассчитанное количество BTC
                    application.setCalculatedGetValue(btcAmount); // Для отображения
                    application.setCalculatedGiveValue(totalAmount); // Сумма с комиссией
                    application.setTitle("Покупка BTC за RUB");
                    application.setStatus(ApplicationStatus.FREE);

                    temporaryApplications.put(user.getId(), application);

                    // Переходим к вводу кошелька
                    String message = "🔐 Теперь введите адрес Bitcoin-кошелька, на который поступит крипта:";
                    InlineKeyboardMarkup keyboard = createBackInlineKeyboard();
                    lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, keyboard));

                    user.setState(UserState.ENTERING_WALLET);
                    userService.update(user);

                } catch (NumberFormatException e) {
                    lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                            "❌ Пожалуйста, введите корректное число", createEnterAmountInlineKeyboard()));
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
                        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                                "❌ Количество должно быть больше 0", createEnterAmountInlineKeyboard()));
                        return;
                    }

                    // РАСЧЕТ ЗНАЧЕНИЙ
                    double btcPrice = cryptoPriceService.getCurrentPrice("BTC", "RUB");
                    double rubAmount = btcAmount * btcPrice;
                    double commission = commissionService.calculateCommission(rubAmount);
                    double totalAmount = commissionService.calculateTotalWithCommission(rubAmount);

                    // Создаем временную заявку С РАССЧИТАННЫМИ ЗНАЧЕНИЯМИ
                    Application application = new Application();
                    application.setUser(user);
                    application.setUserValueGetType(ValueType.BTC);
                    application.setUserValueGiveType(ValueType.RUB);
                    application.setUserValueGiveValue(totalAmount); // Сумма с комиссией
                    application.setUserValueGetValue(btcAmount);    // Запрашиваемое количество BTC
                    application.setCalculatedGetValue(btcAmount);   // Для отображения
                    application.setCalculatedGiveValue(totalAmount); // Для отображения
                    application.setTitle("Покупка BTC за RUB");
                    application.setStatus(ApplicationStatus.FREE);

                    temporaryApplications.put(user.getId(), application);

                    // Переходим к вводу кошелька
                    String message = "🔐 Теперь введите адрес Bitcoin-кошелька, на который поступит крипта:";
                    InlineKeyboardMarkup keyboard = createBackInlineKeyboard();
                    lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, keyboard));

                    user.setState(UserState.ENTERING_WALLET);
                    userService.update(user);

                } catch (NumberFormatException e) {
                    lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                            "❌ Пожалуйста, введите корректное число", createEnterAmountInlineKeyboard()));
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
                    "📭 Нет заявок в системе", createAdminMainMenuInlineKeyboard()));
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

        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message.toString(), createAdminApplicationsInlineKeyboard()));
    }


    private void showActiveApplications(Long chatId, User user, MyBot bot) {
        List<Application> activeApplications = applicationService.findActiveApplications();

        List<Application> sortedApplications = activeApplications.stream()
                .sorted(Comparator.comparing(Application::getIsVip).reversed()
                        .thenComparing(Application::getCreatedAt))
                .collect(Collectors.toList());

        if (sortedApplications.isEmpty()) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                    "📭 Нет активных заявок", createAdminMainMenuInlineKeyboard()));
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
                    "📭 Нет активных заявок", createAdminMainMenuInlineKeyboard()));
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
                    "❌ Ошибка при поиске заявки", createAdminMainMenuInlineKeyboard()));
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

    private void showUserDetails(Long chatId, User targetUser, MyBot bot) {
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
                targetUser.getId(),
                targetUser.getTelegramId(),
                targetUser.getFirstName(),
                targetUser.getLastName() != null ? targetUser.getLastName() : "",
                targetUser.getUsername() != null ? targetUser.getUsername() : "нет",
                targetUser.getTotalApplications(),
                targetUser.getCompletedBuyApplications() + targetUser.getCompletedSellApplications(),
                targetUser.getTotalBuyAmount(),
                targetUser.getTotalSellAmount(),
                targetUser.getBonusBalance(),
                targetUser.getReferralCount(),
                targetUser.getReferralEarnings()
        );

        InlineKeyboardMarkup inlineKeyboard = createBackToAdminKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);
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
                        "❌ Пожалуйста, используйте кнопки", createAdminMainMenuInlineKeyboard()));
        }
    }

    private void showAdminCommissionSettings(Long chatId, User user, MyBot bot) {
        String message = "💰 Управление комиссиями\n\n" +
                "Текущие настройки:\n" +
                "• 1000-1999 ₽: " + commissionConfig.getCommissionPercent(1000) + "%\n" +
                "• 2000-2999 ₽: " + commissionConfig.getCommissionPercent(2000) + "%\n" +
                "• 3000-4999 ₽: " + commissionConfig.getCommissionPercent(3000) + "%\n" +
                "• 5000-9999 ₽: " + commissionConfig.getCommissionPercent(5000) + "%\n\n" +
                "• 10000-14999 ₽: " + commissionConfig.getCommissionPercent(10000) + "%\n" +
                "• 15000-19999 ₽: " + commissionConfig.getCommissionPercent(15000) + "%\n" +
                "• 20000-24999 ₽: " + commissionConfig.getCommissionPercent(20000) + "%\n" +
                "• 25000-29999 ₽: " + commissionConfig.getCommissionPercent(25000) + "%\n" +
                "• 30000 ₽: " + commissionConfig.getCommissionPercent(30000) + "%\n" +
                "Для изменения введите:\n" +
                "• Для диапазона: 1000-1999 5\n" +
                "• Для минимальной суммы: 5000 2\n\n" +
                "Используйте '🔙 Назад' для возврата";

        InlineKeyboardMarkup inlineKeyboard = createBackToAdminKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);
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

        InlineKeyboardMarkup inlineKeyboard = createBackToAdminKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);
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

            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createAdminMainMenuInlineKeyboard()));

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

        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createAdminMainMenuInlineKeyboard()));
    }

    private void showAdminUsers(Long chatId, User user, MyBot bot) {
        String message = "👥 Раздел управления пользователями в разработке";
        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createAdminMainMenuInlineKeyboard()));
    }

    private void processAdminViewingAllApplications(Long chatId, User user, MyBot bot) {
        List<Application> activeApplications = applicationService.findActiveApplications();

        if (activeApplications.isEmpty()) {
            String message = "📭 Нет активных заявок.";
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createAdminMainMenuInlineKeyboard()));
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
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, "❌ Заявка не найдена", createAdminMainMenuInlineKeyboard()));
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
        showAdminApplicationManagementMenu(chatId, user, application, bot);
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
                        "❌ Пожалуйста, используйте кнопки", createOtherMenuInlineKeyboard()));
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

            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, calculation, createCalculatorMenuInlineKeyboard()));

        } catch (NumberFormatException e) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                    "❌ Пожалуйста, введите корректное число", createCalculatorMenuInlineKeyboard()));
        }
    }

    // Обновляем метод отмены через текстовую команду
    private void cancelUserApplication(Long chatId, User user, Long applicationId, MyBot bot) {
        Application application = applicationService.find(applicationId);

        if (application == null || !application.getUser().getId().equals(user.getId())) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                    "❌ Заявка не найдена или у вас нет прав для её отмены", createMainMenuInlineKeyboard(user)));
            return;
        }

        if (application.getStatus() != ApplicationStatus.FREE && application.getStatus() != ApplicationStatus.IN_WORK) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                    "❌ Невозможно отменить заявку с текущим статусом: " + application.getStatus().getDisplayName(),
                    createMainMenuInlineKeyboard(user)));
            return;
        }

        application.setStatus(ApplicationStatus.CANCELLED);

        // ВОЗВРАЩАЕМ БОНУСНЫЙ БАЛАНС ПРИ ОТМЕНЕ
        if (application.getUsedBonusBalance() > 0) {
            user.setBonusBalance(user.getBonusBalance() + application.getUsedBonusBalance());
            userService.update(user);
        }

        applicationService.update(application);

        // УДАЛЯЕМ сообщение с заявкой если оно есть
        if (application.getTelegramMessageId() != null) {
            bot.deleteMessage(chatId, application.getTelegramMessageId());
        }

        String message = "✅ Заявка #" + applicationId + " успешно отменена.";
        if (application.getUsedBonusBalance() > 0) {
            message += String.format("\n💸 Вам возвращен бонусный баланс: %.2f ₽", application.getUsedBonusBalance());
        }

        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createMainMenuInlineKeyboard(user)));
    }

    private void processCalculatorMenu(Long chatId, User user, String text, MyBot bot) {
        switch (text) {
            case "💰 Купить BTC":
                user.setState(UserState.CALCULATOR_BUY);
                userService.update(user);
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                        "💎 Введите сумму в рублях для расчета:", createCalculatorMenuInlineKeyboard()));
                break;
            case "💸 Продать BTC":
                user.setState(UserState.CALCULATOR_SELL);
                userService.update(user);
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                        "💎 Введите количество BTC для расчета:", createCalculatorMenuInlineKeyboard()));
                break;
            case "🔙 Назад":
                user.setState(UserState.OTHER_MENU);
                userService.update(user);
                showOtherMenu(chatId, user, bot);
                break;
            default:
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                        "❌ Пожалуйста, используйте кнопки", createCalculatorMenuInlineKeyboard()));
        }
    }

    private void showCalculatorMenu(Long chatId, User user, MyBot bot) {
        String message = "🧮 Калькулятор\n\nВыберите тип расчета:";

        InlineKeyboardMarkup inlineKeyboard = createCalculatorMenuInlineKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);
    }

    private InlineKeyboardMarkup createCalculatorMenuInlineKeyboard() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton buyButton = new InlineKeyboardButton();
        buyButton.setText("💰 Купить BTC");
        buyButton.setCallbackData("inline_calculator_buy");
        row1.add(buyButton);

        InlineKeyboardButton sellButton = new InlineKeyboardButton();
        sellButton.setText("💸 Продать BTC");
        sellButton.setCallbackData("inline_calculator_sell");
        row1.add(sellButton);

        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton backButton = new InlineKeyboardButton();
        backButton.setText("🔙 Назад");
        backButton.setCallbackData("inline_back");
        row2.add(backButton);

        List<InlineKeyboardButton> row3 = new ArrayList<>();
        InlineKeyboardButton mainMenuButton = new InlineKeyboardButton();
        mainMenuButton.setText("💎 Главное меню");
        mainMenuButton.setCallbackData("inline_main_menu");
        row3.add(mainMenuButton);

        rows.add(row1);
        rows.add(row2);
        rows.add(row3);

        markup.setKeyboard(rows);
        return markup;
    }

    private void showVipConfirmation(Long chatId, User user, Application application, MyBot bot) {
        String message = """
            💎 Хотите добавить 👑 VIP-приоритет за 300₽?
            
            👑 VIP-приоритет обеспечивает:
            • Первоочередную обработку
            • Ускоренное выполнение  
            • Приоритет в очереди
            • Личного оператора
            
            Выберите вариант:
            """;

        InlineKeyboardMarkup inlineKeyboard = createVipConfirmationInlineKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);
    }

    private void showCouponApplication(Long chatId, User user, Application application, MyBot bot) {
        String message = """
            🎫 Хотите применить купон для скидки?
            
            Если у вас есть купон, вы можете применить его сейчас.
            """;

        InlineKeyboardMarkup inlineKeyboard = createCouponApplicationInlineKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);
    }

    private InlineKeyboardMarkup createCouponApplicationInlineKeyboard() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton applyButton = new InlineKeyboardButton();
        applyButton.setText("Применить купон");
        applyButton.setCallbackData("inline_apply_coupon");
        row1.add(applyButton);

        InlineKeyboardButton skipButton = new InlineKeyboardButton();
        skipButton.setText("Пропустить");
        skipButton.setCallbackData("inline_skip_coupon");
        row1.add(skipButton);

        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton backButton = new InlineKeyboardButton();
        backButton.setText("🔙 Назад");
        backButton.setCallbackData("inline_back");
        row2.add(backButton);

        List<InlineKeyboardButton> row3 = new ArrayList<>();
        InlineKeyboardButton mainMenuButton = new InlineKeyboardButton();
        mainMenuButton.setText("💎 Главное меню");
        mainMenuButton.setCallbackData("inline_main_menu");
        row3.add(mainMenuButton);

        rows.add(row1);
        rows.add(row2);
        rows.add(row3);

        markup.setKeyboard(rows);
        return markup;
    }

    private InlineKeyboardMarkup createVipConfirmationInlineKeyboard() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton yesButton = new InlineKeyboardButton();
        yesButton.setText("👑 Да, добавить VIP");
        yesButton.setCallbackData("inline_vip_yes");
        row1.add(yesButton);

        InlineKeyboardButton noButton = new InlineKeyboardButton();
        noButton.setText("🔹 Нет, обычный приоритет");
        noButton.setCallbackData("inline_vip_no");
        row1.add(noButton);

        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton backButton = new InlineKeyboardButton();
        backButton.setText("🔙 Назад");
        backButton.setCallbackData("inline_back");
        row2.add(backButton);

        List<InlineKeyboardButton> row3 = new ArrayList<>();
        InlineKeyboardButton mainMenuButton = new InlineKeyboardButton();
        mainMenuButton.setText("💎 Главное меню");
        mainMenuButton.setCallbackData("inline_main_menu");
        row3.add(mainMenuButton);

        rows.add(row1);
        rows.add(row2);
        rows.add(row3);

        markup.setKeyboard(rows);
        return markup;
    }

    private void processReferralMenu(Long chatId, User user, String text, MyBot bot) {
        switch (text) {
            case "Создать реферальный код":
                user.setState(UserState.CREATING_REFERRAL_CODE);
                userService.update(user);
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                        "Введите описание для вашего реферального кода (например: 'Для друзей' или 'Специальное предложение'):",
                        createBackInlineKeyboard()));
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
                        "❌ Пожалуйста, используйте кнопки", createReferralMenuInlineKeyboard()));
        }
    }

    private void showReferralMenu(Long chatId, User user, MyBot bot) {
        user = userService.find(user.getId());
        ReferralStats stats = referralService.getReferralStats(user);

        String referralLink = referralService.generateReferralLink(user);

        String message = String.format("""
                🎁 Реферальная программа

                📝 Условия: 

                🔗 Ваша реферальная ссылка:
                📌 %s

                🤝 Ваш реферальный уровень: %.2f%%
                1️⃣ Текущий бонус к рефералам 1 уровня: %.2f%%
                2️⃣ Текущий бонус к рефералам 2 уровня: %.2f%%

                1️⃣ Количество рефералов 1 уровня: %d шт.
                2️⃣ Количество рефералов 2 уровня: %d шт.
                🏃‍➡️ Активных рефералов (всего): %d
                ⏳ Активных за последние 30 дн.: L1=%d, L2=%d

                📊 Статистика за всё время:
                💳 Сумма обменов: %.2f руб.
                ⚽️ Количество обменов: %d

                📊 Статистика за этот месяц:
                💳 Сумма обменов: %.2f руб.
                ⚽️ Количество обменов: %d

                💰 Всего заработано: %.2f
                💵 Ваш текущий баланс: %.2f RUB
                💸 Вывод реф дохода: от 300 ₽ в крипте и от 1500 ₽ через СБП
                """,
                referralLink,
                referralService.getLevel1Percent(),
                referralService.getLevel1Percent(),
                referralService.getLevel2Percent(),
                stats.getLevel1Count(),
                stats.getLevel2Count(),
                stats.getActiveReferrals(),
                stats.getActiveLast30DaysL1(),
                stats.getActiveLast30DaysL2(),
                stats.getTotalExchangeAmount(),
                stats.getTotalExchangeCount(),
                stats.getMonthlyExchangeAmount(),
                stats.getMonthlyExchangeCount(),
                stats.getTotalEarned(),
                user.getReferralBalance()
        );

        InlineKeyboardMarkup inlineKeyboard = createReferralMenuInlineKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);
    }


    private void processCreatingReferralCode(Long chatId, User user, String text, MyBot bot) {
        if (text.equals("🔙 Назад") || text.equals("🔙 Главное меню")) {
            user.setState(UserState.REFERRAL_MENU);
            userService.update(user);
            showReferralMenu(chatId, user, bot);
            return;
        }

        try {
            // Проверяем, есть ли у пользователя уже активные реферальные коды
            List<ReferralCode> existingCodes = referralService.getUserReferralCodes(user.getId());
            boolean hasActiveCode = existingCodes.stream().anyMatch(code -> code.getIsActive());

            if (hasActiveCode) {
                String message = "❌ У вас уже есть активный реферальный код.\n\n" +
                        "Вы можете создать только один реферальный код.";
                InlineKeyboardMarkup inlineKeyboard = createBackAndMainMenuKeyboard();
                int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
                lastMessageId.put(chatId, messageId);
                return;
            }

            // Создаем реферальный код
            ReferralCode referralCode = referralService.createReferralCode(user);

            String message = String.format("""
                        ✅ Реферальный код создан!
                        
                        🔸 Ваш код: %s
                        📝 Описание: %s
                        
                        Теперь вы можете делиться этим кодом с друзьями. 
                        За каждую успешную заявку реферала вы будете получать %.2f%% от суммы заявки.
                        """,
                    referralCode.getCode(),
                    text, // используем введенный текст как описание
                    referralCode.getRewardPercent());

            InlineKeyboardMarkup inlineKeyboard = createBackAndMainMenuKeyboard();
            int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
            lastMessageId.put(chatId, messageId);

            user.setState(UserState.REFERRAL_MENU);
            userService.update(user);

        } catch (Exception e) {
            String errorMessage = "❌ Ошибка при создании реферального кода: " + e.getMessage();
            InlineKeyboardMarkup inlineKeyboard = createBackAndMainMenuKeyboard();
            int messageId = bot.sendMessageWithInlineKeyboard(chatId, errorMessage, inlineKeyboard);
            lastMessageId.put(chatId, messageId);
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

            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createMainMenuInlineKeyboard(user)));

            user.setState(UserState.MAIN_MENU);
            userService.update(user);
        } else {
            String message = "❌ Неверный реферальный код или он уже был использован.\n\n" +
                    "Пожалуйста, проверьте код и попробуйте еще раз.";
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createBackInlineKeyboard()));
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
        operatorButton.setText("📞 Написать оператору @SUP_CN");
        operatorButton.setUrl("https://t.me/SUP_CN");
        row2.add(operatorButton);

        rows.add(row1);
        rows.add(row2);

        markup.setKeyboard(rows);
        return markup;
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

    private InlineKeyboardMarkup createBuyMenuInlineKeyboard() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton rubButton = new InlineKeyboardButton();
        rubButton.setText("💎 Ввести сумму в RUB");
        rubButton.setCallbackData("inline_buy_rub");
        row1.add(rubButton);

        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton btcButton = new InlineKeyboardButton();
        btcButton.setText("₿ Ввести количество в BTC");
        btcButton.setCallbackData("inline_buy_btc");
        row2.add(btcButton);

        List<InlineKeyboardButton> row3 = new ArrayList<>();
        InlineKeyboardButton backButton = new InlineKeyboardButton();
        backButton.setText("🔙 Назад");
        backButton.setCallbackData("inline_back");
        row3.add(backButton);

        List<InlineKeyboardButton> row4 = new ArrayList<>();
        InlineKeyboardButton mainMenuButton = new InlineKeyboardButton();
        mainMenuButton.setText("💎 Главное меню");
        mainMenuButton.setCallbackData("inline_main_menu");
        row4.add(mainMenuButton);

        rows.add(row1);
        rows.add(row2);
        rows.add(row3);
        rows.add(row4);

        markup.setKeyboard(rows);
        return markup;
    }

    private InlineKeyboardMarkup createSellMenuInlineKeyboard() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton sellButton = new InlineKeyboardButton();
        sellButton.setText("💎 Ввести количество BTC");
        sellButton.setCallbackData("inline_sell_amount");
        row1.add(sellButton);

        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton backButton = new InlineKeyboardButton();
        backButton.setText("🔙 Назад");
        backButton.setCallbackData("inline_back");
        row2.add(backButton);

        List<InlineKeyboardButton> row3 = new ArrayList<>();
        InlineKeyboardButton mainMenuButton = new InlineKeyboardButton();
        mainMenuButton.setText("💎 Главное меню");
        mainMenuButton.setCallbackData("inline_main_menu");
        row3.add(mainMenuButton);

        rows.add(row1);
        rows.add(row2);
        rows.add(row3);

        markup.setKeyboard(rows);
        return markup;
    }

    private InlineKeyboardMarkup createEnterAmountInlineKeyboard() {
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

    private InlineKeyboardMarkup createAdminMainMenuInlineKeyboard() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // Первый ряд - основные кнопки
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton allAppsButton = new InlineKeyboardButton();
        allAppsButton.setText("📋 Все заявки");
        allAppsButton.setCallbackData("inline_admin_all");
        row1.add(allAppsButton);

        InlineKeyboardButton activeAppsButton = new InlineKeyboardButton();
        activeAppsButton.setText("📊 Активные заявки");
        activeAppsButton.setCallbackData("inline_admin_active");
        row1.add(activeAppsButton);

        // Второй ряд
        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton myAppsButton = new InlineKeyboardButton(); // НОВАЯ КНОПКА
        myAppsButton.setText("👨‍💼 Мои заявки");
        myAppsButton.setCallbackData("inline_admin_my_applications");
        row2.add(myAppsButton);

        InlineKeyboardButton nextButton = new InlineKeyboardButton();
        nextButton.setText("⏭️ Следующая заявка");
        nextButton.setCallbackData("inline_admin_next");
        row2.add(nextButton);

        // Третий ряд
        List<InlineKeyboardButton> row3 = new ArrayList<>();
        InlineKeyboardButton searchButton = new InlineKeyboardButton();
        searchButton.setText("👥 Поиск пользователя");
        searchButton.setCallbackData("inline_admin_search");
        row3.add(searchButton);

        InlineKeyboardButton couponButton = new InlineKeyboardButton();
        couponButton.setText("🎫 Купоны");
        couponButton.setCallbackData("inline_admin_coupons");
        row3.add(couponButton);

        // Четвертый ряд
        List<InlineKeyboardButton> row4 = new ArrayList<>();
        InlineKeyboardButton commissionButton = new InlineKeyboardButton();
        commissionButton.setText("💰 Комиссии");
        commissionButton.setCallbackData("inline_admin_commission");
        row4.add(commissionButton);

        InlineKeyboardButton timeFilterButton = new InlineKeyboardButton();
        timeFilterButton.setText("📅 Фильтр по времени");
        timeFilterButton.setCallbackData("inline_admin_time");
        row4.add(timeFilterButton);

        // Пятый ряд
        List<InlineKeyboardButton> row5 = new ArrayList<>();
        InlineKeyboardButton bonusButton = new InlineKeyboardButton();
        bonusButton.setText("💳 Бонусные балансы");
        bonusButton.setCallbackData("inline_admin_bonus_manage");
        row5.add(bonusButton);

        // Шестой ряд - навигация
        List<InlineKeyboardButton> row6 = new ArrayList<>();
        InlineKeyboardButton mainMenuButton = new InlineKeyboardButton();
        mainMenuButton.setText("💎 Главное меню");
        mainMenuButton.setCallbackData("inline_main_menu");
        row6.add(mainMenuButton);

        rows.add(row1);
        rows.add(row2);
        rows.add(row3);
        rows.add(row4);
        rows.add(row5);
        rows.add(row6);

        markup.setKeyboard(rows);
        return markup;
    }

    private void showEnterAmountMenu(Long chatId, String currency, MyBot bot) {
        String message = String.format("💎 Введите сумму в %s:", currency);
        InlineKeyboardMarkup inlineKeyboard = createEnterAmountInlineKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);
    }

    // Методы для обработки административных фильтров по времени
    private void processAdminTimeFilter(Long chatId, User user, MyBot bot) {
        String message = "📅 Фильтр заявок по времени:\n\nВыберите период для просмотра заявок:";
        InlineKeyboardMarkup inlineKeyboard = createTimeFilterInlineKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);
    }

    private InlineKeyboardMarkup createTimeFilterInlineKeyboard() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton todayButton = new InlineKeyboardButton();
        todayButton.setText("📅 Сегодня");
        todayButton.setCallbackData("inline_admin_today");
        row1.add(todayButton);

        InlineKeyboardButton weekButton = new InlineKeyboardButton();
        weekButton.setText("📅 За неделю");
        weekButton.setCallbackData("inline_admin_week");
        row1.add(weekButton);

        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton monthButton = new InlineKeyboardButton();
        monthButton.setText("📅 За месяц");
        monthButton.setCallbackData("inline_admin_month");
        row2.add(monthButton);

        InlineKeyboardButton allTimeButton = new InlineKeyboardButton();
        allTimeButton.setText("📅 Все время");
        allTimeButton.setCallbackData("inline_admin_all_time");
        row2.add(allTimeButton);

        List<InlineKeyboardButton> row3 = new ArrayList<>();
        InlineKeyboardButton backButton = new InlineKeyboardButton();
        backButton.setText("🔙 Назад");
        backButton.setCallbackData("inline_admin_back");
        row3.add(backButton);

        rows.add(row1);
        rows.add(row2);
        rows.add(row3);

        markup.setKeyboard(rows);
        return markup;
    }

    private void showApplicationsByPeriod(Long chatId, User user, String period, MyBot bot) {
        List<Application> applications = applicationService.findApplicationsByPeriod(period);

        if (applications.isEmpty()) {
            String message = "📭 Нет заявок за выбранный период.";
            InlineKeyboardMarkup inlineKeyboard = createTimeFilterInlineKeyboard();
            int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
            lastMessageId.put(chatId, messageId);
            return;
        }

        StringBuilder message = new StringBuilder("📋 Заявки за выбранный период:\n\n");
        for (int i = 0; i < Math.min(applications.size(), 10); i++) {
            Application app = applications.get(i);
            message.append(String.format("""
                            🆔 #%d | %s
                            👤 %s (@%s)
                            💰 %.2f ₽ | %s
                            📊 %s
                            🕒 %s
                            --------------------
                            """,
                    app.getId(),
                    app.getTitle(),
                    app.getUser().getFirstName(),
                    app.getUser().getUsername() != null ? app.getUser().getUsername() : "нет_username",
                    app.getCalculatedGiveValue(),
                    app.getIsVip() ? "👑 VIP" : "🔹 Обычная",
                    app.getStatus().getDisplayName(),
                    app.getCreatedAt().format(DateTimeFormatter.ofPattern("dd.MM.yy HH:mm"))
            ));
        }

        if (applications.size() > 10) {
            message.append("\n⚠️ Показано 10 из " + applications.size() + " заявок");
        }

        InlineKeyboardMarkup inlineKeyboard = createTimeFilterInlineKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message.toString(), inlineKeyboard);
        lastMessageId.put(chatId, messageId);
    }
    private InlineKeyboardMarkup createBackInlineKeyboard() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton backButton = new InlineKeyboardButton();
        backButton.setText("🔙 Назад");
        backButton.setCallbackData("inline_back");
        row1.add(backButton);

        rows.add(row1);

        markup.setKeyboard(rows);
        return markup;
    }


    private InlineKeyboardMarkup createReferralMenuInlineKeyboard() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton conditionsButton = new InlineKeyboardButton();
        conditionsButton.setText("📋 Условия программы");
        conditionsButton.setCallbackData("inline_referral_conditions");
        row1.add(conditionsButton);

        // УБРАНА КНОПКА ВЫВОДА СРЕДСТВ

        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton backButton = new InlineKeyboardButton();
        backButton.setText("🔙 Назад");
        backButton.setCallbackData("inline_back");
        row2.add(backButton);

        rows.add(row1);
        rows.add(row2);

        markup.setKeyboard(rows);
        return markup;
    }

    private void showAdminApplicationManagementMenu(Long chatId, User admin, Application application, MyBot bot) {
        String userInfo = String.format("@%s (ID: %d, TG: %d)",
                application.getUser().getUsername() != null ? application.getUser().getUsername() : "нет_username",
                application.getUser().getId(),
                application.getUser().getTelegramId());

        String message = String.format("""
                    🎯 Управление заявкой #%d
                    
                    👤 Пользователь: %s %s
                    %s
                    💰 Тип операции: %s
                    📊 Текущий статус: %s
                    
                    💸 Отдает: %.2f %s
                    💰 Получает: %.8f %s
                    
                    %s
                    🔐 Кошелек: %s
                    🎫 Купон: %s
                    🎁 Бонусы: %.2f ₽
                    
                    📅 Создана: %s
                    🕰️ Истекает: %s
                    
                    Выберите действие:
                    """,
                application.getId(),
                application.getUser().getFirstName(),
                application.getUser().getLastName() != null ? application.getUser().getLastName() : "",
                userInfo,
                application.getTitle(),
                application.getStatus().getDisplayName(),
                application.getCalculatedGiveValue(),
                application.getUserValueGiveType().getDisplayName(),
                application.getCalculatedGetValue(),
                application.getUserValueGetType().getDisplayName(),
                application.getIsVip() ? "👑 VIP-приоритет" : "🔹 Обычный приоритет",
                application.getWalletAddress(),
                application.getAppliedCoupon() != null ? application.getAppliedCoupon().getCode() : "нет",
                application.getUsedBonusBalance(),
                application.getCreatedAt().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")),
                application.getFormattedExpiresAt()
        );

        InlineKeyboardMarkup keyboard = createAdminApplicationManagementKeyboard(application.getId());
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, keyboard);
        lastMessageId.put(chatId, messageId);
    }

    private InlineKeyboardMarkup createAdminApplicationManagementKeyboard(Long applicationId) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // Первый ряд - основные статусы
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton inWorkButton = new InlineKeyboardButton();
        inWorkButton.setText("🟡 В работу");
        inWorkButton.setCallbackData("inline_admin_app_inwork_" + applicationId);
        row1.add(inWorkButton);

        InlineKeyboardButton paidButton = new InlineKeyboardButton();
        paidButton.setText("🔵 Оплачен");
        paidButton.setCallbackData("inline_admin_app_paid_" + applicationId);
        row1.add(paidButton);

        // Второй ряд
        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton completedButton = new InlineKeyboardButton();
        completedButton.setText("✅ Выполнено");
        completedButton.setCallbackData("inline_admin_app_completed_" + applicationId);
        row2.add(completedButton);

        InlineKeyboardButton cancelButton = new InlineKeyboardButton();
        cancelButton.setText("🔴 Отменить");
        cancelButton.setCallbackData("inline_admin_app_cancel_" + applicationId);
        row2.add(cancelButton);

        // Третий ряд
        List<InlineKeyboardButton> row3 = new ArrayList<>();
        InlineKeyboardButton freeButton = new InlineKeyboardButton();
        freeButton.setText("🟢 Свободна");
        freeButton.setCallbackData("inline_admin_app_free_" + applicationId);
        row3.add(freeButton);

        // Четвертый ряд - дополнительная информация
        List<InlineKeyboardButton> row4 = new ArrayList<>();
        InlineKeyboardButton userInfoButton = new InlineKeyboardButton();
        userInfoButton.setText("👤 Инфо о пользователе");
        userInfoButton.setCallbackData("inline_admin_app_userinfo_" + applicationId);
        row4.add(userInfoButton);

        InlineKeyboardButton contactButton = new InlineKeyboardButton();
        contactButton.setText("📞 Связаться");
        contactButton.setUrl("https://t.me/" + (applicationService.find(applicationId).getUser().getUsername() != null ? applicationService.find(applicationId).getUser().getUsername() : "cosanostra_support"));
        row4.add(contactButton);

        // Пятый ряд - навигация
        List<InlineKeyboardButton> row5 = new ArrayList<>();
        InlineKeyboardButton myAppsButton = new InlineKeyboardButton();
        myAppsButton.setText("👨‍💼 Мои заявки");
        myAppsButton.setCallbackData("inline_admin_my_applications");
        row5.add(myAppsButton);

        InlineKeyboardButton allAppsButton = new InlineKeyboardButton();
        allAppsButton.setText("📋 Все заявки");
        allAppsButton.setCallbackData("inline_admin_all");
        row5.add(allAppsButton);

        // Шестой ряд
        List<InlineKeyboardButton> row6 = new ArrayList<>();
        InlineKeyboardButton backButton = new InlineKeyboardButton();
        backButton.setText("🔙 Назад");
        backButton.setCallbackData("inline_admin_back");
        row6.add(backButton);

        List<InlineKeyboardButton> row7 = new ArrayList<>();
        InlineKeyboardButton mainMenuButton = new InlineKeyboardButton();
        mainMenuButton.setText("💎 Главное меню");
        mainMenuButton.setCallbackData("inline_main_menu");
        row7.add(mainMenuButton);

        rows.add(row1);
        rows.add(row2);
        rows.add(row3);
        rows.add(row4);
        rows.add(row5);
        rows.add(row6);
        rows.add(row7);

        markup.setKeyboard(rows);
        return markup;
    }

    private InlineKeyboardMarkup createAdminApplicationActionsInlineKeyboard(Long applicationId) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // Первый ряд - основные действия
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton inWorkButton = new InlineKeyboardButton();
        inWorkButton.setText("🟡 В работу");
        inWorkButton.setCallbackData("inline_admin_app_inwork_" + applicationId);
        row1.add(inWorkButton);

        InlineKeyboardButton paidButton = new InlineKeyboardButton(); // ДОБАВЛЕНО
        paidButton.setText("🔵 Оплачен");
        paidButton.setCallbackData("inline_admin_app_paid_" + applicationId);
        row1.add(paidButton);

        // Второй ряд
        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton completedButton = new InlineKeyboardButton();
        completedButton.setText("✅ Выполнено");
        completedButton.setCallbackData("inline_admin_app_completed_" + applicationId);
        row2.add(completedButton);

        InlineKeyboardButton cancelButton = new InlineKeyboardButton();
        cancelButton.setText("🔴 Отменить");
        cancelButton.setCallbackData("inline_admin_app_cancel_" + applicationId);
        row2.add(cancelButton);

        // Третий ряд
        List<InlineKeyboardButton> row3 = new ArrayList<>();
        InlineKeyboardButton freeButton = new InlineKeyboardButton();
        freeButton.setText("🟢 Свободна");
        freeButton.setCallbackData("inline_admin_app_free_" + applicationId);
        row3.add(freeButton);

        // Четвертый ряд - навигация
        List<InlineKeyboardButton> row4 = new ArrayList<>();
        InlineKeyboardButton allAppsButton = new InlineKeyboardButton();
        allAppsButton.setText("📋 Все заявки");
        allAppsButton.setCallbackData("inline_admin_all");
        row4.add(allAppsButton);

        InlineKeyboardButton backButton = new InlineKeyboardButton();
        backButton.setText("🔙 Назад");
        backButton.setCallbackData("inline_admin_back");
        row4.add(backButton);

        // Пятый ряд - главное меню
        List<InlineKeyboardButton> row5 = new ArrayList<>();
        InlineKeyboardButton mainMenuButton = new InlineKeyboardButton();
        mainMenuButton.setText("💎 Главное меню");
        mainMenuButton.setCallbackData("inline_main_menu");
        row5.add(mainMenuButton);

        rows.add(row1);
        rows.add(row2);
        rows.add(row3);
        rows.add(row4);
        rows.add(row5);

        markup.setKeyboard(rows);
        return markup;
    }

    private InlineKeyboardMarkup createAdminApplicationsInlineKeyboard() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // Первый ряд
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton allAppsButton = new InlineKeyboardButton();
        allAppsButton.setText("📋 Все заявки");
        allAppsButton.setCallbackData("inline_admin_all");
        row1.add(allAppsButton);

        InlineKeyboardButton activeAppsButton = new InlineKeyboardButton();
        activeAppsButton.setText("📊 Активные");
        activeAppsButton.setCallbackData("inline_admin_active");
        row1.add(activeAppsButton);

        // Второй ряд - ДОБАВЛЕНА КНОПКА "ВЗЯТЬ"
        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton takeButton = new InlineKeyboardButton();
        takeButton.setText("🎯 Взять заявку");
        takeButton.setCallbackData("inline_admin_take");
        row2.add(takeButton);

        InlineKeyboardButton myAppsButton = new InlineKeyboardButton();
        myAppsButton.setText("👨‍💼 Мои заявки");
        myAppsButton.setCallbackData("inline_admin_my_applications");
        row2.add(myAppsButton);

        // Третий ряд
        List<InlineKeyboardButton> row3 = new ArrayList<>();
        InlineKeyboardButton searchButton = new InlineKeyboardButton();
        searchButton.setText("👥 Поиск");
        searchButton.setCallbackData("inline_admin_search");
        row3.add(searchButton);

        InlineKeyboardButton couponButton = new InlineKeyboardButton();
        couponButton.setText("🎫 Купоны");
        couponButton.setCallbackData("inline_admin_coupon");
        row3.add(couponButton);

        // Четвертый ряд
        List<InlineKeyboardButton> row4 = new ArrayList<>();
        InlineKeyboardButton commissionButton = new InlineKeyboardButton();
        commissionButton.setText("💰 Комиссии");
        commissionButton.setCallbackData("inline_admin_commission");
        row4.add(commissionButton);

        InlineKeyboardButton timeFilterButton = new InlineKeyboardButton();
        timeFilterButton.setText("📅 Фильтр по времени");
        timeFilterButton.setCallbackData("inline_admin_time");
        row4.add(timeFilterButton);

        // Пятый ряд
        List<InlineKeyboardButton> row5 = new ArrayList<>();
        InlineKeyboardButton bonusButton = new InlineKeyboardButton();
        bonusButton.setText("💳 Бонусные балансы");
        bonusButton.setCallbackData("inline_admin_bonus_manage");
        row5.add(bonusButton);

        // Шестой ряд - навигация
        List<InlineKeyboardButton> row6 = new ArrayList<>();
        InlineKeyboardButton backButton = new InlineKeyboardButton();
        backButton.setText("🔙 Назад");
        backButton.setCallbackData("inline_admin_back");
        row6.add(backButton);

        List<InlineKeyboardButton> row7 = new ArrayList<>();
        InlineKeyboardButton mainMenuButton = new InlineKeyboardButton();
        mainMenuButton.setText("💎 Главное меню");
        mainMenuButton.setCallbackData("inline_main_menu");
        row7.add(mainMenuButton);

        rows.add(row1);
        rows.add(row2);
        rows.add(row3);
        rows.add(row4);
        rows.add(row5);
        rows.add(row6);
        rows.add(row7);

        markup.setKeyboard(rows);
        return markup;
    }

// Добавляем обработку кнопки "Взять"
    private void processAdminBonusBalanceManagement(Long chatId, User user, String text, MyBot bot) {
        if (text.equals("🔙 Назад")) {
            user.setState(UserState.ADMIN_MAIN_MENU);
            userService.update(user);
            showAdminMainMenu(chatId, bot);
            return;
        }

        // Обработка ввода username для поиска пользователя
        processAdminUserSearchForBonus(chatId, user, text, bot);
    }

    private void processAdminUserSearchForBonus(Long chatId, User admin, String searchQuery, MyBot bot) {
        User foundUser = null;

        // Пробуем найти по username
        if (!searchQuery.startsWith("@")) {
            foundUser = userService.findByUsername(searchQuery);
        } else {
            foundUser = userService.findByUsername(searchQuery.substring(1));
        }

        // Пробуем найти по ID
        if (foundUser == null) {
            try {
                Long userId = Long.parseLong(searchQuery);
                foundUser = userService.find(userId);
            } catch (NumberFormatException e) {
                // Не число
            }
        }

        // Пробуем найти по Telegram ID
        if (foundUser == null) {
            try {
                Long telegramId = Long.parseLong(searchQuery);
                foundUser = userService.findByTelegramId(telegramId);
            } catch (NumberFormatException e) {
                // Не число
            }
        }

        if (foundUser == null) {
            String message = "❌ Пользователь не найден.\n\n" +
                    "Введите username (без @) или ID пользователя:";
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createBackToAdminKeyboard()));
            return;
        }

        showUserBonusManagement(chatId, foundUser, bot);
    }

    private void showUserBonusManagement(Long chatId, User targetUser, MyBot bot) {
        String message = String.format("""
            💰 Управление бонусным балансом
                        
            👤 Пользователь: %s %s
            📱 Username: @%s
            🆔 ID: %d
            💳 Текущий бонусный баланс: %.2f ₽
                        
            Выберите действие:
            """,
                targetUser.getFirstName(),
                targetUser.getLastName() != null ? targetUser.getLastName() : "",
                targetUser.getUsername() != null ? targetUser.getUsername() : "нет",
                targetUser.getId(),
                targetUser.getBonusBalance()
        );

        InlineKeyboardMarkup inlineKeyboard = createUserBonusManagementKeyboard(targetUser.getId());
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);
    }

    private InlineKeyboardMarkup createUserBonusManagementKeyboard(Long userId) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // Кнопки для пополнения баланса
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        row1.add(createInlineButton("➕ 100 ₽", "inline_bonus_add_100_" + userId));
        row1.add(createInlineButton("➕ 500 ₽", "inline_bonus_add_500_" + userId));

        List<InlineKeyboardButton> row2 = new ArrayList<>();
        row2.add(createInlineButton("➕ 1000 ₽", "inline_bonus_add_1000_" + userId));
        row2.add(createInlineButton("➖ 100 ₽", "inline_bonus_remove_100_" + userId));

        List<InlineKeyboardButton> row3 = new ArrayList<>();
        row3.add(createInlineButton("➖ 500 ₽", "inline_bonus_remove_500_" + userId));
        row3.add(createInlineButton("🔄 Обнулить", "inline_bonus_reset_" + userId));

        List<InlineKeyboardButton> row4 = new ArrayList<>();
        row4.add(createInlineButton("🔙 Назад", "inline_admin_back"));

        rows.add(row1);
        rows.add(row2);
        rows.add(row3);
        rows.add(row4);

        markup.setKeyboard(rows);
        return markup;
    }

    private void processAdminViewCoupons(Long chatId, User user, String text, MyBot bot) {
        if (text.equals("🔙 Назад")) {
            user.setState(UserState.ADMIN_MAIN_MENU);
            userService.update(user);
            showAdminMainMenu(chatId, bot);
            return;
        }
        showAdminCouponsMenu(chatId, bot);
    }

    private boolean validateAmount(double amount, String currency, Long chatId, MyBot bot) {
        if (amount <= 0) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                    "❌ Сумма должна быть больше 0", createEnterAmountInlineKeyboard()));
            return false;
        }

        if (currency.equals("RUB") && amount < 1000) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                    "❌ Минимальная сумма заявки 1000 рублей", createEnterAmountInlineKeyboard()));
            return false;
        }

        if (currency.equals("BTC") && amount < 0.00001) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                    "❌ Минимальное количество BTC: 0.00001", createEnterAmountInlineKeyboard()));
            return false;
        }

        return true;
    }
}

