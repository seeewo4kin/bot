package com.seeewo4kin.bot.Bot;

import com.seeewo4kin.bot.Config.AdminConfig;
import com.seeewo4kin.bot.Config.CommissionConfig;
import com.seeewo4kin.bot.Entity.*;
import com.seeewo4kin.bot.Entity.ReferralStatsEmbedded;
import com.seeewo4kin.bot.Enums.ApplicationStatus;
import com.seeewo4kin.bot.service.ReferralService;
import com.seeewo4kin.bot.Enums.CryptoCurrency;
import com.seeewo4kin.bot.Enums.UserState;
import com.seeewo4kin.bot.Enums.ValueType;
import com.seeewo4kin.bot.ValueGettr.CryptoPriceService;
import com.seeewo4kin.bot.service.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private final Map<Long, Integer> adminAllApplicationsPage = new ConcurrentHashMap<>();
    private final Map<Long, Integer> adminActiveApplicationsPage = new ConcurrentHashMap<>();
    private final Map<Long, Integer> adminAllUsersPage = new ConcurrentHashMap<>();
    private final Map<Long, String> adminCurrentFilter = new ConcurrentHashMap<>();

    private static final BigDecimal VIP_COST = new BigDecimal("300");
    private final Map<Long, Application> temporaryApplications = new ConcurrentHashMap<>();
    private final Map<Long, String> currentOperation = new ConcurrentHashMap<>();
    private final Map<Long, Integer> lastMessageId = new ConcurrentHashMap<>();
    private final Map<Long, Integer> welcomePhotoId = new ConcurrentHashMap<>();
    private final Map<Long, List<Integer>> chatMessageHistory = new ConcurrentHashMap<>();
    private final Map<Long, Integer> firstWelcomeMessageId = new ConcurrentHashMap<>();
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

    private String formatRubAmount(BigDecimal amount) {
        if (amount == null) return "0.00 ₽";
        return String.format("%.2f ₽", amount).replace(",", ".");
    }

    private String formatBtcAmount(BigDecimal amount) {
        if (amount == null) return "0.00000000 BTC";
        return String.format("%.8f BTC", amount).replace(",", ".");
    }

    private String formatCryptoAmount(BigDecimal amount, CryptoCurrency crypto) {
        switch (crypto) {
            case BTC:
                return formatBtcAmount(amount);
            case LTC:
                return String.format("%.8f Ł", amount).replace(",", ".");
            case XMR:
                return String.format("%.12f ɱ", amount).replace(",", ".");
            default:
                return formatBtcAmount(amount);
        }
    }

    private String formatDouble(BigDecimal value) {
        if (value == null) return "0.00";
        return String.format("%.2f", value).replace(",", ".");
    }

    private String formatPercent(BigDecimal value) {
        if (value == null) return "0.0%";
        return String.format("%.1f%%", value).replace(",", ".");
    }

    // Вспомогательные методы для преобразования
    private BigDecimal toBigDecimal(Double value) {
        return value != null ? BigDecimal.valueOf(value) : BigDecimal.ZERO;
    }

    private BigDecimal toBigDecimal(String value) {
        try {
            return new BigDecimal(value);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private Double toDouble(BigDecimal value) {
        return value != null ? value.doubleValue() : 0.0;
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

    /**
     * Очищает весь чат кроме заявок и первого приветственного сообщения
     */
    private void clearChatExceptApplications(Long chatId, MyBot bot) {
        List<Integer> messageHistory = chatMessageHistory.get(chatId);
        Integer firstMessageId = firstWelcomeMessageId.get(chatId);

        if (messageHistory != null) {
            // Удаляем все сообщения из истории, кроме первого приветственного
            for (Integer messageId : messageHistory) {
                if (firstMessageId == null || !messageId.equals(firstMessageId)) {
                    try {
                        bot.deleteMessage(chatId, messageId);
                    } catch (Exception e) {
                        // Игнорируем ошибки удаления
                    }
                }
            }
            messageHistory.clear();
            // Восстанавливаем ID первого сообщения в истории
            if (firstMessageId != null) {
                messageHistory.add(firstMessageId);
            }
        }

        // Очищаем остальные ID, но сохраняем первое приветственное сообщение
        lastMessageId.remove(chatId);
        welcomePhotoId.remove(chatId);
        // НЕ удаляем firstWelcomeMessageId, чтобы первое сообщение оставалось
    }

    /**
     * Добавляет сообщение в историю чата для последующего удаления
     */
    private void addMessageToHistory(Long chatId, Integer messageId) {
        if (messageId != null && messageId > 0) {
            chatMessageHistory.computeIfAbsent(chatId, k -> new ArrayList<>()).add(messageId);
        }
    }

    private void processTextMessage(Update update, MyBot bot) {
        String text = update.getMessage().getText();
        Long chatId = update.getMessage().getChatId();
        Long telegramId = update.getMessage().getFrom().getId();

        bot.deleteMessage(chatId, update.getMessage().getMessageId());

        User user = userService.findByTelegramId(telegramId);

        // Обработка команды /start в любом состоянии
        if ("/start".equals(text)) {
            processStartCommand(update, bot);
            return;
        }

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

        if (text.startsWith("/admin")) {
            if (adminConfig.isAdmin(user.getId())) {
                user.setState(UserState.ADMIN_MAIN_MENU);
                userService.update(user);
                showAdminMainMenu(chatId, bot);
                return;
            }
        }

        if (user == null || user.getState() == UserState.START) {
            processCommand(update, bot);
        } else {
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
            case OTHER_MENU:
            case REFERRAL_MENU:
            case ADMIN_MAIN_MENU:
                processMainMenu(chatId, user, bot);
                break;

            // Ввод суммы возвращает в соответствующее меню
            case ENTERING_BUY_AMOUNT_RUB_BTC:
            case ENTERING_BUY_AMOUNT_RUB_LTC:
            case ENTERING_BUY_AMOUNT_RUB_XMR:
                System.out.println("DEBUG: Back from crypto RUB amount input to input method");
                user.setState(UserState.CHOOSING_INPUT_METHOD);
                userService.update(user);

                // Получаем криптовалюту из currentOperation
                String currentOp = currentOperation.get(user.getId());
                CryptoCurrency crypto = getCryptoFromOperation(currentOp);
                showInputMethodMenu(chatId, user, crypto, bot);
                break;

            case ENTERING_BUY_AMOUNT_BTC:
            case ENTERING_BUY_AMOUNT_LTC:
            case ENTERING_BUY_AMOUNT_XMR:
                System.out.println("DEBUG: Back from crypto amount input to input method");
                user.setState(UserState.CHOOSING_INPUT_METHOD);
                userService.update(user);

                // Получаем криптовалюту из currentOperation
                String currentOpCrypto = currentOperation.get(user.getId());
                CryptoCurrency cryptoCrypto = getCryptoFromOperation(currentOpCrypto);
                showInputMethodMenu(chatId, user, cryptoCrypto, bot);
                break;




            case VIEWING_REFERRAL_TERMS:
                user.setState(UserState.REFERRAL_MENU);
                userService.update(user);
                showReferralMenu(chatId, user, bot);
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
                String currentOpp = currentOperation.get(user.getId());
                if (currentOpp != null && (currentOpp.contains("BUY"))) {
                    user.setState(UserState.BUY_MENU);
                    showBuyMenu(chatId, bot);
                } else {
                    processMainMenu(chatId, user, bot);
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
                showWalletInput(chatId, bot, user);
                break;

            case ENTERING_WALLET:
                System.out.println("DEBUG: Back from wallet input to amount input");

                Application application1 = temporaryApplications.get(user.getId());
                if (application1 != null) {
                    // Определяем, откуда пришли - из покупки или продажи по currentOperation
                    String currentOpWallet = currentOperation.get(user.getId());
                    boolean isBuy = currentOpWallet != null && currentOpWallet.contains("BUY");

                    CryptoCurrency crypto1 = application1.getCryptoCurrency();

                    if (isBuy) {
                        // Для покупки возвращаем к вводу суммы в зависимости от выбранного способа
                        if (currentOpWallet.contains("_RUB")) {
                            // Ввод в RUB
                            if (crypto1 == CryptoCurrency.BTC) {
                                user.setState(UserState.ENTERING_BUY_AMOUNT_RUB_BTC);
                            } else if (crypto1 == CryptoCurrency.LTC) {
                                user.setState(UserState.ENTERING_BUY_AMOUNT_RUB_LTC);
                            } else if (crypto1 == CryptoCurrency.XMR) {
                                user.setState(UserState.ENTERING_BUY_AMOUNT_RUB_XMR);
                            }
                            userService.update(user);
                            // Показываем меню ввода суммы в рублях
                            showEnterAmountRubMenu(chatId, user, crypto1, bot);
                        } else {
                            // Ввод в крипте
                            if (crypto1 == CryptoCurrency.BTC) {
                                user.setState(UserState.ENTERING_BUY_AMOUNT_BTC);
                            } else if (crypto1 == CryptoCurrency.LTC) {
                                user.setState(UserState.ENTERING_BUY_AMOUNT_LTC);
                            } else if (crypto1 == CryptoCurrency.XMR) {
                                user.setState(UserState.ENTERING_BUY_AMOUNT_XMR);
                            }
                            userService.update(user);
                            // Показываем меню ввода суммы
                            showEnterAmountMenu(chatId, user, crypto1, bot);
                        }
                    }
                } else {
                    // Если заявка не найдена, возвращаем в главное меню
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
            case CHOOSING_INPUT_METHOD:
                System.out.println("DEBUG: Back from input method to buy menu");
                user.setState(UserState.BUY_MENU);
                userService.update(user);
                showBuyMenu(chatId, bot);
                break;

            case BUY_MENU:
                System.out.println("DEBUG: Back from buy menu to main menu");
                processMainMenu(chatId, user, bot);
                break;

            case ADMIN_VIEW_USER_DETAILS:
            case ADMIN_CREATE_COUPON:
            case ADMIN_USERS_MENU:
            case ADMIN_VIEW_ALL_USERS:
            case ADMIN_VIEW_RECENT_USERS:
            case ADMIN_USERS_SEARCH_USER:
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
                user.setState(UserState.CALCULATOR_MENU);
                userService.update(user);
                showCalculatorMenu(chatId, user, bot);
                break;

            // По умолчанию - главное меню
            default:
                processMainMenu(chatId, user, bot);
        }
    }
    private void showWalletInput(Long chatId, MyBot bot, User user) {
        Application application = temporaryApplications.get(user.getId());
        if (application == null) {
            processMainMenu(chatId, user, bot);
            return;
        }

        boolean isBuy = application.getUserValueGiveType() == ValueType.RUB;
        CryptoCurrency crypto = application.getCryptoCurrencySafe();

        String message = getWalletMessage(crypto, isBuy);

        // Добавляем информацию о том, куда вернется пользователь при нажатии "Назад"
        String backInfo = isBuy ?
                "\n\n◀️ Назад: к вводу количества " + crypto.getDisplayName() :
                "\n\n◀️ Назад: к вводу количества " + crypto.getDisplayName();

        message += backInfo;

        InlineKeyboardMarkup keyboard = createBackInlineKeyboard();
        int messageId = bot.sendMessageWithKeyboard(chatId, message, keyboard);
        lastMessageId.put(chatId, messageId);
    }



    private void processCommand(Update update, MyBot bot) {
        String text = update.getMessage().getText();
        Long chatId = update.getMessage().getChatId();
        Long telegramId = update.getMessage().getFrom().getId();

        User user = userService.findByTelegramId(telegramId);
        if (user == null) {
            user = userService.findOrCreateUser(update.getMessage().getFrom());
            // Отправляем уведомление админам о новом пользователе
            sendNewUserNotificationToAdmins(user, bot);
        }

        // Команда /start уже обработана в processTextMessage, поэтому здесь просто показываем главное меню
        if ("/start".equals(text)) {
            user.setState(UserState.MAIN_MENU);
            userService.update(user);
            showMainMenu(chatId, user, bot);
        } else {
            // Если пользователь отправил неизвестную команду, показываем главное меню
            user.setState(UserState.MAIN_MENU);
            userService.update(user);
            showMainMenu(chatId, user, bot);
        }
    }
    private CryptoCurrency getCryptoFromOperation(String operation) {
        if (operation == null) {
            System.out.println("ERROR: Operation is null, defaulting to BTC");
            return CryptoCurrency.BTC;
        }

        // Для операций покупки в рублях
        if (operation.contains("BUY_BTC_RUB") || operation.contains("BUY_BTC")) return CryptoCurrency.BTC;
        if (operation.contains("BUY_LTC_RUB") || operation.contains("BUY_LTC")) return CryptoCurrency.LTC;
        if (operation.contains("BUY_XMR_RUB") || operation.contains("BUY_XMR")) return CryptoCurrency.XMR;

        System.out.println("WARNING: Unknown crypto in operation: " + operation + ", defaulting to BTC");
        return CryptoCurrency.BTC;
    }







    private void processUserState(Update update, User user, MyBot bot) {
        Long chatId = update.getMessage().getChatId();
        String text = update.getMessage().getText();

        System.out.println("=== PROCESS_USER_STATE START ===");
        System.out.println("User ID: " + user.getId());
        System.out.println("Current State: " + user.getState());
        System.out.println("Input Text: " + text);
        System.out.println("Chat ID: " + chatId);
        System.out.println("Current Operation: " + currentOperation.get(user.getId()));
        System.out.println("Temporary App: " + (temporaryApplications.containsKey(user.getId()) ? "EXISTS" : "NULL"));

        try {
            switch (user.getState()) {
                case START:
                    System.out.println("DEBUG: START state");
                    break;
                case CAPTCHA_CHECK:
                    System.out.println("DEBUG: CAPTCHA_CHECK state");
                    break;
                case MAIN_MENU:
                    System.out.println("DEBUG: MAIN_MENU state - processing command");
                    processMainMenuCommand(chatId, user, text, bot);
                    break;

                case ADMIN_VIEW_COUPONS:
                    System.out.println("DEBUG: ADMIN_VIEW_COUPONS state");
                    processAdminViewCoupons(chatId, user, text, bot);
                    break;

                case ADMIN_CREATE_COUPON_ADVANCED:
                    System.out.println("DEBUG: ADMIN_CREATE_COUPON_ADVANCED state");
                    processAdminCreateCouponAdvanced(chatId, user, text, bot);
                    break;

                // ========== СОСТОЯНИЯ ВВОДА СУММЫ ПОКУПКИ ==========
                case ENTERING_BUY_AMOUNT_RUB:
                    System.out.println("DEBUG: ENTERING_BUY_AMOUNT_RUB state");
                    processEnteringBuyAmountRub(chatId, user, text, bot);
                    break;

                case ENTERING_BUY_AMOUNT_RUB_BTC:
                    System.out.println("DEBUG: ENTERING_BUY_AMOUNT_RUB_BTC state");
                    processEnteringBuyAmountRubForCrypto(chatId, user, text, bot, CryptoCurrency.BTC);
                    break;

                case ENTERING_BUY_AMOUNT_RUB_LTC:
                    System.out.println("DEBUG: ENTERING_BUY_AMOUNT_RUB_LTC state");
                    processEnteringBuyAmountRubForCrypto(chatId, user, text, bot, CryptoCurrency.LTC);
                    break;

                case ENTERING_BUY_AMOUNT_RUB_XMR:
                    System.out.println("DEBUG: ENTERING_BUY_AMOUNT_RUB_XMR state");
                    processEnteringBuyAmountRubForCrypto(chatId, user, text, bot, CryptoCurrency.XMR);
                    break;

                case CHOOSING_INPUT_METHOD:
                    System.out.println("DEBUG: Back from input method to buy menu");
                    user.setState(UserState.BUY_MENU);
                    userService.update(user);
                    showBuyMenu(chatId, bot);
                    break;

                case BUY_MENU:
                    System.out.println("DEBUG: Back from buy menu to main menu");
                    processMainMenu(chatId, user, bot);
                    break;

                // ========== СОСТОЯНИЯ ВВОДА КРИПТОВАЛЮТЫ ==========
                case ENTERING_BUY_AMOUNT_BTC:
                    System.out.println("DEBUG: ENTERING_BUY_AMOUNT_BTC state");
                    processEnteringBuyAmountCrypto(chatId, user, text, bot, CryptoCurrency.BTC);
                    break;

                case ENTERING_BUY_AMOUNT_LTC:
                    System.out.println("DEBUG: ENTERING_BUY_AMOUNT_LTC state");
                    processEnteringBuyAmountCrypto(chatId, user, text, bot, CryptoCurrency.LTC);
                    break;

                case ENTERING_BUY_AMOUNT_XMR:
                    System.out.println("DEBUG: ENTERING_BUY_AMOUNT_XMR state");
                    processEnteringBuyAmountCrypto(chatId, user, text, bot, CryptoCurrency.XMR);
                    break;

                // ========== СОСТОЯНИЯ ПОДТВЕРЖДЕНИЯ ЗАЯВКИ ==========
                case CONFIRMING_APPLICATION:
                    System.out.println("DEBUG: CONFIRMING_APPLICATION state");
                    processConfirmingApplication(chatId, user, text, bot);
                    break;

                case APPLYING_COUPON:
                    System.out.println("DEBUG: APPLYING_COUPON state");
                    processApplyingCoupon(chatId, user, text, bot);
                    break;

                case APPLYING_COUPON_FINAL:
                    System.out.println("DEBUG: APPLYING_COUPON_FINAL state");
                    processApplyingCouponFinal(chatId, user, text, bot);
                    break;

                case VIEWING_APPLICATIONS:
                    System.out.println("DEBUG: VIEWING_APPLICATIONS state");
                    processViewingApplications(chatId, user, bot);
                    break;

                case VIEWING_COUPONS:
                    System.out.println("DEBUG: VIEWING_COUPONS state");
                    processViewingCoupons(chatId, user, bot);
                    break;

                case REFERRAL_MENU:
                    System.out.println("DEBUG: REFERRAL_MENU state");
                    processReferralMenu(chatId, user, text, bot);
                    break;

                case CREATING_REFERRAL_CODE:
                    System.out.println("DEBUG: CREATING_REFERRAL_CODE state");
                    processCreatingReferralCode(chatId, user, text, bot);
                    break;

                case ENTERING_REFERRAL_CODE:
                    System.out.println("DEBUG: ENTERING_REFERRAL_CODE state");
                    processEnteringReferralCode(chatId, user, text, bot);
                    break;

                case OTHER_MENU:
                    System.out.println("DEBUG: OTHER_MENU state");
                    processOtherMenu(chatId, user, text, bot);
                    break;

                case CALCULATOR_MENU:
                    System.out.println("DEBUG: CALCULATOR_MENU state");
                    processCalculatorMenu(chatId, user, text, bot);
                    break;

                case CALCULATOR_BUY:
                    System.out.println("DEBUG: CALCULATOR_BUY state");
                    processCalculatorBuy(chatId, user, text, bot);
                    break;


                case CONFIRMING_VIP:
                    System.out.println("DEBUG: CONFIRMING_VIP state");
                    processVipConfirmation(chatId, user, text, bot);
                    break;

                case ENTERING_WALLET:
                    System.out.println("DEBUG: ENTERING_WALLET state");
                    processEnteringWallet(chatId, user, text, bot);
                    break;

                case USING_BONUS_BALANCE:
                    System.out.println("DEBUG: USING_BONUS_BALANCE state");
                    processBonusUsageText(chatId, user, text, bot);
                    break;

                // ========== АДМИНСКИЕ СОСТОЯНИЯ ==========
                case ADMIN_MAIN_MENU:
                    System.out.println("DEBUG: ADMIN_MAIN_MENU state");
                    processAdminMainMenu(chatId, user, text, bot);
                    break;

                case ADMIN_VIEW_ALL_APPLICATIONS:
                    System.out.println("DEBUG: ADMIN_VIEW_ALL_APPLICATIONS state");
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
                    System.out.println("DEBUG: ADMIN_VIEW_ACTIVE_APPLICATIONS state");
                    if (text.equals("🔙 Назад")) {
                        user.setState(UserState.ADMIN_MAIN_MENU);
                        userService.update(user);
                        showAdminMainMenu(chatId, bot);
                    } else {
                        processAdminActiveApplicationsSelection(chatId, user, text, bot);
                    }
                    break;

                case ADMIN_SEARCH_APPLICATION:
                    System.out.println("DEBUG: ADMIN_SEARCH_APPLICATION state");
                    if (text.equals("🔙 Назад")) {
                        user.setState(UserState.ADMIN_MAIN_MENU);
                        userService.update(user);
                        showAdminMainMenu(chatId, bot);
                    } else {
                        processAdminApplicationSearch(chatId, user, text, bot);
                    }
                    break;

                case ADMIN_BROADCAST_MESSAGE:
                    System.out.println("DEBUG: ADMIN_BROADCAST_MESSAGE state");
                    if (text != null && text.equals("🔙 Назад")) {
                        user.setState(UserState.ADMIN_MAIN_MENU);
                        userService.update(user);
                        showAdminMainMenu(chatId, bot);
                    } else if (update.hasMessage()) {
                        processBroadcastMessage(chatId, user, update, bot);
                    }
                    break;

                case ADMIN_VIEWING_ALL_APPLICATIONS:
                    System.out.println("DEBUG: ADMIN_VIEWING_ALL_APPLICATIONS state");
                    processAdminApplicationSelection(chatId, user, text, bot);
                    break;

                case ADMIN_VIEWING_APPLICATION_DETAILS:
                    System.out.println("DEBUG: ADMIN_VIEWING_APPLICATION_DETAILS state");
                    processAdminApplicationActions(chatId, user, text, bot);
                    break;

                case ADMIN_COMMISSION_SETTINGS:
                    System.out.println("DEBUG: ADMIN_COMMISSION_SETTINGS state");
                    processAdminCommissionSettings(chatId, user, text, bot);
                    break;

                case ADMIN_VIEW_USER_DETAILS:
                    System.out.println("DEBUG: ADMIN_VIEW_USER_DETAILS state");
                    processAdminUserSearch(chatId, user, text, bot);
                    break;

                case ADMIN_CREATE_COUPON:
                    System.out.println("DEBUG: ADMIN_CREATE_COUPON state");
                    processCreateCoupon(chatId, user, text, bot);
                    break;

                case ADMIN_MY_APPLICATIONS:
                    System.out.println("DEBUG: ADMIN_MY_APPLICATIONS state");
                    processAdminMyApplicationsSelection(chatId, user, text, bot);
                    break;

                case ADMIN_MANAGE_BONUS_BALANCE:
                    System.out.println("DEBUG: ADMIN_MANAGE_BONUS_BALANCE state");
                    processAdminBonusBalanceManagement(chatId, user, text, bot);
                    break;

                case ADMIN_USERS_MENU:
                    System.out.println("DEBUG: ADMIN_USERS_MENU state");
                    processAdminUsersMenu(chatId, user, text, bot);
                    break;

                case ADMIN_VIEW_ALL_USERS:
                    System.out.println("DEBUG: ADMIN_VIEW_ALL_USERS state");
                    processAdminViewAllUsers(chatId, user, text, bot);
                    break;

                case ADMIN_VIEW_RECENT_USERS:
                    System.out.println("DEBUG: ADMIN_VIEW_RECENT_USERS state");
                    processAdminViewRecentUsers(chatId, user, text, bot);
                    break;

                case ADMIN_USERS_SEARCH_USER:
                    System.out.println("DEBUG: ADMIN_USERS_SEARCH_USER state");
                    processAdminUsersSearchUser(chatId, user, text, bot);
                    break;

                default:
                    System.out.println("DEBUG: UNKNOWN STATE: " + user.getState());
                    // При неизвестном состоянии показываем главное меню
                    String errorMessage = "❌ Неизвестное состояние. Возврат в главное меню.";
                    bot.sendMessage(chatId, errorMessage);
                    processMainMenu(chatId, user, bot);
            }
        } catch (Exception e) {
            System.out.println("ERROR in processUserState: " + e.getMessage());
            e.printStackTrace();

            // При любой ошибке возвращаем в главное меню
            String errorMessage = "❌ Произошла ошибка. Возврат в главное меню.";
            bot.sendMessage(chatId, errorMessage);
            processMainMenu(chatId, user, bot);
        }

        System.out.println("=== PROCESS_USER_STATE END ===");
        System.out.println("Final State: " + user.getState());
        System.out.println("=================================");
    }

    // Удаляем старый метод processEnteringBuyAmountBtc и заменяем на общий метод
    private void processEnteringBuyAmountCrypto(Long chatId, User user, String text, MyBot bot, CryptoCurrency crypto) {
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
                    BigDecimal cryptoAmount = toBigDecimal(text);
                    if (cryptoAmount.compareTo(BigDecimal.ZERO) <= 0) {
                        int messageId = bot.sendMessageWithKeyboard(chatId,
                                "❌ Количество должно быть больше 0", createEnterAmountInlineKeyboard());
                        lastMessageId.put(chatId, messageId);
                        addMessageToHistory(chatId, messageId);
                        return;
                    }

                    // Получаем свежую цену перед расчетом
                    BigDecimal cryptoPrice = cryptoPriceService.getFreshPrice(crypto.name(), "RUB");
                    BigDecimal rubAmount = cryptoAmount.multiply(cryptoPrice);
                    BigDecimal commission = commissionService.calculateCommission(rubAmount);
                    BigDecimal commissionPercent = commissionService.getCommissionPercent(rubAmount);
                    BigDecimal totalAmount = commissionService.calculateTotalWithCommission(rubAmount);

                    Application application = new Application();
                    application.setUser(user);
                    application.setCryptoCurrency(crypto); // Устанавливаем тип криптовалюты
                    application.setUserValueGetType(ValueType.valueOf(crypto.name())); // BTC, LTC или XMR
                    application.setUserValueGiveType(ValueType.RUB);
                    application.setOriginalGiveValue(rubAmount); // Сохраняем сумму БЕЗ комиссии
                    application.setOriginalGetValue(cryptoAmount);
                    application.setUserValueGiveValue(totalAmount);
                    application.setUserValueGetValue(cryptoAmount);
                    application.setCalculatedGetValue(cryptoAmount);
                    application.setCalculatedGiveValue(totalAmount);
                    application.setCommissionAmount(commission);
                    application.setCommissionPercent(commissionPercent);
                    application.setTitle("Покупка " + crypto.getSymbol() + " за RUB");
                    application.setStatus(ApplicationStatus.FREE);

                    temporaryApplications.put(user.getId(), application);

                    String message = getWalletMessage(crypto, true);
                    InlineKeyboardMarkup keyboard = createBackInlineKeyboard();
                    int messageId = bot.sendMessageWithKeyboard(chatId, message, keyboard);
        lastMessageId.put(chatId, messageId);
        addMessageToHistory(chatId, messageId);

                    user.setState(UserState.ENTERING_WALLET);
                    userService.update(user);

                } catch (NumberFormatException e) {
                    int messageId = bot.sendMessageWithKeyboard(chatId,
                            "❌ Пожалуйста, введите корректное число", createEnterAmountInlineKeyboard());
                    lastMessageId.put(chatId, messageId);
                    addMessageToHistory(chatId, messageId);
                }
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
                int messageId = bot.sendMessageWithKeyboard(chatId, cancelMessage, createMainMenuInlineKeyboard(user));
                lastMessageId.put(chatId, messageId);
                addMessageToHistory(chatId, messageId);
                user.setState(UserState.MAIN_MENU);
                userService.update(user);
                break;
            case "🔙 Назад":
                user.setState(UserState.APPLYING_COUPON_FINAL);
                userService.update(user);
                showCouponApplication(chatId, user, application, bot);
                break;
            default:
                messageId = bot.sendMessageWithKeyboard(chatId, "❌ Пожалуйста, используйте кнопки", createFinalConfirmationInlineKeyboard());
                lastMessageId.put(chatId, messageId);
                addMessageToHistory(chatId, messageId);
        }
    }

    private void processEnteringBuyAmountRubForCrypto(Long chatId, User user, String text, MyBot bot, CryptoCurrency crypto) {
        System.out.println("=== PROCESS_ENTERING_BUY_AMOUNT_RUB_FOR_CRYPTO START ===");
        System.out.println("Crypto: " + crypto);
        System.out.println("User ID: " + user.getId());
        System.out.println("Current State: " + user.getState());
        System.out.println("Input Text: " + text);

        String currentOp = currentOperation.get(user.getId());
        System.out.println("Current Operation: " + currentOp);

        // Проверяем контекст операции
        String expectedOperation = "BUY_" + crypto.name() + "_RUB";
        if (!expectedOperation.equals(currentOp)) {
            System.out.println("ERROR: Operation mismatch! Expected: " + expectedOperation + ", Got: " + currentOp);
            String errorMessage = "❌ Ошибка сессии. Пожалуйста, начните заново.";
            int messageId = bot.sendMessageWithKeyboard(chatId, errorMessage, createMainMenuInlineKeyboard(user));
            lastMessageId.put(chatId, messageId);

            user.setState(UserState.MAIN_MENU);
            userService.update(user);
            currentOperation.remove(user.getId());
            return;
        }

        // Обработка навигационных команд
        if (text.equals("🔙 Назад")) {
            System.out.println("DEBUG: Handling back navigation to input method menu");
            user.setState(UserState.CHOOSING_INPUT_METHOD);
            userService.update(user);
            showInputMethodMenu(chatId, user, crypto, bot);
            return;
        }

        if (text.equals("🔙 Главное меню")) {
            System.out.println("DEBUG: Handling main menu navigation");
            processMainMenu(chatId, user, bot);
            return;
        }

        // Обработка числового ввода
        try {
            System.out.println("DEBUG: Processing numeric input: " + text);

            // Очистка ввода от пробелов и запятых
            String cleanText = text.replace(",", ".").replace(" ", "").trim();
            BigDecimal rubAmount = new BigDecimal(cleanText);


            // Валидация суммы
            if (rubAmount.compareTo(BigDecimal.valueOf(1000)) < 0) {
                String errorMessage = "❌ Минимальная сумма заявки 1000 рублей";
                int messageId = bot.sendMessageWithKeyboard(chatId, errorMessage, createEnterAmountInlineKeyboard());
                lastMessageId.put(chatId, messageId);
                return;
            }

            if (rubAmount.compareTo(BigDecimal.valueOf(500000)) > 0) {
                String errorMessage = "❌ Максимальная сумма заявки 500,000 рублей";
                int messageId = bot.sendMessageWithKeyboard(chatId, errorMessage, createEnterAmountInlineKeyboard());
                lastMessageId.put(chatId, messageId);
                return;
            }

            // Получаем свежую цену перед расчетом
            BigDecimal cryptoPrice = cryptoPriceService.getFreshPrice(crypto.name(), "RUB");

            if (cryptoPrice == null || cryptoPrice.compareTo(BigDecimal.ZERO) <= 0) {
                throw new RuntimeException("Не удалось получить курс " + crypto.getSymbol());
            }

            // Расчет количества криптовалюты БЕЗ комиссии
            BigDecimal cryptoAmount = rubAmount.divide(cryptoPrice, 8, RoundingMode.HALF_UP);
            // РАСЧЕТ КОМИССИИ ДЛЯ ПОКУПКИ
            BigDecimal commission = commissionService.calculateCommission(rubAmount);
            BigDecimal totalAmountWithCommission = commissionService.calculateTotalWithCommission(rubAmount);
            BigDecimal commissionPercent = commissionService.getCommissionPercent(rubAmount);


            // Создаем заявку
            Application application = new Application();
            application.setUser(user);
            application.setCryptoCurrency(crypto);
            application.setUserValueGetType(ValueType.valueOf(crypto.name())); // Получаем крипту
            application.setUserValueGiveType(ValueType.RUB);
            application.setOriginalGiveValue(rubAmount); // Сохраняем сумму БЕЗ комиссии для кэшбека
            application.setOriginalGetValue(cryptoAmount);
            // Отдаем рубли

            // Устанавливаем значения С КОМИССИЕЙ
            application.setUserValueGiveValue(totalAmountWithCommission); // Сумма к оплате (включая комиссию)
            application.setUserValueGetValue(cryptoAmount);               // Количество крипты (без изменений)
            application.setCalculatedGetValue(cryptoAmount);
            application.setCalculatedGiveValue(totalAmountWithCommission);

            // Сохраняем информацию о комиссии
            application.setCommissionAmount(commission);
            application.setCommissionPercent(commissionPercent);

            application.setTitle("Покупка " + crypto.getSymbol() + " за RUB");
            application.setStatus(ApplicationStatus.FREE);
            application.setCreatedAt(LocalDateTime.now());
            application.setExpiresAt(LocalDateTime.now().plusMinutes(40));

            temporaryApplications.put(user.getId(), application);

            // Информационное сообщение с деталями комиссии
            String infoMessage = String.format("""
                ✅ Сумма рассчитана с учетом комиссии!
                
                💰 Введенная сумма: %s
                💸 Комиссия: %s (%s)
                💵 Итого к оплате: %s
                🪙 Вы получите: %s
                
                Курс %s: %s
                """,
                    formatRubAmount(rubAmount),
                    formatRubAmount(commission),
                    formatPercent(commissionPercent),
                    formatRubAmount(totalAmountWithCommission),
                    formatCryptoAmount(cryptoAmount, crypto),
                    crypto.getDisplayName(),
                    formatRubAmount(cryptoPrice)
            );

            bot.sendMessage(chatId, infoMessage);

            // Переход к вводу кошелька
            String walletMessage = getWalletMessage(crypto, true);
            InlineKeyboardMarkup keyboard = createBackInlineKeyboard();
            int messageId = bot.sendMessageWithKeyboard(chatId, walletMessage, keyboard);
            lastMessageId.put(chatId, messageId);

            // Обновляем состояние пользователя
            user.setState(UserState.ENTERING_WALLET);
            userService.update(user);

            System.out.println("DEBUG: State update to ENTERING_WALLET: completed");

            // Двойная проверка
            User updatedUser = userService.find(user.getId());
            System.out.println("DEBUG: Verified state in DB: " + (updatedUser != null ? updatedUser.getState() : "USER_NOT_FOUND"));

        } catch (NumberFormatException e) {
            System.out.println("DEBUG: NumberFormatException: " + e.getMessage());
            String errorMessage = "❌ Пожалуйста, введите корректное число (например: 1500 или 2500.50)";
            int messageId = bot.sendMessageWithKeyboard(chatId, errorMessage, createEnterAmountInlineKeyboard());
            lastMessageId.put(chatId, messageId);
        } catch (ArithmeticException e) {
            System.out.println("DEBUG: ArithmeticException: " + e.getMessage());
            String errorMessage = "❌ Ошибка при расчете. Попробуйте другую сумму.";
            int messageId = bot.sendMessageWithKeyboard(chatId, errorMessage, createEnterAmountInlineKeyboard());
            lastMessageId.put(chatId, messageId);
        } catch (Exception e) {
            System.out.println("DEBUG: Exception: " + e.getMessage());
            e.printStackTrace();
            String errorMessage = "❌ Ошибка: " + e.getMessage() + "\n\nПожалуйста, попробуйте снова.";
            int messageId = bot.sendMessageWithKeyboard(chatId, errorMessage, createEnterAmountInlineKeyboard());
            lastMessageId.put(chatId, messageId);
        }

        System.out.println("=== PROCESS_ENTERING_BUY_AMOUNT_RUB_FOR_CRYPTO END ===");
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

        processBonusUsage(chatId, user, text, bot, null);
    }

    private void createApplicationFinal(Long chatId, User user, Application application, MyBot bot) {
        if (application.getUserValueGetType() == null || application.getUserValueGiveType() == null) {
            String errorMessage = "❌ Ошибка: некорректные типы значений в заявке.";
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, errorMessage, createMainMenuInlineKeyboard(user)));
            temporaryApplications.remove(user.getId());
            user.setState(UserState.MAIN_MENU);
            userService.update(user);
            return;
        }

        // Применяем купон если есть
        if (application.getAppliedCoupon() != null) {
            Coupon coupon = application.getAppliedCoupon();
            if (coupon.getDiscountPercent() != null) {
                BigDecimal discountMultiplier = BigDecimal.ONE.subtract(coupon.getDiscountPercent().divide(BigDecimal.valueOf(100)));
                application.setCalculatedGiveValue(application.getCalculatedGiveValue().multiply(discountMultiplier));
            } else if (coupon.getDiscountAmount() != null) {
                application.setCalculatedGiveValue(application.getCalculatedGiveValue().subtract(coupon.getDiscountAmount()));
            }

            // Обновляем счетчик использования купона
            coupon.setUsedCount(coupon.getUsedCount() + 1);
            couponService.updateCoupon(coupon);
        }

        // Применяем бонусный баланс
        if (application.getUsedBonusBalance().compareTo(BigDecimal.ZERO) > 0) {
            if (user.getBonusBalance().compareTo(application.getUsedBonusBalance()) >= 0) {
                user.setBonusBalance(user.getBonusBalance().subtract(application.getUsedBonusBalance()));
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

        // Устанавливаем срок действия 40 минут
        application.setExpiresAt(LocalDateTime.now().plusMinutes(40));
        application.setStatus(ApplicationStatus.FREE);

        // СОХРАНЯЕМ ЗАЯВКУ В БАЗУ
        applicationService.create(application);
        temporaryApplications.remove(user.getId());

        // Отправляем сообщение пользователю
        String applicationMessage = formatApplicationMessage(application);
        InlineKeyboardMarkup keyboard = createApplicationInlineKeyboard(application.getId());
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, applicationMessage, keyboard);

        // Сохраняем ID сообщения для возможного удаления
        application.setTelegramMessageId(messageId);
        applicationService.update(application);

        user.setState(UserState.MAIN_MENU);
        userService.update(user);

        // Уведомление админам
        try {
            String adminNotification = String.format(
                    "🔔 Новая заявка #%d!\n\n" +
                            "👤 От: @%s (ID: %d)\n" +
                            "💸 Тип: %s\n" +
                            "💰 Сумма: %s %s",
                    application.getId(),
                    user.getUsername() != null ? user.getUsername() : "??",
                    user.getTelegramId(),
                    (application.getUserValueGetType() == ValueType.BTC ||
                     application.getUserValueGetType() == ValueType.LTC ||
                     application.getUserValueGetType() == ValueType.XMR) ?
                            "Покупка " + application.getCryptoCurrencySafe().getSymbol() :
                            "Продажа " + application.getCryptoCurrencySafe().getSymbol(),
                    (application.getUserValueGetType() == ValueType.BTC ||
                     application.getUserValueGetType() == ValueType.LTC ||
                     application.getUserValueGetType() == ValueType.XMR) ?
                            formatRubAmount(application.getCalculatedGiveValue()) :
                            formatCryptoAmount(application.getCalculatedGiveValue(), application.getCryptoCurrencySafe()),
                    (application.getUserValueGetType() == ValueType.BTC ||
                     application.getUserValueGetType() == ValueType.LTC ||
                     application.getUserValueGetType() == ValueType.XMR) ? "₽" : application.getCryptoCurrencySafe().getSymbol()
            );

            for (Long adminId : adminConfig.getAdminUserIds()) {
                bot.sendMessage(adminId, adminNotification);
            }
        } catch (Exception e) {
            System.err.println("Не удалось отправить уведомление админам: " + e.getMessage());
        }
        System.out.println("DEBUG: Saving application with types - " +
                "getType: " + application.getUserValueGetType() + ", " +
                "giveType: " + application.getUserValueGiveType() + ", " +
                "crypto: " + application.getCryptoCurrencySafe());
    }

    private String formatExpiresAt(LocalDateTime expiresAt) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
        return expiresAt.format(formatter);
    }

    private String formatApplicationMessage(Application application) {
        boolean isBuy = application.getUserValueGetType() == ValueType.BTC || application.getUserValueGetType() == ValueType.LTC || application.getUserValueGetType() == ValueType.XMR ;
        String operationType = isBuy ? "покупку" : "продажу";
        String cryptoName = application.getCryptoCurrencySafe().getDisplayName();
        String cryptoIcon = application.getCryptoCurrencySafe().getIcon();
        String walletLabel = isBuy ?
                "🔐 " + cryptoName + "-кошелек" :
                "💳 Реквизиты для выплаты";

        StringBuilder message = new StringBuilder();
        message.append(String.format("""
        ✅ Заявка на %s %s создана!
        
        📝 ID: %s

        ━━━━━━━━━━━━━━━━━━━━━━━
        💰 Детали заявки
        ━━━━━━━━━━━━━━━━━━━━━━━
        
        • Получаете: %s
        • Отдаете: %s
        • %s: %s
        • Приоритет: %s
        """,
                operationType,
                cryptoName,
                application.getId(), // Используем ID вместо UUID
                isBuy ?
                        formatCryptoAmount(application.getCalculatedGetValue(), application.getCryptoCurrencySafe()) :
                        formatRubAmount(application.getCalculatedGetValue()),
                isBuy ?
                        formatRubAmount(application.getCalculatedGiveValue()) :
                        formatCryptoAmount(application.getCalculatedGiveValue(), application.getCryptoCurrencySafe()),
                walletLabel,
                application.getWalletAddress(),
                application.getIsVip() ? "👑 VIP" : "🔹 Обычный"
        ));

        // Добавляем информацию о купоне
        if (application.getAppliedCoupon() != null) {
            Coupon coupon = application.getAppliedCoupon();
            String discount = coupon.getDiscountPercent() != null ?
                    coupon.getDiscountPercent() + "%" :
                    formatRubAmount(coupon.getDiscountAmount());
            message.append(String.format("• 🎫 Купон (%s): %s\n", coupon.getCode(), discount));
        }

        // Добавляем информацию о бонусах
        if (application.getUsedBonusBalance().compareTo(BigDecimal.ZERO) > 0) {
            message.append(String.format("• 🎁 Использовано бонусов: %s\n",
                    formatRubAmount(application.getUsedBonusBalance())));
        }

        message.append(String.format("""

        ⏳ Срок действия: до %s

        👨‍💼 Перешлите эту заявку оператору: @SUP_CN

        📊 Статус: %s
        
        💡 Если у вас спам-блок, нажмите кнопку 🆘 ниже
        """,
                formatExpiresAt(application.getExpiresAt()),
                application.getStatus().getDisplayName()
        ));

        System.out.println("DEBUG: Заявка " + application.getId());
        System.out.println("DEBUG: isBuy = " + isBuy);
        System.out.println("DEBUG: Получаем: " + application.getCalculatedGetValue() + " " + application.getUserValueGetType());
        System.out.println("DEBUG: Отдаем: " + application.getCalculatedGiveValue() + " " + application.getUserValueGiveType());


        return message.toString();
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

        ApplicationStatus oldStatus = application.getStatus(); // Сохраняем старый статус для логирования

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
                break;
            case "🔴 Отменить":
                application.setStatus(ApplicationStatus.CANCELLED);

                if (application.getUsedBonusBalance().compareTo(BigDecimal.ZERO) > 0) {
                    User applicationUser = application.getUser();
                    applicationUser.setBonusBalance(applicationUser.getBonusBalance().add(application.getUsedBonusBalance()));
                    userService.update(applicationUser);

                    String bonusReturnMessage = String.format(
                            "💸 Вам возвращен бонусный баланс: %s\n" +
                                    "📝 Причина: отмена заявки #%d",
                            formatRubAmount(application.getUsedBonusBalance()), application.getId()
                    );
                    bot.sendMessage(applicationUser.getTelegramId(), bonusReturnMessage);
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

        // ОБНОВЛЯЕМ СООБЩЕНИЕ У ПОЛЬЗОВАТЕЛЯ
        if (oldStatus != application.getStatus()) {
            updateUserApplicationMessage(application, bot);
        }

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
            // ПРАВИЛЬНОЕ ОПРЕДЕЛЕНИЕ ТИПА ОПЕРАЦИИ
            boolean isBuy = application.getUserValueGetType() == ValueType.BTC ||
                    application.getUserValueGetType() == ValueType.LTC ||
                    application.getUserValueGetType() == ValueType.XMR;

            if (isBuy) {
                // Покупка криптовалюты - получаем крипту, отдаем рубли
                user.setCompletedBuyApplications(user.getCompletedBuyApplications() + 1);
                user.setTotalBuyAmount(user.getTotalBuyAmount().add(application.getOriginalGiveValue()));
            } else {
                // Продажа криптовалюты - получаем рубли, отдаем крипту
                user.setCompletedSellApplications(user.getCompletedSellApplications() + 1);
                user.setTotalSellAmount(user.getTotalSellAmount().add(application.getOriginalGetValue()));
            }

            user.setTotalApplications(user.getTotalApplications() + 1);

            // Сохраняем изменения
            userService.update(user);

            System.out.println("STATISTICS DEBUG: User " + user.getId() +
                    ", Operation: " + (isBuy ? "BUY" : "SELL") +
                    ", Completed Buy Apps: " + user.getCompletedBuyApplications() +
                    ", Completed Sell Apps: " + user.getCompletedSellApplications() +
                    ", Total Apps: " + user.getTotalApplications());
        }
    }

    private void updateUserApplicationMessage(Application application, MyBot bot) {
        try {
            if (application.getTelegramMessageId() != null && application.getUser() != null) {
                String updatedMessage = formatApplicationMessage(application);
                InlineKeyboardMarkup keyboard = createApplicationInlineKeyboard(application.getId());

                // Редактируем существующее сообщение
                bot.editMessageText(
                        application.getUser().getTelegramId(),
                        application.getTelegramMessageId(),
                        updatedMessage,
                        keyboard
                );

                System.out.println("DEBUG: Updated application message for user " +
                        application.getUser().getId() + ", status: " + application.getStatus());
            }
        } catch (Exception e) {
            System.err.println("Ошибка при обновлении сообщения пользователя: " + e.getMessage());
            // Если не удалось отредактировать сообщение, можно отправить новое
            try {
                String updatedMessage = formatApplicationMessage(application);
                InlineKeyboardMarkup keyboard = createApplicationInlineKeyboard(application.getId());
                int newMessageId = bot.sendMessageWithInlineKeyboard(
                        application.getUser().getTelegramId(),
                        updatedMessage,
                        keyboard
                );

                // Обновляем ID сообщения в заявке
                application.setTelegramMessageId(newMessageId);
                applicationService.update(application);

            } catch (Exception ex) {
                System.err.println("Не удалось отправить новое сообщение пользователю: " + ex.getMessage());
            }
        }
    }

    private void processAdminCommissionSettings(Long chatId, User user, String text, MyBot bot) {
        System.out.println("=== PROCESS_ADMIN_COMMISSION_SETTINGS START ===");
        System.out.println("Admin User ID: " + user.getId());
        System.out.println("Input Text: " + text);

        // Проверяем права администратора
        if (!adminConfig.isAdmin(user.getId())) {
            System.out.println("ERROR: User is not admin");
            String errorMessage = "❌ Доступ запрещен";
            int messageId = bot.sendMessageWithKeyboard(chatId, errorMessage, createMainMenuInlineKeyboard(user));
            lastMessageId.put(chatId, messageId);
            return;
        }

        // Обработка навигационных команд
        if (text.equals("🔙 Назад")) {
            System.out.println("DEBUG: Handling back navigation to admin menu");
            user.setState(UserState.ADMIN_MAIN_MENU);
            userService.update(user);
            showAdminMainMenu(chatId, bot);
            return;
        }

        if (text.equals("🔙 Главное меню")) {
            System.out.println("DEBUG: Handling main menu navigation");
            processMainMenu(chatId, user, bot);
            return;
        }

        if (text.equals("🧪 Тест комиссий")) {
            System.out.println("DEBUG: Running commission test");
            testCommissionCalculation(chatId, bot);
            return;
        }

        // Обработка обновления комиссий
        try {
            String[] parts = text.split(" ");
            if (parts.length == 2) {
                String rangeStr = parts[0];
                BigDecimal percent = new BigDecimal(parts[1]);

                System.out.println("COMMISSION DEBUG: Admin updating commission - Range: " + rangeStr + ", Percent: " + percent);

                // Валидация процента комиссии
                if (percent.compareTo(BigDecimal.ZERO) <= 0 || percent.compareTo(BigDecimal.valueOf(100)) >= 0) {
                    String errorMessage = "❌ Процент комиссии должен быть между 0.1 и 99.9";
                    int messageId = bot.sendMessageWithKeyboard(chatId, errorMessage, createBackToAdminKeyboard());
                    lastMessageId.put(chatId, messageId);
                    return;
                }

                if (rangeStr.contains("-")) {
                    // Обработка диапазона (например: "1000-1999")
                    String[] rangeParts = rangeStr.split("-");
                    if (rangeParts.length != 2) {
                        throw new IllegalArgumentException("Неверный формат диапазона. Используйте: 1000-1999");
                    }

                    BigDecimal min = new BigDecimal(rangeParts[0]);
                    BigDecimal max = new BigDecimal(rangeParts[1]);

                    if (min.compareTo(max) >= 0) {
                        throw new IllegalArgumentException("Минимальное значение должно быть меньше максимального");
                    }

                    // Проверяем, что диапазон соответствует существующим настройкам
                    if (!isValidRange(min)) {
                        throw new IllegalArgumentException("Диапазон должен начинаться с одного из порогов: 1000, 2000, 3000, 5000, 10000, 15000, 20000");
                    }

                    commissionConfig.updateCommissionRange(min, max, percent);

                    String message = String.format("✅ Комиссия обновлена!\n\nДиапазон: %s-%s ₽\nКомиссия: %.1f%%",
                            min, max, percent.doubleValue());
                    int messageId = bot.sendMessageWithKeyboard(chatId, message, createBackToAdminKeyboard());
                    lastMessageId.put(chatId, messageId);

                } else {
                    // Обработка минимальной суммы (например: "5000")
                    BigDecimal min = new BigDecimal(rangeStr);

                    if (min.compareTo(BigDecimal.ZERO) <= 0) {
                        throw new IllegalArgumentException("Минимальная сумма должна быть больше 0");
                    }

                    // Проверяем, что порог соответствует существующим настройкам
                    if (!isValidThreshold(min)) {
                        throw new IllegalArgumentException("Порог должен быть одним из: 1000, 2000, 3000, 5000, 10000, 15000, 20000");
                    }

                    commissionConfig.updateCommissionRange(min, percent);

                    String message = String.format("✅ Комиссия обновлена!\n\nОт %s ₽\nКомиссия: %.1f%%",
                            rangeStr, percent.doubleValue());
                    int messageId = bot.sendMessageWithKeyboard(chatId, message, createBackToAdminKeyboard());
                    lastMessageId.put(chatId, messageId);
                }

                // Логируем обновление
                System.out.println("COMMISSION DEBUG: Commission updated successfully");

                // Показываем обновленные настройки
                showAdminCommissionSettings(chatId, user, bot);
                return;
            }
        } catch (NumberFormatException e) {
            System.out.println("COMMISSION DEBUG: NumberFormatException: " + e.getMessage());
            String errorMessage = "❌ Неверный числовой формат. Используйте числа (например: 1000 50.0)";
            int messageId = bot.sendMessageWithKeyboard(chatId, errorMessage, createBackToAdminKeyboard());
            lastMessageId.put(chatId, messageId);
        } catch (IllegalArgumentException e) {
            System.out.println("COMMISSION DEBUG: IllegalArgumentException: " + e.getMessage());
            String errorMessage = "❌ " + e.getMessage();
            int messageId = bot.sendMessageWithKeyboard(chatId, errorMessage, createBackToAdminKeyboard());
            lastMessageId.put(chatId, messageId);
        } catch (Exception e) {
            System.out.println("COMMISSION DEBUG: Exception: " + e.getMessage());
            e.printStackTrace();
            String errorMessage = "❌ Ошибка при обновлении комиссии: " + e.getMessage();
            int messageId = bot.sendMessageWithKeyboard(chatId, errorMessage, createBackToAdminKeyboard());
            lastMessageId.put(chatId, messageId);
        }

        // Если не удалось распарсить или произошла ошибка, показываем инструкцию снова
        showAdminCommissionSettings(chatId, user, bot);
        System.out.println("=== PROCESS_ADMIN_COMMISSION_SETTINGS END ===");
    }

    // Вспомогательные методы для валидации порогов
    private boolean isValidThreshold(BigDecimal threshold) {
        Set<BigDecimal> validThresholds = Set.of(
                new BigDecimal("1000"), new BigDecimal("2000"), new BigDecimal("3000"),
                new BigDecimal("5000"), new BigDecimal("10000"), new BigDecimal("15000"),
                new BigDecimal("20000")
        );
        return validThresholds.contains(threshold);
    }

    private boolean isValidRange(BigDecimal minThreshold) {
        return isValidThreshold(minThreshold);
    }

    private void testCommissionCalculation(Long chatId, MyBot bot) {
        System.out.println("COMMISSION DEBUG: Running commission test");

        StringBuilder testResults = new StringBuilder();
        testResults.append("🧪 Тест комиссий:\n\n");

        // Тестовые суммы для проверки комиссий
        BigDecimal[] testAmounts = {
                new BigDecimal("500"),   // Меньше минимальной
                new BigDecimal("1500"),  // 1000-1999
                new BigDecimal("2500"),  // 2000-2999
                new BigDecimal("3500"),  // 3000-4999
                new BigDecimal("6000"),  // 5000-9999
                new BigDecimal("12000"), // 10000-14999
                new BigDecimal("18000"), // 15000-19999
                new BigDecimal("22000")  // 20000-24999
        };

        for (BigDecimal amount : testAmounts) {
            try {
                BigDecimal commission = commissionService.calculateCommission(amount);
                BigDecimal percent = commissionService.getCommissionPercent(amount);
                BigDecimal totalWithCommission = commissionService.calculateTotalWithCommission(amount);
                BigDecimal totalWithoutCommission = commissionService.calculateTotalWithoutCommission(amount);

                testResults.append(String.format("""
                    💰 %s ₽:
                    • Комиссия: %s (%s)
                    • Итого с комиссией: %s
                    • Итого без комиссии: %s
                    
                    """,
                        formatRubAmount(amount),
                        formatRubAmount(commission),
                        formatPercent(percent),
                        formatRubAmount(totalWithCommission),
                        formatRubAmount(totalWithoutCommission)
                ));
            } catch (Exception e) {
                testResults.append(String.format("""
                    ❌ %s ₽: Ошибка расчета - %s
                    
                    """,
                        formatRubAmount(amount),
                        e.getMessage()
                ));
            }
        }

        testResults.append("\n💡 Примечание:\n");
        testResults.append("• Для покупки используется 'Итого с комиссией'\n");
        testResults.append("• Для продажи используется 'Итого без комиссии'");

        bot.sendMessage(chatId, testResults.toString());
        System.out.println("COMMISSION DEBUG: Commission test completed");
    }

    private void processStartCommand(Update update, MyBot bot) {
        Long chatId = update.getMessage().getChatId();
        org.telegram.telegrambots.meta.api.objects.User telegramUser = update.getMessage().getFrom();
        String text = update.getMessage().getText();

        // Очищаем весь чат при команде /start
        clearChatExceptApplications(chatId, bot);

        User user = userService.findOrCreateUser(telegramUser);

        // Отправляем уведомление админам о новом пользователе
        if (userService.wasUserCreated(user, telegramUser)) {
            sendNewUserNotificationToAdmins(user, bot);
        }

        // Обработка реферальных ссылок (формат: /start ref_CODE или /start CODE)
        if (text.contains(" ")) {
            String[] parts = text.split(" ");
            if (parts.length > 1) {
                String refCodeParam = parts[1];
                String refCode = null;
                
                // Поддерживаем формат ref_CODE
                if (refCodeParam.startsWith("ref_")) {
                    refCode = refCodeParam.substring(4); // Убираем префикс "ref_"
                } else {
                    refCode = refCodeParam; // Просто код без префикса
                }
                
                // Ищем реферальный код в базе
                ReferralCode referralCode = referralService.findByCode(refCode);
                if (referralCode != null && referralCode.getIsActive()) {
                    User inviter = referralCode.getUser();
                    if (inviter != null && !inviter.getId().equals(user.getId())) {
                        // Сохраняем реферальный код пользователю
                        user.setUsedReferralCode(refCode);
                        userService.update(user);
                        
                        // ИСПРАВЛЕННЫЙ ВЫЗОВ: передаем код
                        referralService.processReferralRegistration(inviter, user, refCode);
                    }
                }
            }
        }

        String welcomeMessage = """
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━
        ⚠️ ВНИМАНИЕ! Будьте бдительны!
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━

        🛡️ Не подвергайтесь провокациям мошенников!
        ✍️ Наш оператор НИКОГДА НЕ ПИШЕТ ПЕРВЫМ

        ━━━━━━━━━━━━━━━━━━━━━━━━━━━
        📞 АКТУАЛЬНЫЕ КОНТАКТЫ
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━

        🚪 Доступ в проект: @COSANOSTRALOBBYBOT
        👨‍💼 Оператор 24/7: @SUP_CN
        🔧 Техподдержка 24/7: @CN_BUGSY 
          └─ Всегда онлайн, решим любой вопрос!

        ━━━━━━━━━━━━━━━━━━━━━━━━━━━
        🔒 ПРОВЕРКА БЕЗОПАСНОСТИ
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━
        """;

        int welcomeMessageId = bot.sendMessage(chatId, welcomeMessage);
        firstWelcomeMessageId.put(chatId, welcomeMessageId); // Сохраняем ID первого приветственного сообщения

        user.setState(UserState.CAPTCHA_CHECK);
        userService.update(user);
        showCaptcha(chatId, user, bot);
    }


    private void showCaptcha(Long chatId, User user, MyBot bot) {
        CaptchaService.CaptchaChallenge challenge = captchaService.generateCaptcha(user.getId());

        InlineKeyboardMarkup keyboard = createCaptchaKeyboard(challenge.getOptions());
        String message = "🔐 Для продолжения пройдите проверку безопасности\n\n" +
                "Выберите смайлик: \"" + challenge.getCorrectEmoji() + "\"";

        int messageId = bot.sendMessageWithKeyboard(chatId, message, keyboard);
        lastMessageId.put(chatId, messageId);
        addMessageToHistory(chatId, messageId);
    }


    private InlineKeyboardMarkup createBonusBalanceKeyboard(BigDecimal maxUsable) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        if (maxUsable.compareTo(BigDecimal.valueOf(50)) >= 0) {
            List<InlineKeyboardButton> row1 = new ArrayList<>();
            row1.add(createInlineButton("50 ₽", "inline_bonus_50"));

            if (maxUsable.compareTo(BigDecimal.valueOf(100)) >= 0) {
                row1.add(createInlineButton("100 ₽", "inline_bonus_100"));
            }

            if (maxUsable.compareTo(BigDecimal.valueOf(200)) >= 0) {
                row1.add(createInlineButton("200 ₽", "inline_bonus_200"));
            }
            rows.add(row1);
        }

        List<InlineKeyboardButton> row2 = new ArrayList<>();
        row2.add(createInlineButton("Максимум (" + formatRubAmount(maxUsable) + ")", "inline_bonus_max"));

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
            BigDecimal value = BigDecimal.valueOf(Long.valueOf(parts[2]));
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
                if (value.compareTo(BigDecimal.ZERO) < 1 || value.compareTo(BigDecimal.valueOf(100)) == 1) {
                    throw new IllegalArgumentException("Процент скидки должен быть от 1 до 100");
                }
                coupon.setDiscountPercent(value);
            } else if ("amount".equalsIgnoreCase(type)) {
                if (value.compareTo(BigDecimal.ZERO) < 1) {
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


    private void sendCompletionMessageToUser(Application application, MyBot bot) {
        try {
            User user = application.getUser();
            if (user == null) return;

            String message = String.format(
                "🎉 Поздравляем!\n\n" +
                "✅ Ваша заявка #%d успешно выполнена!\n\n" +
                "💰 Получено: %s\n" +
                "💎 Отдано: %s\n\n" +
                "Спасибо за использование нашего сервиса!",
                application.getId(),
                formatCryptoAmount(application.getUserValueGetValue(), application.getCryptoCurrencySafe()),
                application.getUserValueGiveType() == ValueType.RUB ?
                    formatRubAmount(application.getUserValueGiveValue()) :
                    formatCryptoAmount(application.getUserValueGiveValue(), application.getCryptoCurrencySafe())
            );

            InlineKeyboardMarkup keyboard = createCompletionMessageKeyboard();
            bot.sendMessageWithInlineKeyboard(user.getTelegramId(), message, keyboard);

        } catch (Exception e) {
            System.err.println("Ошибка при отправке поздравительного сообщения: " + e.getMessage());
        }
    }

    private InlineKeyboardMarkup createCompletionMessageKeyboard() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // Кнопка оставить отзыв
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton feedbackButton = new InlineKeyboardButton();
        feedbackButton.setText("⭐ Оставить отзыв");
        feedbackButton.setUrl("https://t.me/CN_FEEDBACKBOT");
        row1.add(feedbackButton);

        // Кнопка главное меню
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


    private void processAdminApplicationActionCallback(Long chatId, User user, String callbackData, MyBot bot, String callbackQueryId) {
        try {
            String[] parts = callbackData.split("_");
            Long applicationId = Long.parseLong(parts[parts.length - 1]);

            Application application = applicationService.find(applicationId);
            if (application == null) {
                bot.answerCallbackQuery(callbackQueryId, "❌ Заявка не найдена");
                return;
            }

            ApplicationStatus oldStatus = application.getStatus(); // Сохраняем старый статус

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

                    // РЕФЕРАЛЬНЫЕ ВЫПЛАТЫ
                    referralService.processReferralReward(application);

                    // ОТПРАВЛЯЕМ ПОЗДРАВИТЕЛЬНОЕ СООБЩЕНИЕ ПОЛЬЗОВАТЕЛЮ
                    sendCompletionMessageToUser(application, bot);
                    break;
                case "cancel":
                    application.setStatus(ApplicationStatus.CANCELLED);
                    // Возвращаем бонусный баланс
                    if (application.getUsedBonusBalance().compareTo(BigDecimal.ZERO) > 0) {
                        User applicationUser = application.getUser();
                        applicationUser.setBonusBalance(applicationUser.getBonusBalance().add(application.getUsedBonusBalance()));
                        userService.update(applicationUser);

                        String bonusReturnMessage = String.format(
                                "💸 Вам возвращен бонусный баланс: %s\n" +
                                        "📝 Причина: отмена заявки #%d",
                                formatRubAmount(application.getUsedBonusBalance()), application.getId()
                        );
                        bot.sendMessage(applicationUser.getTelegramId(), bonusReturnMessage);
                    }
                    break;
                case "free":
                    application.setStatus(ApplicationStatus.FREE);
                    application.setAdminId((long) 0); // Снимаем привязку к админу
                    break;
                case "userinfo":
                    // Показываем информацию о пользователе
                    bot.answerCallbackQuery(callbackQueryId, "👤 Загрузка информации...");
                    showUserDetails(chatId, application.getUser(), bot);
                    return;
            }

            applicationService.update(application);

            // ОБНОВЛЯЕМ СООБЩЕНИЕ У ПОЛЬЗОВАТЕЛЯ
            if (oldStatus != application.getStatus()) {
                updateUserApplicationMessage(application, bot);
            }

            String statusMessage = String.format("✅ Статус заявки #%d изменен на: %s",
                    applicationId, application.getStatus().getDisplayName());
            bot.answerCallbackQuery(callbackQueryId, statusMessage);

            // Обновляем меню управления заявкой
            showAdminApplicationManagementMenu(chatId, user, application, bot);

        } catch (Exception e) {
            bot.answerCallbackQuery(callbackQueryId, "❌ Ошибка при обработке");
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
            BigDecimal amount = BigDecimal.ZERO;
            Long targetUserId = Long.parseLong(parts[4]);

            User targetUser = userService.find(targetUserId);
            if (targetUser == null) {
                bot.answerCallbackQuery(callbackQueryId, "❌ Пользователь не найден");
                return;
            }

            switch (operation) {
                case "add":
                    amount = new BigDecimal(parts[3]);
                    targetUser.setBonusBalance(targetUser.getBonusBalance().add(amount));
                    break;
                case "remove":
                    amount = new BigDecimal(parts[3]);
                    BigDecimal newBalance = targetUser.getBonusBalance().subtract(amount);
                    // Не позволяем балансу уйти в отрицательное значение
                    targetUser.setBonusBalance(newBalance.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : newBalance);
                    break;
                case "reset":
                    targetUser.setBonusBalance(BigDecimal.ZERO);
                    break;
            }

            userService.update(targetUser);

            String message = String.format("✅ Бонусный баланс %s на %s\nНовый баланс: %s",
                    operation.equals("reset") ? "обнулен" : (operation.equals("add") ? "пополнен" : "списан"),
                    formatRubAmount(amount),
                    formatRubAmount(targetUser.getBonusBalance()));

            bot.answerCallbackQuery(callbackQueryId, message);
            showUserBonusManagement(chatId, targetUser, bot);

        } catch (Exception e) {
            bot.answerCallbackQuery(callbackQueryId, "❌ Ошибка при операции с балансом");
        }
    }
    private void showEnterAmountRubMenu(Long chatId, User user, CryptoCurrency crypto, MyBot bot) {
        // Получаем текущую цену для справки
        BigDecimal currentPrice = cryptoPriceService.getCurrentPrice(crypto.name(), "RUB");

        String message = String.format("""
        💎 Введите сумму в RUB для покупки %s:
        
        💸 Минимальная сумма: 1000 RUB
        """,
                crypto.getDisplayName(),
                crypto.getSymbol(),
                formatRubAmount(currentPrice)
        );

        InlineKeyboardMarkup inlineKeyboard = createEnterAmountInlineKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);
    }
    private void processInlineButton(Long chatId, User user, String callbackData, MyBot bot, String callbackQueryId) {
        System.out.println("=== PROCESS_INLINE_BUTTON START ===");
        System.out.println("User ID: " + user.getId());
        System.out.println("Callback Data: " + callbackData);
        System.out.println("Current State: " + user.getState());
        System.out.println("Callback Query ID: " + callbackQueryId);

        deletePreviousBotMessage(chatId, bot);

        if (callbackQueryId != null) {
            bot.answerCallbackQuery(callbackQueryId, "🔄 Обработка...");
        }

        try {
            // ========== ОБРАБОТКА АДМИНСКИХ ДЕЙСТВИЙ С ЗАЯВКАМИ ==========
            if (callbackData.startsWith("inline_admin_app_")) {
                System.out.println("DEBUG: Processing admin application action");
                processAdminApplicationActionCallback(chatId, user, callbackData, bot, callbackQueryId);
                return;
            }
            if (callbackData.startsWith("inline_admin_page_")) {
                processAdminPageChange(chatId, user, callbackData, bot);
                return;
            }
            if (callbackData.startsWith("inline_admin_users_prev_") ||
                callbackData.startsWith("inline_admin_users_next_") ||
                callbackData.equals("inline_admin_users_page_info") ||
                callbackData.equals("inline_admin_users_back")) {
                processAdminUsersPageChange(chatId, user, callbackData, bot);
                return;
            }

            // ========== ОБРАБОТКА БОНУСНЫХ ОПЕРАЦИЙ АДМИНА ==========
            if (callbackData.startsWith("inline_bonus_add_") || callbackData.startsWith("inline_bonus_remove_") ||
                    callbackData.startsWith("inline_bonus_reset_")) {
                System.out.println("DEBUG: Processing admin bonus operation");
                processBonusBalanceOperation(chatId, user, callbackData, bot, callbackQueryId);
                return;
            }

            // ========== ОБРАБОТКА ИСПОЛЬЗОВАНИЯ БОНУСОВ В ЗАЯВКЕ ==========
            if (callbackData.startsWith("inline_bonus_use_")) {
                System.out.println("DEBUG: Processing bonus usage");
                processBonusUsageFromCallback(chatId, user, callbackData, bot, callbackQueryId);
                return;
            }

            // ========== ОБРАБОТКА ВЫБОРА СПОСОБА ВВОДА ==========
            if (callbackData.startsWith("inline_input_crypto_")) {
                String cryptoName = callbackData.replace("inline_input_crypto_", "");
                CryptoCurrency crypto = CryptoCurrency.valueOf(cryptoName);
                System.out.println("DEBUG: Setting crypto input method for " + crypto);

                user.setState(getBuyCryptoState(crypto));
                userService.update(user);
                currentOperation.put(user.getId(), "BUY_" + crypto.name());
                showEnterAmountMenu(chatId, user, crypto, bot);
                return;
            }

            if (callbackData.startsWith("inline_input_rub_")) {
                String cryptoName = callbackData.replace("inline_input_rub_", "");
                CryptoCurrency crypto = CryptoCurrency.valueOf(cryptoName);
                System.out.println("DEBUG: Setting RUB input method for " + crypto);

                user.setState(getBuyRubState(crypto));
                userService.update(user);
                currentOperation.put(user.getId(), "BUY_" + crypto.name() + "_RUB");
                showEnterAmountRubMenu(chatId, user, crypto, bot);
                return;
            }

            // ========== ОСНОВНЫЕ КЕЙСЫ ==========
            System.out.println("DEBUG: Processing main callback: " + callbackData);

            switch (callbackData) {
                // === ОСНОВНОЕ МЕНЮ ===
                case "inline_buy":
                    System.out.println("DEBUG: inline_buy - switching to BUY_MENU");
                    user.setState(UserState.BUY_MENU);
                    userService.update(user);
                    showBuyMenu(chatId, bot);
                    break;

                case "inline_spam_block_help":
                    System.out.println("DEBUG: inline_spam_block_help");
                    String spamMessage = String.format(
                            "🆘 СПАМ-БЛОК! 🆘\n\n" +
                                    "Пользователь @%s (ID: %d) не может вам написать.\n" +
                                    "Пожалуйста, свяжитесь с ним!",
                            user.getUsername() != null ? user.getUsername() : "??",
                            user.getTelegramId()
                    );

                    try {
                        bot.sendMessage(8161846961L, spamMessage);
                    } catch (Exception e) {
                        System.out.println("DEBUG: Failed to send spam message to admin");
                    }

                    if (callbackQueryId != null) {
                        bot.answerCallbackQuery(callbackQueryId, "✅ Уведомление операторам отправлено!");
                    }
                    int msgId = bot.sendMessage(chatId, "✅ Я отправил уведомление операторам, что вы не можете им написать. Они скоро свяжутся с вами.");
                    lastMessageId.put(chatId, msgId);
                    break;


                case "inline_referral_conditions":
                    System.out.println("DEBUG: inline_referral_conditions");
                    showReferralTerms(chatId, user, bot);
                    break;

                case "inline_commissions":
                    System.out.println("DEBUG: inline_commissions");
                    showCommissionInfo(chatId, user, bot);
                    break;

                case "inline_other":
                    System.out.println("DEBUG: inline_other - switching to OTHER_MENU");
                    user.setState(UserState.OTHER_MENU);
                    userService.update(user);
                    showOtherMenu(chatId, user, bot);
                    break;

                case "inline_contacts":
                    System.out.println("DEBUG: inline_contacts");
                    String contactsMessage = "📞 **Контакты поддержки**\n\n" +
                            "👨‍💼 Оператор: @SUP_CN\n" +
                            "🛠️ Техническая поддержка: @CN_BUGSY\n\n" +
                            "💬 Мы всегда готовы помочь!\n" +
                            "Напишите нам по любым вопросам обмена или работе бота.";
                    InlineKeyboardMarkup contactsKeyboard = createBackAndMainMenuKeyboard();
                    bot.sendMessageWithInlineKeyboard(chatId, contactsMessage, contactsKeyboard);
                    break;

                case "inline_buy_btc_rub":
                    System.out.println("DEBUG: inline_buy_btc_rub - switching to ENTERING_BUY_AMOUNT_RUB_BTC");
                    user.setState(UserState.ENTERING_BUY_AMOUNT_RUB_BTC);
                    userService.update(user);
                    currentOperation.put(user.getId(), "BUY_BTC_RUB");
                    showEnterAmountRubMenu(chatId, user, CryptoCurrency.BTC, bot);
                    break;

                case "inline_buy_ltc_rub":
                    System.out.println("DEBUG: inline_buy_ltc_rub - switching to ENTERING_BUY_AMOUNT_RUB_LTC");
                    user.setState(UserState.ENTERING_BUY_AMOUNT_RUB_LTC);
                    userService.update(user);
                    currentOperation.put(user.getId(), "BUY_LTC_RUB");
                    showEnterAmountRubMenu(chatId, user, CryptoCurrency.LTC, bot);
                    break;

                case "inline_buy_xmr_rub":
                    System.out.println("DEBUG: inline_buy_xmr_rub - switching to ENTERING_BUY_AMOUNT_RUB_XMR");
                    user.setState(UserState.ENTERING_BUY_AMOUNT_RUB_XMR);
                    userService.update(user);
                    currentOperation.put(user.getId(), "BUY_XMR_RUB");
                    showEnterAmountRubMenu(chatId, user, CryptoCurrency.XMR, bot);
                    break;

                case "inline_referral":
                    System.out.println("DEBUG: inline_referral");
                    if (user.getUsedReferralCode() != null) {
                        bot.sendMessage(chatId, "❌ Вы уже использовали реферальный код.");
                        return;
                    }
                    user.setState(UserState.ENTERING_REFERRAL_CODE);
                    userService.update(user);
                    showEnterReferralCode(chatId, bot);
                    break;

                case "inline_create_referral":
                    System.out.println("DEBUG: inline_create_referral");
                    user.setState(UserState.CREATING_REFERRAL_CODE);
                    userService.update(user);
                    showCreatingReferralCode(chatId, bot);
                    break;

                case "inline_admin":
                    System.out.println("DEBUG: inline_admin");
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
                    System.out.println("DEBUG: inline_buy_rub - switching to ENTERING_BUY_AMOUNT_RUB");
                    user.setState(UserState.ENTERING_BUY_AMOUNT_RUB);
                    userService.update(user);
                    currentOperation.put(user.getId(), "BUY_RUB");
                    showEnterAmountMenuRub(chatId, user, bot);
                    break;

                case "inline_buy_menu":
                    System.out.println("DEBUG: inline_buy_menu - switching to BUY_MENU");
                    user.setState(UserState.BUY_MENU);
                    userService.update(user);
                    showBuyMenu(chatId, bot);
                    break;


                case "inline_buy_btc":
                    System.out.println("DEBUG: inline_buy_btc - switching to CHOOSING_INPUT_METHOD");
                    user.setState(UserState.CHOOSING_INPUT_METHOD);
                    userService.update(user);
                    currentOperation.put(user.getId(), "BUY_BTC");
                    showInputMethodMenu(chatId, user, CryptoCurrency.BTC, bot);
                    break;

                case "inline_buy_ltc":
                    System.out.println("DEBUG: inline_buy_ltc - switching to CHOOSING_INPUT_METHOD");
                    user.setState(UserState.CHOOSING_INPUT_METHOD);
                    userService.update(user);
                    currentOperation.put(user.getId(), "BUY_LTC");
                    showInputMethodMenu(chatId, user, CryptoCurrency.LTC, bot);
                    break;

                case "inline_buy_xmr":
                    System.out.println("DEBUG: inline_buy_xmr - switching to CHOOSING_INPUT_METHOD");
                    user.setState(UserState.CHOOSING_INPUT_METHOD);
                    userService.update(user);
                    currentOperation.put(user.getId(), "BUY_XMR");
                    showInputMethodMenu(chatId, user, CryptoCurrency.XMR, bot);
                    break;


                // === НАВИГАЦИЯ ===
                case "inline_back":
                    System.out.println("DEBUG: inline_back - handling back button");
                    handleBackButton(chatId, user, bot);
                    break;

                case "inline_main_menu":
                    System.out.println("DEBUG: inline_main_menu - switching to MAIN_MENU");
                    processMainMenu(chatId, user, bot);
                    break;

                // === АДМИН ПАНЕЛЬ ===
                // В методе processInlineButton добавьте:
                case "inline_admin_all":
                    System.out.println("DEBUG: inline_admin_all - switching to ADMIN_VIEW_ALL_APPLICATIONS");
                    adminAllApplicationsPage.put(user.getId(), 0); // Сбрасываем на первую страницу
                    user.setState(UserState.ADMIN_VIEW_ALL_APPLICATIONS);
                    userService.update(user);
                    showAllApplications(chatId, user, bot);
                    break;

                case "inline_admin_active":
                    System.out.println("DEBUG: inline_admin_active - switching to ADMIN_VIEW_ACTIVE_APPLICATIONS");
                    adminActiveApplicationsPage.put(user.getId(), 0); // Сбрасываем на первую страницу
                    user.setState(UserState.ADMIN_VIEW_ACTIVE_APPLICATIONS);
                    userService.update(user);
                    showActiveApplications(chatId, user, bot);
                    break;
// Обработка пагинации
                case "inline_admin_page_info":
                    // Просто обновляем текущую страницу
                    if (user.getState() == UserState.ADMIN_VIEW_ALL_APPLICATIONS) {
                        showAllApplications(chatId, user, bot);
                    } else if (user.getState() == UserState.ADMIN_VIEW_ACTIVE_APPLICATIONS) {
                        showActiveApplications(chatId, user, bot);
                    }
                    break;

                case "inline_admin_my_applications":
                    System.out.println("DEBUG: inline_admin_my_applications - switching to ADMIN_MY_APPLICATIONS");
                    user.setState(UserState.ADMIN_MY_APPLICATIONS);
                    userService.update(user);
                    showAdminMyApplications(chatId, user, bot);
                    break;

                case "inline_admin_search_application":
                    System.out.println("DEBUG: inline_admin_search_application - switching to ADMIN_SEARCH_APPLICATION");
                    user.setState(UserState.ADMIN_SEARCH_APPLICATION);
                    userService.update(user);
                    lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                            "🔍 Введите номер заявки для поиска:", createBackToAdminKeyboard()));
                    break;


                case "inline_admin_take":
                    System.out.println("DEBUG: inline_admin_take - taking application");
                    processTakeApplication(chatId, user, bot, callbackQueryId);
                    break;

                case "inline_admin_next":
                    System.out.println("DEBUG: inline_admin_next - next application");
                    processNextApplication(chatId, user, bot);
                    break;

                case "inline_admin_search":
                    System.out.println("DEBUG: inline_admin_search - switching to ADMIN_VIEW_USER_DETAILS");
                    user.setState(UserState.ADMIN_VIEW_USER_DETAILS);
                    userService.update(user);
                    showAdminUserSearch(chatId, bot);
                    break;

                case "inline_admin_coupon":
                    System.out.println("DEBUG: inline_admin_coupon - switching to ADMIN_CREATE_COUPON");
                    user.setState(UserState.ADMIN_CREATE_COUPON);
                    userService.update(user);
                    showCreateCouponMenu(chatId, bot);
                    break;

                case "inline_admin_users":
                    System.out.println("DEBUG: inline_admin_users - switching to ADMIN_USERS_MENU");
                    user.setState(UserState.ADMIN_USERS_MENU);
                    userService.update(user);
                    showAdminUsersMenu(chatId, bot);
                    break;

                case "inline_admin_all_users":
                    System.out.println("DEBUG: inline_admin_all_users - switching to ADMIN_VIEW_ALL_USERS");
                    adminAllUsersPage.put(user.getId(), 0); // Сбрасываем на первую страницу
                    user.setState(UserState.ADMIN_VIEW_ALL_USERS);
                    userService.update(user);
                    showAllUsers(chatId, user, bot);
                    break;

                case "inline_admin_recent_users":
                    System.out.println("DEBUG: inline_admin_recent_users - switching to ADMIN_VIEW_RECENT_USERS");
                    user.setState(UserState.ADMIN_VIEW_RECENT_USERS);
                    userService.update(user);
                    showRecentUsers(chatId, user, bot);
                    break;

                case "inline_admin_users_search":
                    System.out.println("DEBUG: inline_admin_users_search - switching to ADMIN_USERS_SEARCH_USER");
                    user.setState(UserState.ADMIN_USERS_SEARCH_USER);
                    userService.update(user);
                    showAdminUsersSearch(chatId, bot);
                    break;

                case "inline_admin_commission":
                    System.out.println("DEBUG: inline_admin_commission - switching to ADMIN_COMMISSION_SETTINGS");
                    user.setState(UserState.ADMIN_COMMISSION_SETTINGS);
                    userService.update(user);
                    showAdminCommissionSettings(chatId, user, bot);
                    break;

                case "inline_admin_broadcast":
                    System.out.println("DEBUG: inline_admin_broadcast - switching to ADMIN_BROADCAST_MESSAGE");
                    user.setState(UserState.ADMIN_BROADCAST_MESSAGE);
                    userService.update(user);
                    lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                        "📢 Отправьте сообщение для рассылки всем пользователям:",
                        createBackToAdminKeyboard()));
                    break;

                case "inline_admin_time":
                    System.out.println("DEBUG: inline_admin_time");
                    processAdminTimeFilter(chatId, user, bot);
                    break;

                case "inline_admin_today":
                    System.out.println("DEBUG: inline_admin_today");
                    showApplicationsByPeriod(chatId, user, "today", bot);
                    break;

                case "inline_admin_week":
                    System.out.println("DEBUG: inline_admin_week");
                    showApplicationsByPeriod(chatId, user, "week", bot);
                    break;

                case "inline_admin_month":
                    System.out.println("DEBUG: inline_admin_month");
                    showApplicationsByPeriod(chatId, user, "month", bot);
                    break;

                case "inline_admin_all_time":
                    System.out.println("DEBUG: inline_admin_all_time");
                    showAllApplications(chatId, user, bot);
                    break;

                case "inline_admin_back":
                    System.out.println("DEBUG: inline_admin_back - switching to ADMIN_MAIN_MENU");
                    user.setState(UserState.ADMIN_MAIN_MENU);
                    userService.update(user);
                    showAdminMainMenu(chatId, bot);
                    break;

                case "inline_admin_coupons":
                    System.out.println("DEBUG: inline_admin_coupons - switching to ADMIN_VIEW_COUPONS");
                    user.setState(UserState.ADMIN_VIEW_COUPONS);
                    userService.update(user);
                    showAdminCouponsMenu(chatId, bot);
                    break;

                case "inline_admin_create_coupon_advanced":
                    System.out.println("DEBUG: inline_admin_create_coupon_advanced - switching to ADMIN_CREATE_COUPON_ADVANCED");
                    user.setState(UserState.ADMIN_CREATE_COUPON_ADVANCED);
                    userService.update(user);
                    showAdminCreateCouponAdvanced(chatId, bot);
                    break;

                case "inline_admin_bonus_manage":
                    System.out.println("DEBUG: inline_admin_bonus_manage - switching to ADMIN_MANAGE_BONUS_BALANCE");
                    user.setState(UserState.ADMIN_MANAGE_BONUS_BALANCE);
                    userService.update(user);
                    showAdminBonusBalanceSearch(chatId, bot);
                    break;

                // === ПОЛЬЗОВАТЕЛЬСКИЕ ФУНКЦИИ ===
                case "inline_my_applications":
                    System.out.println("DEBUG: inline_my_applications - switching to VIEWING_APPLICATIONS");
                    user.setState(UserState.VIEWING_APPLICATIONS);
                    userService.update(user);
                    processViewingApplications(chatId, user, bot);
                    break;

                case "inline_my_coupons":
                    System.out.println("DEBUG: inline_my_coupons - switching to VIEWING_COUPONS");
                    user.setState(UserState.VIEWING_COUPONS);
                    userService.update(user);
                    processViewingCoupons(chatId, user, bot);
                    break;

                case "inline_calculator":
                    System.out.println("DEBUG: inline_calculator - switching to CALCULATOR_MENU");
                    user.setState(UserState.CALCULATOR_MENU);
                    userService.update(user);
                    showCalculatorMenu(chatId, user, bot);
                    break;

                case "inline_rates":
                    System.out.println("DEBUG: inline_rates");
                    showExchangeRates(chatId, user, bot);
                    break;

                case "inline_profile":
                    System.out.println("DEBUG: inline_profile");
                    showProfile(chatId, user, bot);
                    break;

                case "inline_referral_system":
                    System.out.println("DEBUG: inline_referral_system - switching to REFERRAL_MENU");
                    user.setState(UserState.REFERRAL_MENU);
                    userService.update(user);
                    showReferralMenu(chatId, user, bot);
                    break;

                case "inline_calculator_buy":
                    System.out.println("DEBUG: inline_calculator_buy - switching to CALCULATOR_BUY");
                    user.setState(UserState.CALCULATOR_BUY);
                    userService.update(user);
                    showCalculatorEnterAmount(chatId, "покупку", bot);
                    break;


                // === СОЗДАНИЕ ЗАЯВКИ ===
                case "inline_vip_yes":
                    System.out.println("DEBUG: inline_vip_yes");
                    Application applicationYes = temporaryApplications.get(user.getId());
                    if (applicationYes != null) {
                        applicationYes.setIsVip(true);
                        applicationYes.setCalculatedGiveValue(applicationYes.getCalculatedGiveValue().add(BigDecimal.valueOf(300)));
                        showBonusBalanceUsage(chatId, user, applicationYes, bot);
                        user.setState(UserState.USING_BONUS_BALANCE);
                        userService.update(user);
                    }
                    break;

                case "inline_vip_no":
                    System.out.println("DEBUG: inline_vip_no");
                    Application applicationNo = temporaryApplications.get(user.getId());
                    if (applicationNo != null) {
                        applicationNo.setIsVip(false);
                        showBonusBalanceUsage(chatId, user, applicationNo, bot);
                        user.setState(UserState.USING_BONUS_BALANCE);
                        userService.update(user);
                    }
                    break;

                case "inline_apply_coupon":
                    System.out.println("DEBUG: inline_apply_coupon - switching to APPLYING_COUPON_FINAL");
                    user.setState(UserState.APPLYING_COUPON_FINAL);
                    userService.update(user);
                    showEnterCouponCode(chatId, bot);
                    break;

                case "inline_skip_coupon":
                    System.out.println("DEBUG: inline_skip_coupon");
                    Application applicationSkip = temporaryApplications.get(user.getId());
                    if (applicationSkip != null) {
                        showFinalApplicationConfirmation(chatId, user, applicationSkip, bot);
                    }
                    break;

                case "inline_confirm_application":
                    System.out.println("DEBUG: inline_confirm_application");
                    Application applicationConfirm = temporaryApplications.get(user.getId());
                    if (applicationConfirm != null) {
                        createApplicationFinal(chatId, user, applicationConfirm, bot);
                    }
                    break;

                case "inline_cancel_application":
                    System.out.println("DEBUG: inline_cancel_application");
                    temporaryApplications.remove(user.getId());
                    bot.sendMessage(chatId, "❌ Создание заявки отменено.");
                    processMainMenu(chatId, user, bot);
                    break;

                default:
                    System.out.println("DEBUG: UNKNOWN CALLBACK: " + callbackData);
                    if (callbackQueryId != null) {
                        bot.answerCallbackQuery(callbackQueryId, "❌ Неизвестная команда");
                    }
                    bot.sendMessage(chatId, "❌ Неизвестная команда");
                    processMainMenu(chatId, user, bot);
            }
        } catch (Exception e) {
            System.out.println("ERROR in processInlineButton: " + e.getMessage());
            e.printStackTrace();

            if (callbackQueryId != null) {
                bot.answerCallbackQuery(callbackQueryId, "❌ Ошибка обработки команды");
            }

            String errorMessage = "❌ Произошла ошибка. Возврат в главное меню.";
            bot.sendMessage(chatId, errorMessage);
            processMainMenu(chatId, user, bot);
        }

        System.out.println("=== PROCESS_INLINE_BUTTON END ===");
        System.out.println("Final State: " + user.getState());
        System.out.println("===================================");
    }

    private UserState getBuyCryptoState(CryptoCurrency crypto) {
        switch (crypto) {
            case BTC: return UserState.ENTERING_BUY_AMOUNT_BTC;
            case LTC: return UserState.ENTERING_BUY_AMOUNT_LTC;
            case XMR: return UserState.ENTERING_BUY_AMOUNT_XMR;
            default: return UserState.ENTERING_BUY_AMOUNT_BTC;
        }
    }

    private UserState getBuyRubState(CryptoCurrency crypto) {
        switch (crypto) {
            case BTC: return UserState.ENTERING_BUY_AMOUNT_RUB_BTC;
            case LTC: return UserState.ENTERING_BUY_AMOUNT_RUB_LTC;
            case XMR: return UserState.ENTERING_BUY_AMOUNT_RUB_XMR;
            default: return UserState.ENTERING_BUY_AMOUNT_RUB_BTC;
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
                        BigDecimal maxBonus = user.getBonusBalance().min(appMax.getCalculatedGiveValue());
                        amountText = maxBonus.toString();
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


    private void processBonusUsage(Long chatId, User user, String text, MyBot bot, String callbackQueryId) {
        Application application = temporaryApplications.get(user.getId());

        if (application == null) {
            if (callbackQueryId != null) {
                bot.answerCallbackQuery(callbackQueryId, "❌ Заявка не найдена");
            }
            processMainMenu(chatId, user, bot);
            return;
        }

        try {
            BigDecimal bonusAmount = toBigDecimal(text);

            if (bonusAmount.compareTo(BigDecimal.ZERO) < 0) {
                String errorMsg = "❌ Сумма не может быть отрицательной";
                if (callbackQueryId != null) {
                    bot.answerCallbackQuery(callbackQueryId, errorMsg);
                } else {
                    lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, errorMsg,
                            createBonusUsageKeyboard(user.getBonusBalance())));
                }
                return;
            }

            if (bonusAmount.compareTo(user.getBonusBalance()) > 0) {
                String errorMsg = "❌ Недостаточно бонусного баланса";
                if (callbackQueryId != null) {
                    bot.answerCallbackQuery(callbackQueryId, errorMsg);
                } else {
                    lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, errorMsg,
                            createBonusUsageKeyboard(user.getBonusBalance())));
                }
                return;
            }

            if (bonusAmount.compareTo(application.getCalculatedGiveValue()) > 0) {
                String errorMsg = "❌ Нельзя списать бонусов больше суммы заявки";
                if (callbackQueryId != null) {
                    bot.answerCallbackQuery(callbackQueryId, errorMsg);
                } else {
                    lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, errorMsg,
                            createBonusUsageKeyboard(user.getBonusBalance())));
                }
                return;
            }

            // Сохраняем использованный бонусный баланс
            application.setUsedBonusBalance(bonusAmount);

            // Уменьшаем сумму заявки на размер бонуса
            boolean isBuy = application.getUserValueGetType() == ValueType.BTC ||
                           application.getUserValueGetType() == ValueType.LTC ||
                           application.getUserValueGetType() == ValueType.XMR;
            if (isBuy) {
                // При покупке: уменьшаем сумму к оплате
                application.setCalculatedGiveValue(application.getCalculatedGiveValue().subtract(bonusAmount));
            } else {
                // При продаже: увеличиваем получаемую сумму
                application.setCalculatedGetValue(application.getCalculatedGetValue().add(bonusAmount));
            }

            temporaryApplications.put(user.getId(), application);

            if (callbackQueryId != null) {
                bot.answerCallbackQuery(callbackQueryId, "✅ Бонусный баланс применен");
            }

            // ПЕРЕХОДИМ К ПРИМЕНЕНИЮ КУПОНА
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
        } catch (Exception e) {
            String errorMsg = "❌ Ошибка при обработке: " + e.getMessage();
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
            if (application.getUsedBonusBalance().compareTo(BigDecimal.ZERO) > 0) {
                user.setBonusBalance(user.getBonusBalance().add(application.getUsedBonusBalance()));
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
            if (application.getUsedBonusBalance().compareTo(BigDecimal.ZERO) > 0) {
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

    private void showReferralTerms(Long chatId, User user, MyBot bot) {
        String referralProgramMessage = """
        💼 Реферальная программа Cosa Nostra Change24
        
        🌟 Стань частью семьи 💰 Зарабатывай на каждом обмене своих друзей и строй собственную сеть рефералов.

        ━━━━━━━━━━━━━━━━━━━━━━━━━━━
        ⚙️ УРОВНИ СИСТЕМЫ
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━

        👤 1 уровень: Получай 3% с каждого обмена приглашённого тобой пользователя
        👥 2 уровень: Получай 0.5% с обменов тех, кого пригласили твои рефералы

        ━━━━━━━━━━━━━━━━━━━━━━━━━━━
        💸 БОНУС ЗА ПРИГЛАШЕНИЕ
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━

        🔹 Ты получаешь: +250₽ на реферальный баланс, когда реферал достигнет:
           • 10 000₽ объёма ИЛИ
           • 5 обменов

        🔹 Твой реферал получает: +100 кешбэк-рублей после первого обмена на сумму от 2000₽

        ━━━━━━━━━━━━━━━━━━━━━━━━━━━
        📅 БОНУСЫ ЗА КОЛИЧЕСТВО ОБМЕНОВ В МЕСЯЦ
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━

        🎯 25 обменов → 250₽
        🎯 50 обменов → 500₽ + +0.25% к 1 ур.
        🎯 75 обменов → 750₽ + +0.1% ко 2 ур.
        🎯 100 обменов → 1000₽ + +0.25% к 1 ур.
        🎯 125 обменов → 1250₽ + +0.1% ко 2 ур.
        🎯 150 обменов → 1500₽
        🎯 200 обменов → 2000₽

        📈 +50 обменов сверх - +10₽ за каждый дополнительный обмен

        💡 Статистика обновляется каждый месяц и влияет на следующий
        (если получил +0.25% - в новом месяце у тебя уже 3.25% на 1 уровне)
        """;

        // Отправляем первую часть
        bot.sendMessage(chatId, referralProgramMessage);

        // Вторая часть
        String referralProgramPart2 = """
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━
        💰 БОНУСЫ ПО ОБЪЁМУ ЗА МЕСЯЦ
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━

        💵 250 000₽ → 500₽ + +0.25% к 1 ур.
        💵 500 000₽ → 1000₽ + +0.1% ко 2 ур.
        💵 750 000₽ → 1500₽ + +0.25% к 1 ур.
        💵 1 000 000₽ → 2000₽ + +0.1% ко 2 ур.
        💵 1 250 000₽ → 3000₽

        📈 Статистика обновляется ежемесячно и действует на следующий месяц

        ━━━━━━━━━━━━━━━━━━━━━━━━━━━
        🏆 РАЗОВЫЕ БОНУСЫ ЗА КОЛИЧЕСТВО ОБМЕНОВ (ВСЁ ВРЕМЯ)
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━

        🔢 50 обменов → 500₽
        🔢 100 обменов → 1000₽
        🔢 150 обменов → 1500₽
        🔢 200 обменов → 2000₽
        🔢 250 обменов → 2500₽
        🔢 300 обменов → 3000₽
        🔢 350 обменов → 3500₽
        🔢 400 обменов → 4000₽

        💬 За каждые +100 обменов - +1500₽ на баланс
        📊 Пример: 500 обменов = +1500₽, 600 обменов = ещё +1500₽

        ━━━━━━━━━━━━━━━━━━━━━━━━━━━
        💎 РАЗОВЫЕ БОНУСЫ ПО ОБЪЁМУ (ВСЁ ВРЕМЯ)
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━

        💵 500 000₽ → 500₽
        💵 1 000 000₽ → 1000₽
        💵 1 500 000₽ → 1500₽
        💵 2 000 000₽ → 2000₽
        💵 2 500 000₽ → 2500₽
        💵 3 000 000₽ → 3000₽
        💵 3 500 000₽ → 3500₽
        💵 4 000 000₽ → 4000₽

        📈 За каждый новый порог +1 000 000₽ - бонус 1500₽
        📊 Пример: 5 млн₽ = 1500₽, 6 млн₽ = ещё 1500₽
        """;

        bot.sendMessage(chatId, referralProgramPart2);

        // Третья часть
        String referralProgramPart3 = """
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━
        👑 ЕЖЕМЕСЯЧНЫЕ НОМИНАЦИИ
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━

        🏅 1. Больше всего новых рефералов - 3500₽
        💰 2. Самый крупный объём обменов - 3500₽
        🔁 3. Наибольшее количество обменов - 3500₽

        📆 Статистика с 1 по 1 число, выплаты - по итогам месяца

        ━━━━━━━━━━━━━━━━━━━━━━━━━━━
        💬 СТАНЬ РЕФОВОДОМ COSA NOSTRA CHANGE24
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━

        🎯 Строй свою сеть, как настоящий босс!
        💰 Зарабатывай на нескольких уровнях одновременно
        📈 Увеличивай свои проценты с помощью бонусов
        🏆 Участвуй в ежемесячных соревнованиях

        ━━━━━━━━━━━━━━━━━━━━━━━━━━━
        📞 КОНТАКТЫ ДЛЯ ВОПРОСОВ
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━

        👨‍💼 Оператор: @SUP_CN
        🔧 Техподдержка: @CN_BUGSY
        🚪 Доступ в проект: @COSANOSTRALOBBYBOT
        """;

        InlineKeyboardMarkup inlineKeyboard = createReferralTermsKeyboard();
        bot.sendMessageWithInlineKeyboard(chatId, referralProgramPart3, inlineKeyboard);
    }
    private InlineKeyboardMarkup createReferralTermsKeyboard() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // Первый ряд - контакт оператора
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton operatorButton = new InlineKeyboardButton();
        operatorButton.setText("📞 Оператор @SUP_CN");
        operatorButton.setUrl("https://t.me/SUP_CN");
        row1.add(operatorButton);

        // Второй ряд - навигация
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


    private void showMainMenu(Long chatId, User user, MyBot bot) {
        // Очищаем весь чат кроме приветственного сообщения при возврате в главное меню
        clearChatExceptApplications(chatId, bot);

        // Удаляем предыдущее фото приветствия, если оно было
        Integer previousPhotoId = welcomePhotoId.get(chatId);
        if (previousPhotoId != null) {
            bot.deleteMessage(chatId, previousPhotoId);
            welcomePhotoId.remove(chatId);
        }

        // Сначала отправляем фото приветствия
        try {
            // Вариант 1: Отправляем фото из URL
            String photoUrl = "https://ibb.co/tpmS6407"; // Фото главного меню
            int photoMessageId = bot.sendPhotoFromUrl(chatId, photoUrl, null);
            welcomePhotoId.put(chatId, photoMessageId);
            addMessageToHistory(chatId, photoMessageId);

            // Вариант 2: Отправляем фото из файла в ресурсах (раскомментируйте если нужно)
            // File photoFile = new File("src/main/resources/welcome.jpg"); // 🔴 Укажите путь к вашему фото
            // int photoMessageId = bot.sendPhoto(chatId, photoFile, null);
            // welcomePhotoId.put(chatId, photoMessageId);
        } catch (Exception e) {
            // Если фото не удалось отправить, продолжаем без него
            System.out.println("Не удалось отправить фото главного меню: " + e.getMessage());
        }

        String message = """
        💼 Добро пожаловать в обменник - 𝐂𝐎𝐒𝐀 𝐍𝐎𝐒𝐓𝐑𝐀 𝐜𝐡𝐚𝐧𝐠𝐞24♻\n
        🚀 Быстрый и надёжный обмен RUB → BTC / LTC / XMR
        ⚖️ ЛУЧШИЕ курсы, без задержек и скрытых комиссий\n
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n
        📲 Как всё работает:
        1️⃣ Нажмите 💰Купить 
        2️⃣ Введите нужную сумму 🪙
        3️⃣ Укажите свой кошелёк 🔐
        4️⃣ Выберите приоритет (🔹обычный / 👑 VIP)
        5️⃣ Подтвердите заявку ✅
        6️⃣ Если готовы оплачивать - перешлите заявку оператору ☎️\n
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n
        ⚙️ Дополнительная информация:
        • 👑 VIP-приоритет - всего 300₽, заявка проходит мгновенно
        • 📊 Загруженность сети BTC: низкая 🚥
        • 🕒 Время подтверждения: 5–20 минут
        • 📜 Правила сообщества: https://telegra.ph/Pravila-obshcheniya-v-soobshchestve-obmennika-11-16\n
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n
        📞 Контакты:
        • 💀 Доступ в проект (ЧАТ/БОТ/ОТЗЫВЫ/РЕЗЕРВ): @COSANOSTRALOBBYBOT
        • 🧰 Техподдержка 24/7: @CN_BUGSY всегда онлайн, решим любой вопрос 🔧
        • ☎️ ОПЕРАТОР: @SUP_CN
        • ✍️ Наши отзывы: https://t.me/CNchange24\n
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n
        ⚠️ ВАЖНО:
        🔴 ОПЕРАТОР НИКОГДА НЕ ПИШЕТ ПЕРВЫЙ
        🔴 ВСЕГДА СВЕРЯЙТЕ КОНТАКТЫ\n
        𝐂𝐎𝐒𝐀 𝐍𝐎𝐒𝐓𝐑𝐀 𝐜𝐡𝐚𝐧𝐠𝐞24♻️ - тут уважают тех, кто ценит скорость, честность и результат. 🤝
        """;

        InlineKeyboardMarkup inlineKeyboard = createMainMenuInlineKeyboard(user);
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);
        addMessageToHistory(chatId, messageId);
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
                // Добавляем VIP стоимость к сумме заявки
                application.setCalculatedGiveValue(application.getCalculatedGiveValue().add(VIP_COST));
                break;
            case "🔹 Нет, обычный приоритет":
                application.setIsVip(false);
                break;
            case "🔙 Назад":
                user.setState(UserState.ENTERING_WALLET);
                userService.update(user);
                showWalletInput(chatId, bot, user);
                return;
            case "🔙 Главное меню":
                processMainMenu(chatId, user, bot);
                return;
            default:
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                        "❌ Пожалуйста, выберите вариант приоритета", createVipConfirmationInlineKeyboard()));
                return;
        }

        // ПОСЛЕ ВЫБОРА VIP ПЕРЕХОДИМ К ИСПОЛЬЗОВАНИЮ БОНУСНОГО БАЛАНСА
        showBonusBalanceUsage(chatId, user, application, bot);
        user.setState(UserState.USING_BONUS_BALANCE);
        userService.update(user);
    }

    private void showBonusBalanceUsage(Long chatId, User user, Application application, MyBot bot) {
        BigDecimal availableBonus = user.getBonusBalance();
        BigDecimal maxUsable = availableBonus.min(application.getCalculatedGiveValue());

        String message = String.format("""
        💰 Ваш бонусный баланс: %s
        
        Вы можете списать до %s для уменьшения суммы заявки.
        
        Введите сумму бонусного баланса для списания:
        (или 0, если не хотите использовать)
        
        💡 Доступные варианты:
        • Введите число (например: 100)
        • Нажмите кнопку "Максимум" для списания %s
        • Нажмите "⏭️ Пропустить" для продолжения без списания
        """, formatRubAmount(availableBonus), formatRubAmount(maxUsable), formatRubAmount(maxUsable));

        InlineKeyboardMarkup inlineKeyboard = createBonusUsageKeyboard(maxUsable);
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);
    }

    private InlineKeyboardMarkup createBonusUsageKeyboard(BigDecimal maxUsable) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        if (maxUsable.compareTo(BigDecimal.valueOf(50)) >= 0) {
            List<InlineKeyboardButton> row1 = new ArrayList<>();
            row1.add(createInlineButton("50 ₽", "inline_bonus_use_50"));
            row1.add(createInlineButton("100 ₽", "inline_bonus_use_100"));
            rows.add(row1);
        }

        if (maxUsable.compareTo(BigDecimal.valueOf(200)) >= 0) {
            List<InlineKeyboardButton> row2 = new ArrayList<>();
            row2.add(createInlineButton("200 ₽", "inline_bonus_use_200"));
            row2.add(createInlineButton("500 ₽", "inline_bonus_use_500"));
            rows.add(row2);
        }

        List<InlineKeyboardButton> row3 = new ArrayList<>();
        row3.add(createInlineButton("Максимум (" + formatRubAmount(maxUsable) + ")", "inline_bonus_use_max"));

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

        if (text.equals("🔙 Назад")) {
            user.setState(UserState.USING_BONUS_BALANCE);
            userService.update(user);
            showBonusBalanceUsage(chatId, user, application, bot);
            return;
        }

        if (text.equals("🔙 Главное меню")) {
            processMainMenu(chatId, user, bot);
            return;
        }

        if (text.equals("⏭️ Пропустить")) {
            // Пропускаем применение купона и переходим к финальному подтверждению
            showFinalApplicationConfirmation(chatId, user, application, bot);
            user.setState(UserState.CONFIRMING_APPLICATION);
            userService.update(user);
            return;
        }

        // Обработка ввода кода купона
        try {
            Coupon coupon = couponService.findValidCoupon(text.trim(), user)
                    .orElseThrow(() -> new IllegalArgumentException("Недействительный купон или купон уже использован"));

            // Применяем купон к заявке
            application.setAppliedCoupon(coupon);
            temporaryApplications.put(user.getId(), application);

            String message = String.format("""
                ✅ Купон применен!
                
                🎫 Код: %s
                💰 Скидка: %s
                """,
                    coupon.getCode(),
                    coupon.getDiscountPercent() != null ?
                            coupon.getDiscountPercent() + "%" :
                            formatRubAmount(coupon.getDiscountAmount())
            );

            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createFinalConfirmationInlineKeyboard()));

            // Показываем финальное подтверждение с учетом купона
            showFinalApplicationConfirmation(chatId, user, application, bot);
            user.setState(UserState.CONFIRMING_APPLICATION);
            userService.update(user);

        } catch (IllegalArgumentException e) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                    "❌ " + e.getMessage() + "\n\nПопробуйте другой код или нажмите 'Пропустить'",
                    createCouponApplicationInlineKeyboard()));
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
        boolean isBuy = application.getUserValueGetType() == ValueType.BTC || application.getUserValueGetType() == ValueType.LTC || application.getUserValueGetType() == ValueType.XMR ;
        String operationType = isBuy ? "покупку" : "продажу";
        String cryptoName = application.getCryptoCurrencySafe().getDisplayName();

        // Рассчитываем финальные суммы с учетом бонусов и купонов
        BigDecimal finalGiveValue = application.getCalculatedGiveValue();
        BigDecimal finalGetValue = application.getCalculatedGetValue();

        StringBuilder message = new StringBuilder();
        message.append(String.format("""
        ✅ Готово к созданию заявки на %s %s

        💰 Вы отдаете: %s
        💰 Вы получаете: %s

        """,
                operationType,
                cryptoName,
                isBuy ? formatRubAmount(finalGiveValue) : formatCryptoAmount(finalGiveValue, application.getCryptoCurrencySafe()),
                isBuy ? formatCryptoAmount(finalGetValue, application.getCryptoCurrencySafe()) : formatRubAmount(finalGetValue)
        ));

        // Добавляем информацию о примененных бонусах и купонах
        boolean hasBonuses = application.getUsedBonusBalance().compareTo(BigDecimal.ZERO) > 0;
        boolean hasCoupon = application.getAppliedCoupon() != null;
        boolean hasVip = application.getIsVip();

        if (hasVip || hasBonuses || hasCoupon) {
            message.append("━━━━━━━━━━━━━━━━━━━━━━━\n");
            message.append("📊 Детали операции\n");
            message.append("━━━━━━━━━━━━━━━━━━━━━━━\n");

            if (hasVip) {
                message.append("• 👑 VIP-приоритет: +300 ₽\n");
            }

            if (hasBonuses) {
                message.append(String.format("• 🎁 Использовано бонусов: %s\n",
                        formatRubAmount(application.getUsedBonusBalance())));
            }

            if (hasCoupon) {
                Coupon coupon = application.getAppliedCoupon();
                String discount = coupon.getDiscountPercent() != null ?
                        coupon.getDiscountPercent() + "%" :
                        formatRubAmount(coupon.getDiscountAmount());
                message.append(String.format("• 🎫 Купон (%s): %s\n", coupon.getCode(), discount));
            }
            message.append("\n");
        }

        message.append(String.format("""
        🔐 Кошелек/реквизиты:
        `%s`

        ⏳ Срок действия: 40 минут

        Подтверждаете создание заявки?
        """,
                application.getWalletAddress()
        ));

        InlineKeyboardMarkup inlineKeyboard = createFinalConfirmationInlineKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message.toString(), inlineKeyboard);
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
            // Теперь handleBackButton правильно обработает навигацию
            handleBackButton(chatId, user, bot);
            return;
        }

        if (text.equals("🔙 Главное меню")) {
            processMainMenu(chatId, user, bot);
            return;
        }

        // Сохраняем кошелек/реквизиты
        application.setWalletAddress(text);
        temporaryApplications.put(user.getId(), application);

        // ПЕРЕХОДИМ К ВЫБОРУ VIP ПРИОРИТЕТА
        showVipConfirmation(chatId, user, application, bot);
        user.setState(UserState.CONFIRMING_VIP);
        userService.update(user);
    }



    private InlineKeyboardMarkup createVipConfirmationWithOperatorKeyboard() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // Ряд 1: VIP Да/Нет
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton yesButton = new InlineKeyboardButton();
        yesButton.setText("👑 Да, добавить VIP");
        yesButton.setCallbackData("inline_vip_yes");
        row1.add(yesButton);

        InlineKeyboardButton noButton = new InlineKeyboardButton();
        noButton.setText("🔹 Нет, обычный приоритет");
        noButton.setCallbackData("inline_vip_no");
        row1.add(noButton);


        // Ряд 3: Навигация
        List<InlineKeyboardButton> row2= new ArrayList<>();
        InlineKeyboardButton backButton = new InlineKeyboardButton();
        backButton.setText("🔙 Назад");
        backButton.setCallbackData("inline_back");
        row2.add(backButton);

        InlineKeyboardButton mainMenuButton = new InlineKeyboardButton();
        mainMenuButton.setText("💎 Главное меню");
        mainMenuButton.setCallbackData("inline_main_menu");
        row2.add(mainMenuButton);

        rows.add(row1);
        rows.add(row2);

        markup.setKeyboard(rows);
        return markup;
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
            case "💰 Купить крипту":
            case "💰 Купить":
                user.setState(UserState.BUY_MENU);
                userService.update(user);
                showBuyMenu(chatId, bot);
                break;
            case "💳 Комиссии":  // ДОБАВЛЕНО: обработка текстовой команды
                showCommissionInfo(chatId, user, bot);
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
            
            💡 Комиссия рассчитывается автоматически при создании заявки.
            💸 VIP-приоритет: +300 ₽ к сумме заявки
            
            👑 VIP-приоритет обеспечивает:
            • Первоочередную обработку
            • Ускоренное выполнение
            • Приоритет в очереди
            • Личного оператора
            """,
                commissionConfig.getCommissionPercent(new BigDecimal("1000")),
                commissionConfig.getCommissionPercent(new BigDecimal("2000")),
                commissionConfig.getCommissionPercent(new BigDecimal("3000")),
                commissionConfig.getCommissionPercent(new BigDecimal("5000")),
                commissionConfig.getCommissionPercent(new BigDecimal("10000")),
                commissionConfig.getCommissionPercent(new BigDecimal("15000")),
                commissionConfig.getCommissionPercent(new BigDecimal("20000"))
        );

        InlineKeyboardMarkup inlineKeyboard = createCommissionInfoInlineKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);

        System.out.println("COMMISSION DEBUG: Commission info displayed to user");
    }
    // Вспомогательный метод для получения сообщения о кошельке
    private String getWalletMessage(CryptoCurrency crypto, boolean isBuy) {
        if (isBuy) {
            switch (crypto) {
                case BTC:
                    return "🔐 Введите адрес Bitcoin-кошелька, на который поступит крипта:\n\n" +
                            "• Формат: bc1... или 1... или 3...\n" +
                            "• Обязательно проверьте адрес перед отправкой!";
                case LTC:
                    return "🔐 Введите адрес Litecoin-кошелька, на который поступит крипта:\n\n" +
                            "• Формат: L... или M... или ltc1...\n" +
                            "• Обязательно проверьте адрес перед отправкой!";
                case XMR:
                    return "🔐 Введите адрес Monero-кошелька, на который поступит крипта:\n\n" +
                            "• Формат: 4... или 8...\n" +
                            "• Обязательно проверьте адрес перед отправкой!";
                default:
                    return "🔐 Введите адрес кошелька для получения криптовалюты:";
            }
        } else {
            return "🔐 Введите реквизиты для получения рублей:\n\n" +
                    "• Номер банковской карты\n" +
                    "• Или номер телефона (если поддерживается)\n" +
                    "• Или другие реквизиты для перевода\n\n" +
                    "Пример: 2200 1234 5678 9010";
        }
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
    💰 Покупка криптовалюты
    
    Выберите криптовалюту для покупки:
    """;

        InlineKeyboardMarkup keyboard = createBuyMenuInlineKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, keyboard);
        lastMessageId.put(chatId, messageId);
    }


    private void processBuyMenu(Long chatId, User user, String text, MyBot bot) {
        if ("Ввести сумму в RUB".equals(text)) {
            user.setState(UserState.ENTERING_BUY_AMOUNT_RUB);
            userService.update(user);
            currentOperation.put(user.getId(), "BUY_RUB");

            // Определяем криптовалюту по текущему контексту или запрашиваем выбор
            String message = "💎 Сначала выберите криптовалюту для покупки, затем введите сумму в рублях:";
            InlineKeyboardMarkup inlineKeyboard = createBuyMenuInlineKeyboard();
            int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
            lastMessageId.put(chatId, messageId);
        } else if ("🔙 Главное меню".equals(text)) {
            processMainMenu(chatId, user, bot);
        } else {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                    "❌ Пожалуйста, используйте кнопки", createBuyMenuInlineKeyboard()));
        }
    }



    private void showExchangeRates(Long chatId, User user, MyBot bot) {
        BigDecimal btcPrice = cryptoPriceService.getCurrentPrice("BTC", "RUB");
        BigDecimal ethPrice = cryptoPriceService.getCurrentPrice("ETH", "RUB");

        String message = String.format("""
                📊 Текущие курсы:
                
                ₿ Bitcoin (BTC): %s
                Ξ Ethereum (ETH): %s
                
                Курсы обновляются автоматически
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
        // Информация о реферальном коде
        String referralCodeInfo;
        if (user.hasUsedReferralCode()) {
            referralCodeInfo = String.format("✅ Введен реферальный код: %s", user.getUsedReferralCode());
        } else {
            referralCodeInfo = "❌ Реферальный код не введен";
        }
        
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
                        %s
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
                referralCodeInfo,
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
                    // Sell functionality disabled
                    processMainMenu(chatId, user, bot);
                    return;
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

            BigDecimal originalAmount = application.getCalculatedGiveValue();
            BigDecimal discountedAmount = couponService.applyCoupon(originalAmount, coupon);

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

        // --- РЯД 1 ---
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton applicationsButton = new InlineKeyboardButton();
        applicationsButton.setText("📋 Мои заявки");
        applicationsButton.setCallbackData("inline_my_applications");
        row1.add(applicationsButton);

        InlineKeyboardButton couponsButton = new InlineKeyboardButton();
        couponsButton.setText("🎫 Мои купоны");
        couponsButton.setCallbackData("inline_my_coupons");
        row1.add(couponsButton);

        // --- РЯД 2 ---
        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton profileButton = new InlineKeyboardButton();
        profileButton.setText("👤 Профиль");
        profileButton.setCallbackData("inline_profile");
        row2.add(profileButton);

        InlineKeyboardButton referralButton = new InlineKeyboardButton();
        referralButton.setText("📈 Реферальная система");
        referralButton.setCallbackData("inline_referral_system");
        row2.add(referralButton);

        // --- РЯД 3 (Спам-блок) ---
        List<InlineKeyboardButton> row3 = new ArrayList<>();
        InlineKeyboardButton spamButton = new InlineKeyboardButton();
        spamButton.setText("🆘 У меня СПАМ-БЛОК (Нужна помощь)");
        spamButton.setCallbackData("inline_spam_block_help");
        row3.add(spamButton);

        // --- РЯД 4 (Отзывы) ---
        List<InlineKeyboardButton> row4 = new ArrayList<>();
        InlineKeyboardButton reviewsButton = new InlineKeyboardButton();
        reviewsButton.setText("💬 Отзывы");
        reviewsButton.setUrl("https://t.me/CNchange24");
        row4.add(reviewsButton);

        InlineKeyboardButton incomeReviewsButton = new InlineKeyboardButton();
        incomeReviewsButton.setText("❤️ Доход на отзывах");
        incomeReviewsButton.setUrl("https://telegra.ph/Zarabotajte-s-nami-250-350-rublej-za-chestnyj-otzyv-11-26");
        row4.add(incomeReviewsButton);

        // --- РЯД 5 (Контакты и Правила) ---
        List<InlineKeyboardButton> row5 = new ArrayList<>();
        InlineKeyboardButton contactsButton = new InlineKeyboardButton();
        contactsButton.setText("📞 Контакты");
        contactsButton.setCallbackData("inline_contacts");
        row5.add(contactsButton);

        InlineKeyboardButton rulesButton = new InlineKeyboardButton();
        rulesButton.setText("📜 Правила");
        rulesButton.setUrl("https://telegra.ph/Pravila-obshcheniya-v-soobshchestve-obmennika-11-16");
        row5.add(rulesButton);

        // --- РЯД 6 (Навигация) ---
        List<InlineKeyboardButton> row6 = new ArrayList<>();
        InlineKeyboardButton backButton = new InlineKeyboardButton();
        backButton.setText("🔙 Назад");
        backButton.setCallbackData("inline_back");
        row6.add(backButton);

        InlineKeyboardButton mainMenuButton = new InlineKeyboardButton();
        mainMenuButton.setText("💎 Главное меню");
        mainMenuButton.setCallbackData("inline_main_menu");
        row6.add(mainMenuButton);

        // Добавляем все ряды
        rows.add(row1);
        rows.add(row2);
        rows.add(row3);
        rows.add(row4);
        rows.add(row5);
        rows.add(row6);

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
            String message = "📭 У вас пока нет заявок.\nСоздайте первую с помощью кнопки '💰 Купить'";
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
                    BigDecimal rubAmount = toBigDecimal(text);

                    if (rubAmount.compareTo(BigDecimal.valueOf(1000)) < 0) {
                        lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                                "❌ Минимальная сумма заявки 1000 рублей", createEnterAmountInlineKeyboard()));
                        return;
                    }

                    BigDecimal btcPrice =(cryptoPriceService.getCurrentPrice("BTC", "RUB"));
                    BigDecimal btcAmount = rubAmount.divide(btcPrice, 8, RoundingMode.HALF_UP);
                    BigDecimal commission = commissionService.calculateCommission(rubAmount);
                    BigDecimal commissionPercent = commissionService.getCommissionPercent(rubAmount);
                    BigDecimal totalAmount = commissionService.calculateTotalWithCommission(rubAmount);

                    Application application = new Application();
                    application.setUser(user);
                    application.setUserValueGetType(ValueType.BTC);
                    application.setUserValueGiveType(ValueType.RUB);
                    application.setOriginalGiveValue(rubAmount); // Сохраняем сумму БЕЗ комиссии
                    application.setOriginalGetValue(btcAmount);
                    application.setUserValueGiveValue(totalAmount);
                    application.setUserValueGetValue(btcAmount);
                    application.setCalculatedGetValue(btcAmount);
                    application.setCalculatedGiveValue(totalAmount);
                    application.setCommissionAmount(commission);
                    application.setCommissionPercent(commissionPercent);
                    application.setTitle("Покупка BTC за RUB");
                    application.setStatus(ApplicationStatus.FREE);

                    temporaryApplications.put(user.getId(), application);

                    String message = "🔐 Теперь введите адрес Bitcoin-кошелька, на который поступит крипта:";
                    InlineKeyboardMarkup keyboard = createBackInlineKeyboard();
                    int messageId = bot.sendMessageWithKeyboard(chatId, message, keyboard);
        lastMessageId.put(chatId, messageId);
        addMessageToHistory(chatId, messageId);

                    user.setState(UserState.ENTERING_WALLET);
                    userService.update(user);

                } catch (NumberFormatException e) {
                    int messageId = bot.sendMessageWithKeyboard(chatId,
                            "❌ Пожалуйста, введите корректное число", createEnterAmountInlineKeyboard());
                    lastMessageId.put(chatId, messageId);
                    addMessageToHistory(chatId, messageId);
                } catch (Exception e) {
                    lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                            "❌ Ошибка при расчете: " + e.getMessage(), createEnterAmountInlineKeyboard()));
                }
        }
    }




    private void processMainMenu(Long chatId, User user, MyBot bot) {
        user.setState(UserState.MAIN_MENU);
        userService.update(user);
        showMainMenu(chatId, user, bot);
    }

    private void showAllApplications(Long chatId, User user, MyBot bot) {
        int page = adminAllApplicationsPage.getOrDefault(user.getId(), 0);
        int pageSize = 10;

        List<Application> allApplications = applicationService.findAll();
        int totalApplications = allApplications.size();
        int totalPages = (int) Math.ceil((double) totalApplications / pageSize);

        // Корректируем страницу, если она вышла за пределы
        if (page >= totalPages && totalPages > 0) {
            page = totalPages - 1;
            adminAllApplicationsPage.put(user.getId(), page);
        }

        // Получаем заявки для текущей страницы
        List<Application> pageApplications = allApplications.stream()
                .sorted((a1, a2) -> a2.getCreatedAt().compareTo(a1.getCreatedAt())) // новые сначала
                .skip(page * pageSize)
                .limit(pageSize)
                .collect(Collectors.toList());

        StringBuilder message = new StringBuilder();

        if (pageApplications.isEmpty()) {
            message.append("📭 Нет заявок в системе");
        } else {
            message.append(String.format("📋 Все заявки (стр. %d/%d):\n\n", page + 1, totalPages));

            for (int i = 0; i < pageApplications.size(); i++) {
                Application app = pageApplications.get(i);
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
                        userInfo,
                        app.getCalculatedGiveValue(),
                        app.getIsVip() ? "👑 VIP" : "🔹 Обычная",
                        app.getStatus().getDisplayName(),
                        app.getCreatedAt().format(DateTimeFormatter.ofPattern("dd.MM.yy HH:mm"))
                ));
            }
        }

        // Создаем клавиатуру с пагинацией
        InlineKeyboardMarkup inlineKeyboard = createAdminApplicationsPaginatedKeyboard(page, totalPages, "all");
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message.toString(), inlineKeyboard);
        lastMessageId.put(chatId, messageId);
    }

    private void showActiveApplications(Long chatId, User user, MyBot bot) {
        int page = adminActiveApplicationsPage.getOrDefault(user.getId(), 0);
        int pageSize = 10;

        List<Application> activeApplications = applicationService.findActiveApplications();
        int totalApplications = activeApplications.size();
        int totalPages = (int) Math.ceil((double) totalApplications / pageSize);

        // Корректируем страницу
        if (page >= totalPages && totalPages > 0) {
            page = totalPages - 1;
            adminActiveApplicationsPage.put(user.getId(), page);
        }

        // Получаем заявки для текущей страницы
        List<Application> sortedApplications = activeApplications.stream()
                .sorted(Comparator.comparing(Application::getIsVip).reversed()
                        .thenComparing(Application::getCreatedAt))
                .collect(Collectors.toList());

        List<Application> pageApplications = sortedApplications.stream()
                .skip(page * pageSize)
                .limit(pageSize)
                .collect(Collectors.toList());

        StringBuilder message = new StringBuilder();

        if (pageApplications.isEmpty()) {
            message.append("📭 Нет активных заявок");
        } else {
            message.append(String.format("📊 Активные заявки (стр. %d/%d):\n\n", page + 1, totalPages));

            for (int i = 0; i < pageApplications.size(); i++) {
                Application app = pageApplications.get(i);
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
                        (page * pageSize) + i + 1,
                        app.getIsVip() ? "👑" : "🔹",
                        app.getId(),
                        app.getUser().getFirstName(),
                        userInfo,
                        app.getCalculatedGiveValue(),
                        app.getCreatedAt().format(DateTimeFormatter.ofPattern("dd.MM.yy HH:mm"))
                ));
            }

            message.append("\nВведите номер заявки из списка для управления:");
        }

        InlineKeyboardMarkup inlineKeyboard = createAdminApplicationsPaginatedKeyboard(page, totalPages, "active");
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message.toString(), inlineKeyboard);
        lastMessageId.put(chatId, messageId);
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
    private InlineKeyboardMarkup createAdminApplicationsPaginatedKeyboard(int currentPage, int totalPages, String type) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // Ряд с пагинацией (только если больше 1 страницы)
        if (totalPages > 1) {
            List<InlineKeyboardButton> paginationRow = new ArrayList<>();

            // Кнопка "Назад"
            if (currentPage > 0) {
                InlineKeyboardButton prevButton = new InlineKeyboardButton();
                prevButton.setText("◀️ Назад");
                prevButton.setCallbackData("inline_admin_page_" + type + "_" + (currentPage - 1));
                paginationRow.add(prevButton);
            }

            // Информация о странице
            InlineKeyboardButton pageInfoButton = new InlineKeyboardButton();
            pageInfoButton.setText("Стр. " + (currentPage + 1) + "/" + totalPages);
            pageInfoButton.setCallbackData("inline_admin_page_info");
            paginationRow.add(pageInfoButton);

            // Кнопка "Вперед"
            if (currentPage < totalPages - 1) {
                InlineKeyboardButton nextButton = new InlineKeyboardButton();
                nextButton.setText("Вперед ▶️");
                nextButton.setCallbackData("inline_admin_page_" + type + "_" + (currentPage + 1));
                paginationRow.add(nextButton);
            }

            rows.add(paginationRow);
        }

        // Ряд с основными действиями
        List<InlineKeyboardButton> actionsRow = new ArrayList<>();

        InlineKeyboardButton refreshButton = new InlineKeyboardButton();
        refreshButton.setText("🔄 Обновить");
        refreshButton.setCallbackData("inline_admin_" + type);
        actionsRow.add(refreshButton);

        InlineKeyboardButton takeButton = new InlineKeyboardButton();
        takeButton.setText("🎯 Взять заявку");
        takeButton.setCallbackData("inline_admin_take");
        actionsRow.add(takeButton);

        // Кнопка "Следующая заявка" (опционально)
        InlineKeyboardButton nextAppButton = new InlineKeyboardButton();
        nextAppButton.setText("⏭️ Следующая");
        nextAppButton.setCallbackData("inline_admin_next");
        actionsRow.add(nextAppButton);

        rows.add(actionsRow);

        // Ряд с навигацией
        List<InlineKeyboardButton> navigationRow = new ArrayList<>();

        InlineKeyboardButton backButton = new InlineKeyboardButton();
        backButton.setText("🔙 Назад");
        backButton.setCallbackData("inline_admin_back");
        navigationRow.add(backButton);

        InlineKeyboardButton mainMenuButton = new InlineKeyboardButton();
        mainMenuButton.setText("💎 Главное меню");
        mainMenuButton.setCallbackData("inline_main_menu");
        navigationRow.add(mainMenuButton);

        rows.add(navigationRow);

        markup.setKeyboard(rows);
        return markup;
    }

    /**
     * Проверяет, может ли админ взять заявку
     */
    private boolean canAdminTakeApplication(Application application, User admin) {
        if (application.getStatus() != ApplicationStatus.FREE) {
            return false;
        }

        // Если заявка уже в работе другим админом
        if (application.getStatus() == ApplicationStatus.IN_WORK &&
                application.getAdminId() != null &&
                !application.getAdminId().equals(admin.getId())) {
            return false;
        }

        return true;
    }

    // Обработка "Следующая заявка"
    private void processNextApplication(Long chatId, User user, MyBot bot) {
        System.out.println("DEBUG: Processing next application navigation");

        // Определяем текущий тип списка
        String listType = "";
        int currentPage = 0;
        int totalPages = 0;

        if (user.getState() == UserState.ADMIN_VIEW_ALL_APPLICATIONS) {
            listType = "all";
            currentPage = adminAllApplicationsPage.getOrDefault(user.getId(), 0);
            List<Application> allApplications = applicationService.findAll();
            totalPages = (int) Math.ceil((double) allApplications.size() / 10);
        } else if (user.getState() == UserState.ADMIN_VIEW_ACTIVE_APPLICATIONS) {
            listType = "active";
            currentPage = adminActiveApplicationsPage.getOrDefault(user.getId(), 0);
            List<Application> activeApplications = applicationService.findActiveApplications();
            totalPages = (int) Math.ceil((double) activeApplications.size() / 10);
        } else {
            // Если состояние не подходит, показываем главное меню админа
            user.setState(UserState.ADMIN_MAIN_MENU);
            userService.update(user);
            showAdminMainMenu(chatId, bot);
            return;
        }

        // Переходим на следующую страницу
        int nextPage = currentPage + 1;

        // Проверяем, не вышли ли за пределы
        if (nextPage >= totalPages) {
            bot.sendMessage(chatId, "ℹ️ Вы уже на последней странице");
            return;
        }

        // Сохраняем новую страницу
        if ("all".equals(listType)) {
            adminAllApplicationsPage.put(user.getId(), nextPage);
            showAllApplications(chatId, user, bot);
        } else if ("active".equals(listType)) {
            adminActiveApplicationsPage.put(user.getId(), nextPage);
            showActiveApplications(chatId, user, bot);
        }

        System.out.println("DEBUG: Navigated to page " + nextPage + " of " + listType + " applications");
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

    private void processTakeApplication(Long chatId, User admin, MyBot bot, String callbackQueryId) {
        System.out.println("DEBUG: Processing take application request");

        // Получаем активные заявки
        List<Application> activeApplications = applicationService.findActiveApplications();

        if (activeApplications.isEmpty()) {
            String message = "📭 Нет активных заявок для взятия в работу";
            if (callbackQueryId != null) {
                bot.answerCallbackQuery(callbackQueryId, message);
            } else {
                bot.sendMessage(chatId, message);
            }
            return;
        }

        // Сортируем заявки: VIP сначала, затем по дате создания (старые сначала)
        List<Application> sortedApplications = activeApplications.stream()
                .sorted(Comparator.comparing(Application::getIsVip).reversed()
                        .thenComparing(Application::getCreatedAt))
                .collect(Collectors.toList());

        // Берем первую заявку из отсортированного списка
        Application nextApplication = sortedApplications.get(0);

        if (nextApplication == null) {
            String errorMessage = "❌ Ошибка при поиске заявки";
            if (callbackQueryId != null) {
                bot.answerCallbackQuery(callbackQueryId, errorMessage);
            } else {
                bot.sendMessage(chatId, errorMessage);
            }
            return;
        }

        // Проверяем, не взята ли заявка другим админом
        if (nextApplication.getStatus() == ApplicationStatus.IN_WORK &&
                nextApplication.getAdminId() != null &&
                !nextApplication.getAdminId().equals(admin.getId())) {

            String takenMessage = "❌ Эта заявка уже взята другим оператором";
            if (callbackQueryId != null) {
                bot.answerCallbackQuery(callbackQueryId, takenMessage);
            } else {
                bot.sendMessage(chatId, takenMessage);
            }
            return;
        }

        try {
            // Устанавливаем статус "В работе" и привязываем админа
            nextApplication.setStatus(ApplicationStatus.IN_WORK);
            nextApplication.setAdminId(admin.getId());
            applicationService.update(nextApplication);

            // Сохраняем выбранную заявку
            selectedApplication.put(admin.getId(), nextApplication.getId());

            // Обновляем состояние пользователя
            admin.setState(UserState.ADMIN_VIEWING_APPLICATION_DETAILS);
            userService.update(admin);

            String successMessage = "✅ Заявка #" + nextApplication.getId() + " взята в работу";
            if (callbackQueryId != null) {
                bot.answerCallbackQuery(callbackQueryId, successMessage);
            }

            // Показываем меню управления заявкой
            showAdminApplicationManagementMenu(chatId, admin, nextApplication, bot);

            System.out.println("DEBUG: Application " + nextApplication.getId() + " taken by admin " + admin.getId());

            // Отправляем уведомление пользователю, если заявка перешла в работу
            try {
                String userNotification = String.format(
                        "🔄 Ваша заявка #%d взята в работу оператором.\n\n" +
                                "📞 Свяжитесь с оператором для уточнения деталей: @SUP_CN",
                        nextApplication.getId()
                );
                bot.sendMessage(nextApplication.getUser().getTelegramId(), userNotification);
            } catch (Exception e) {
                System.err.println("Не удалось отправить уведомление пользователю: " + e.getMessage());
            }

        } catch (Exception e) {
            System.err.println("Ошибка при взятии заявки: " + e.getMessage());
            String errorMessage = "❌ Ошибка при взятии заявки: " + e.getMessage();
            if (callbackQueryId != null) {
                bot.answerCallbackQuery(callbackQueryId, errorMessage);
            } else {
                bot.sendMessage(chatId, errorMessage);
            }
        }
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
        System.out.println("COMMISSION DEBUG: Displaying commission settings to admin");

        StringBuilder message = new StringBuilder();
        message.append("💰 Управление комиссиями\n\n");
        message.append("📊 Текущие настройки комиссий:\n");

        // Используем новый метод для отображения комиссий
        String commissionRangesDisplay = commissionConfig.getCommissionRangesDisplay();
        message.append(commissionRangesDisplay);

        message.append("\n📝 Как обновить комиссию:\n");
        message.append("Введите в формате: `СУММА ПРОЦЕНТ`\n\n");
        message.append("Примеры:\n");
        message.append("• `1000 50.0` - для сумм от 1000 ₽\n");
        message.append("• `5000 31.0` - для сумм от 5000 ₽\n");
        message.append("• `1000-1999 50.0` - для диапазона\n\n");
        message.append("💡 Примечание: Комиссия применяется автоматически при создании заявок");

        // Создаем клавиатуру с кнопкой тестирования
        InlineKeyboardMarkup inlineKeyboard = createAdminCommissionSettingsKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message.toString(), inlineKeyboard);
        lastMessageId.put(chatId, messageId);

        System.out.println("COMMISSION DEBUG: Commission settings displayed successfully");
    }

    private String getRangeDescription(BigDecimal minAmount, Map<String, BigDecimal> allRanges) {
        // Находим следующий порог для определения диапазона
        BigDecimal nextThreshold = allRanges.keySet().stream()
                .map(BigDecimal::new)
                .filter(threshold -> threshold.compareTo(minAmount) > 0)
                .min(BigDecimal::compareTo)
                .orElse(null);

        if (nextThreshold != null) {
            // Вычитаем 1 для красивого отображения диапазона
            BigDecimal maxAmount = nextThreshold.subtract(BigDecimal.ONE);
            return String.format("%s-%s ₽", minAmount, maxAmount);
        } else {
            return minAmount + "+ ₽";
        }
    }

    private InlineKeyboardMarkup createAdminCommissionSettingsKeyboard() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // Кнопка тестирования комиссий
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton testButton = new InlineKeyboardButton();
        testButton.setText("🧪 Тест комиссий");
        testButton.setCallbackData("inline_test_commissions");
        row1.add(testButton);

        // Кнопка возврата
        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton backButton = new InlineKeyboardButton();
        backButton.setText("🔙 Назад");
        backButton.setCallbackData("inline_admin_back");
        row2.add(backButton);

        rows.add(row1);
        rows.add(row2);

        markup.setKeyboard(rows);
        return markup;
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
            // ИЗМЕНЕНО: double на BigDecimal
            BigDecimal value = new BigDecimal(parts[2]);
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
                // ИЗМЕНЕНО: Сравнение BigDecimal
                if (value.compareTo(BigDecimal.ONE) < 0 || value.compareTo(new BigDecimal("100")) > 0) {
                    throw new IllegalArgumentException("Процент скидки должен быть от 1 до 100");
                }
                coupon.setDiscountPercent(value);
            } else if ("amount".equalsIgnoreCase(type)) {
                // ИЗМЕНЕНО: Сравнение BigDecimal
                if (value.compareTo(BigDecimal.ONE) < 0) {
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
            // ИЗМЕНЕНО: double на BigDecimal
            BigDecimal value = new BigDecimal(parts[2]);
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
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, message, createAdminApplicationsInlineKeyboard()));
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
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, "❌ Заявка не найдена", createAdminApplicationsInlineKeyboard()));
                return;
            }

            selectedApplication.put(user.getId(), applicationId);
            user.setState(UserState.ADMIN_VIEWING_APPLICATION_DETAILS);
            userService.update(user);

            showAdminApplicationDetails(chatId, user, application, bot);

        } catch (NumberFormatException e) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, "❌ Введите корректный номер заявки", createAdminApplicationsInlineKeyboard()));
        }
    }

    private void processAdminApplicationSearch(Long chatId, User user, String text, MyBot bot) {
        try {
            Long applicationId = Long.parseLong(text);
            Application application = applicationService.find(applicationId);

            if (application == null) {
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                    "❌ Заявка #" + applicationId + " не найдена\n\n🔍 Введите другой номер заявки:",
                    createBackToAdminKeyboard()));
                return;
            }

            selectedApplication.put(user.getId(), applicationId);
            user.setState(UserState.ADMIN_VIEWING_APPLICATION_DETAILS);
            userService.update(user);

            showAdminApplicationDetails(chatId, user, application, bot);

        } catch (NumberFormatException e) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                "❌ Введите корректный номер заявки\n\n🔍 Пример: 123",
                createBackToAdminKeyboard()));
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
            BigDecimal rubAmount = toBigDecimal(text);
            BigDecimal btcPrice = (cryptoPriceService.getCurrentPrice("BTC", "RUB"));
            BigDecimal btcAmount = rubAmount.divide(btcPrice, 8, RoundingMode.HALF_UP);
            BigDecimal commission = commissionService.calculateCommission(rubAmount);
            BigDecimal totalAmount = commissionService.calculateTotalWithCommission(rubAmount);

            String calculation = String.format("""
                            🧮 Расчет покупки:
                            
                            💰 Вводимая сумма: %s
                            💸 Комиссия: %s (%s)
                            💵 Итого к оплате: %s
                            ₿ Вы получите: %s
                            
                            Курс BTC: %s
                            """,
                    formatRubAmount(rubAmount),
                    formatRubAmount(commission),
                    formatPercent(commissionService.getCommissionPercent(rubAmount)),
                    formatRubAmount(totalAmount),
                    formatBtcAmount(btcAmount),
                    formatRubAmount(btcPrice)
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

        if (application.getUsedBonusBalance().compareTo(BigDecimal.ZERO) == 1) {
            user.setBonusBalance(user.getBonusBalance().add(application.getUsedBonusBalance()));
            userService.update(user);
        }

        applicationService.update(application);

        // УДАЛЯЕМ сообщение с заявкой если оно есть
        if (application.getTelegramMessageId() != null) {
            bot.deleteMessage(chatId, application.getTelegramMessageId());
        }

        String message = "✅ Заявка #" + applicationId + " успешно отменена.";
        if (application.getUsedBonusBalance().compareTo(BigDecimal.ZERO) == 1) {
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

    private void processAdminPageChange(Long chatId, User user, String callbackData, MyBot bot) {
        try {
            String[] parts = callbackData.split("_");
            String listType = parts[3]; // "all" или "active"
            int newPage = Integer.parseInt(parts[4]);

            if ("all".equals(listType)) {
                adminAllApplicationsPage.put(user.getId(), newPage);
                showAllApplications(chatId, user, bot);
            } else if ("active".equals(listType)) {
                adminActiveApplicationsPage.put(user.getId(), newPage);
                showActiveApplications(chatId, user, bot);
            }

        } catch (Exception e) {
            System.out.println("ERROR in processAdminPageChange: " + e.getMessage());
            bot.sendMessage(chatId, "❌ Ошибка при переключении страницы");
        }
    }

    private void processAdminUsersPageChange(Long chatId, User user, String callbackData, MyBot bot) {
        try {
            System.out.println("DEBUG: processAdminUsersPageChange called with: " + callbackData);

            if (callbackData.equals("inline_admin_users_page_info")) {
                // Просто обновляем текущую страницу
                if (user.getState() == UserState.ADMIN_VIEW_ALL_USERS) {
                    showAllUsers(chatId, user, bot);
                }
                return;
            }

            if (callbackData.equals("inline_admin_users_back")) {
                user.setState(UserState.ADMIN_USERS_MENU);
                userService.update(user);
                showAdminUsersMenu(chatId, bot);
                return;
            }

            String[] parts = callbackData.split("_");
            System.out.println("DEBUG: Callback parts: " + String.join(", ", parts));

            String action = parts[3]; // "prev" или "next"
            String type = parts[4] + "_" + parts[5]; // "all_users"

            int currentPage = adminAllUsersPage.getOrDefault(user.getId(), 0);
            int newPage = currentPage;

            if ("prev".equals(action)) {
                newPage = Math.max(0, currentPage - 1);
            } else if ("next".equals(action)) {
                newPage = currentPage + 1;
            }

            adminAllUsersPage.put(user.getId(), newPage);
            showAllUsers(chatId, user, bot);

        } catch (Exception e) {
            System.out.println("ERROR in processAdminUsersPageChange: " + e.getMessage());
            bot.sendMessage(chatId, "❌ Ошибка при переключении страницы пользователей");
        }
    }

    private void showVipConfirmation(Long chatId, User user, Application application, MyBot bot) {
        String message = String.format("""
            💎 Хотите добавить 👑 VIP-приоритет за %s?
            
            👑 VIP-приоритет обеспечивает:
            • Первоочередную обработку
            • Ускоренное выполнение  
            • Приоритет в очереди
            • Личного оператора
            
            Выберите вариант:
            """, formatRubAmount(VIP_COST)); // ИЗМЕНЕНО

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
        // Обновляем статистику перед показом
        user = userService.find(user.getId()); // Перезагружаем актуальные данные
        ReferralStatsEmbedded stats = referralService.getReferralStats(user);

        // Получаем актуальную реферальную ссылку
        String referralLink = referralService.generateReferralLinkWithCode(user);
        
        // Получаем реферальный код пользователя
        List<ReferralCode> userCodes = referralService.getUserActiveReferralCodes(user.getId());
        String referralCodeDisplay;
        if (!userCodes.isEmpty()) {
            ReferralCode code = userCodes.get(0);
            referralCodeDisplay = String.format("🎫 Ваш реферальный код: %s", code.getCode());
        } else {
            referralCodeDisplay = "🎫 Реферальный код: не создан";
        }

        String message = String.format("""
                🎁 Реферальная программа

                🔗 Ваша реферальная ссылка:
                📌 %s
                
                %s

                ━━━━━━━━━━━━━━━━━━━━━━━
                📊 Статистика
                ━━━━━━━━━━━━━━━━━━━━━━━

                🤝 Ваш реферальный уровень: %.2f%%
                1️⃣ Бонус к рефералам 1 уровня: %.2f%%
                2️⃣ Бонус к рефералам 2 уровня: %.2f%%

                👥 Количество рефералов:
                1️⃣ Первого уровня: %d шт.
                2️⃣ Второго уровня: %d шт.
                🏃‍➡️ Активных рефералов (всего): %d
                🌐 Всего пользователей с реферальным кодом: %d

                ━━━━━━━━━━━━━━━━━━━━━━━
                💰 Финансовая статистика
                ━━━━━━━━━━━━━━━━━━━━━━━

                📅 За всё время:
                💳 Сумма обменов: %.2f руб.
                ⚽️ Количество обменов: %d

                📅 За этот месяц:
                💳 Сумма обменов: %.2f руб.
                ⚽️ Количество обменов: %d

                🏦 Балансы:
                💰 Всего заработано: %.2f ₽
                💵 Текущий баланс: %.2f ₽

                ━━━━━━━━━━━━━━━━━━━━━━━
                 📞 Контакты:
                ━━━━━━━━━━━━━━━━━━━━━━━

                🤖 Бот: @COSANOSTRA24_bot
                ☎️ Оператор: @SUP_CN
                """,
                referralLink,
                referralCodeDisplay,
                referralService.getLevel1Percent(),
                referralService.getLevel1Percent(),
                referralService.getLevel2Percent(),
                stats.getLevel1Count(),
                stats.getLevel2Count(),
                stats.getActiveReferrals(),
                userService.getUsersWithReferralCodeCount(),
                stats.getTotalExchangeAmount(),
                stats.getTotalExchangeCount(),
                stats.getMonthlyExchangeAmount(),
                stats.getMonthlyExchangeCount(),
                user.getReferralEarnings(),
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

            // FIX: Create a new ReferralCode object first
            ReferralCode referralCode = new ReferralCode();
            // Set the description from user input
            referralCode.setDescription(text);
            // Set the user
            referralCode.setUser(user);

            // Generate the referral code using the service
            ReferralCode createdCode = referralService.createReferralCode(referralCode);

            String message = String.format("""
                        ✅ Реферальный код создан!
                        
                        🔸 Ваш код: %s
                        📝 Описание: %s
                        
                        Теперь вы можете делиться этим кодом с друзьями. 
                        За каждую успешную заявку реферала вы будете получать %.2f%% от суммы заявки.
                        """,
                    createdCode.getCode(),
                    text,
                    createdCode.getRewardPercent());

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

        // Первая строка: основные действия
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton cancelButton = new InlineKeyboardButton();
        cancelButton.setText("❌ Отменить заявку");
        cancelButton.setCallbackData("cancel_app_" + applicationId);
        row1.add(cancelButton);

        InlineKeyboardButton queueButton = new InlineKeyboardButton();
        queueButton.setText("📊 Номер в очереди");
        queueButton.setCallbackData("queue_app_" + applicationId);
        row1.add(queueButton);

        // Вторая строка: оператор и спам-блок
        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton operatorButton = new InlineKeyboardButton();
        operatorButton.setText("📞 Написать оператору @SUP_CN");
        operatorButton.setUrl("https://t.me/SUP_CN");
        row2.add(operatorButton);

        InlineKeyboardButton spamButton = new InlineKeyboardButton();
        spamButton.setText("🆘 У меня СПАМ-БЛОК");
        spamButton.setCallbackData("inline_spam_block_help");
        row2.add(spamButton);

        rows.add(row1);
        rows.add(row2);

        markup.setKeyboard(rows);
        return markup;
    }


    private InlineKeyboardMarkup createMainMenuInlineKeyboard(User user) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // Первый ряд: Покупка
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton buyButton = new InlineKeyboardButton();
        buyButton.setText("💰 Купить крипту");
        buyButton.setCallbackData("inline_buy_menu");
        row1.add(buyButton);

        // Второй ряд: Комиссии и Прочее
        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton commissionButton = new InlineKeyboardButton();
        commissionButton.setText("💳 Комиссии");
        commissionButton.setCallbackData("inline_commissions");
        row2.add(commissionButton);

        InlineKeyboardButton otherButton = new InlineKeyboardButton();
        otherButton.setText("⚙️ Прочее");
        otherButton.setCallbackData("inline_other");
        row2.add(otherButton);

        // Третий ряд: Админ панель (только для админов)
        if (adminConfig.isAdmin(user.getId())) {
            List<InlineKeyboardButton> row3 = new ArrayList<>();
            InlineKeyboardButton adminButton = new InlineKeyboardButton();
            adminButton.setText("👨‍💼 Админ панель");
            adminButton.setCallbackData("inline_admin");
            row3.add(adminButton);
            rows.add(row3);
        }

        rows.add(row1);
        rows.add(row2);

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

        // Bitcoin
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton btcButton = new InlineKeyboardButton();
        btcButton.setText("₿ Bitcoin (BTC)");
        btcButton.setCallbackData("inline_buy_btc");
        row1.add(btcButton);

        // Litecoin
        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton ltcButton = new InlineKeyboardButton();
        ltcButton.setText("Ł Litecoin (LTC)");
        ltcButton.setCallbackData("inline_buy_ltc");
        row2.add(ltcButton);

        // Monero
        List<InlineKeyboardButton> row3 = new ArrayList<>();
        InlineKeyboardButton xmrButton = new InlineKeyboardButton();
        xmrButton.setText("ɱ Monero (XMR)");
        xmrButton.setCallbackData("inline_buy_xmr");
        row3.add(xmrButton);

        // Навигация
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

    private void showInputMethodMenu(Long chatId, User user, CryptoCurrency crypto, MyBot bot) {
        System.out.println("DEBUG: showInputMethodMenu for " + crypto);

        String cryptoDescription = switch (crypto) {
            case BTC -> "биткоинов";
            case LTC -> "лайткоинов";
            case XMR -> "монеро";
            default -> crypto.getDisplayName().toLowerCase();
        };

        String message = String.format("""
        Покупка %s

        Выберите способ ввода суммы:

        • В рублях - укажите сумму в RUB
        • %s - укажите количество %s
        """, crypto.getDisplayName(),
            switch (crypto) {
                case BTC -> "В биткоинах";
                case LTC -> "В лайткоинах";
                case XMR -> "В монеро";
                default -> "В " + crypto.getDisplayName().toLowerCase();
            },
            cryptoDescription);

        InlineKeyboardMarkup inlineKeyboard = createInputMethodInlineKeyboard(crypto);

        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);

        // Убедимся, что состояние установлено правильно
        user.setState(UserState.CHOOSING_INPUT_METHOD);
        userService.update(user);
    }

    private InlineKeyboardMarkup createInputMethodInlineKeyboard(CryptoCurrency crypto) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // Кнопка выбора способа ввода - В рублях
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton rubAmountButton = new InlineKeyboardButton();
        rubAmountButton.setText("В рублях");
        rubAmountButton.setCallbackData("inline_input_rub_" + crypto.name());
        row1.add(rubAmountButton);

        // Кнопка выбора способа ввода - В конкретной криптовалюте
        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton cryptoAmountButton = new InlineKeyboardButton();
        String cryptoText = switch (crypto) {
            case BTC -> "В биткоинах";
            case LTC -> "В лайткоинах";
            case XMR -> "В монеро";
            default -> "В " + crypto.getDisplayName().toLowerCase();
        };
        cryptoAmountButton.setText(cryptoText);
        cryptoAmountButton.setCallbackData("inline_input_crypto_" + crypto.name());
        row2.add(cryptoAmountButton);

        // Навигация
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

        // === ЗАЯВКИ ===
        // Первый ряд - просмотр заявок
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton allAppsButton = new InlineKeyboardButton();
        allAppsButton.setText("📋 Все заявки");
        allAppsButton.setCallbackData("inline_admin_all");
        row1.add(allAppsButton);

        InlineKeyboardButton activeAppsButton = new InlineKeyboardButton();
        activeAppsButton.setText("⚡ Активные");
        activeAppsButton.setCallbackData("inline_admin_active");
        row1.add(activeAppsButton);

        // Второй ряд - мои заявки и поиск
        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton myAppsButton = new InlineKeyboardButton();
        myAppsButton.setText("👨‍💼 Мои заявки");
        myAppsButton.setCallbackData("inline_admin_my_applications");
        row2.add(myAppsButton);

        InlineKeyboardButton searchAppButton = new InlineKeyboardButton();
        searchAppButton.setText("🔍 Поиск заявки");
        searchAppButton.setCallbackData("inline_admin_search_application");
        row2.add(searchAppButton);

        // === ПОЛЬЗОВАТЕЛИ ===
        // Третий ряд - управление пользователями
        List<InlineKeyboardButton> row3 = new ArrayList<>();
        InlineKeyboardButton usersButton = new InlineKeyboardButton();
        usersButton.setText("👥 Пользователи");
        usersButton.setCallbackData("inline_admin_users");
        row3.add(usersButton);

        // === УПРАВЛЕНИЕ СИСТЕМОЙ ===
        // Четвертый ряд - купоны и комиссии
        List<InlineKeyboardButton> row4 = new ArrayList<>();
        InlineKeyboardButton couponButton = new InlineKeyboardButton();
        couponButton.setText("🎫 Купоны");
        couponButton.setCallbackData("inline_admin_coupons");
        row4.add(couponButton);

        InlineKeyboardButton commissionButton = new InlineKeyboardButton();
        commissionButton.setText("💰 Комиссии");
        commissionButton.setCallbackData("inline_admin_commission");
        row4.add(commissionButton);

        // Пятый ряд - бонусные балансы
        List<InlineKeyboardButton> row5 = new ArrayList<>();
        InlineKeyboardButton bonusButton = new InlineKeyboardButton();
        bonusButton.setText("💳 Бонусные балансы");
        bonusButton.setCallbackData("inline_admin_bonus_manage");
        row5.add(bonusButton);

        // Шестой ряд - рассылка
        List<InlineKeyboardButton> row6 = new ArrayList<>();
        InlineKeyboardButton broadcastButton = new InlineKeyboardButton();
        broadcastButton.setText("📢 Рассылка");
        broadcastButton.setCallbackData("inline_admin_broadcast");
        row6.add(broadcastButton);

        // Седьмой ряд - навигация
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

    private void showEnterAmountMenuRub(Long chatId, User user, MyBot bot) {
        String operationType = currentOperation.get(user.getId()).contains("BUY") ? "купить" : "продать";
        String message = String.format("💎 Введите сумму в рублях (RUB) которую хотите %s:",
                operationType);

        InlineKeyboardMarkup keyboard = createBackAndMainMenuKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, keyboard);
        lastMessageId.put(chatId, messageId);
    }

    private void showEnterAmountMenu(Long chatId, User user, CryptoCurrency crypto, MyBot bot) {
        String operationType = currentOperation.get(user.getId()).contains("BUY") ? "купить" : "продать";
        String message = String.format("💎 Введите количество %s (%s) которое хотите %s:",
                crypto.getDisplayName(),
                crypto.getSymbol(),
                operationType);

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

        // Первый ряд - условия программы
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton conditionsButton = new InlineKeyboardButton();
        conditionsButton.setText("📋 Условия программы");
        conditionsButton.setCallbackData("inline_referral_conditions");
        row1.add(conditionsButton);

        // Второй ряд - контакт оператора
        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton operatorButton = new InlineKeyboardButton();
        operatorButton.setText("📞 Оператор @SUP_CN");
        operatorButton.setUrl("https://t.me/SUP_CN");
        row2.add(operatorButton);

        // Третий ряд - навигация
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

        // === ИЗМЕНЕНИЕ СТАТУСА ЗАЯВКИ ===
        // Первый ряд - основные действия
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton inWorkButton = new InlineKeyboardButton();
        inWorkButton.setText("🟡 В работу");
        inWorkButton.setCallbackData("inline_admin_app_inwork_" + applicationId);
        row1.add(inWorkButton);

        InlineKeyboardButton paidButton = new InlineKeyboardButton();
        paidButton.setText("🔵 Оплачен");
        paidButton.setCallbackData("inline_admin_app_paid_" + applicationId);
        row1.add(paidButton);

        // Второй ряд - завершение и отмена
        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton completedButton = new InlineKeyboardButton();
        completedButton.setText("✅ Выполнено");
        completedButton.setCallbackData("inline_admin_app_completed_" + applicationId);
        row2.add(completedButton);

        InlineKeyboardButton cancelButton = new InlineKeyboardButton();
        cancelButton.setText("🔴 Отменить");
        cancelButton.setCallbackData("inline_admin_app_cancel_" + applicationId);
        row2.add(cancelButton);

        // === РАБОТА С ПОЛЬЗОВАТЕЛЕМ ===
        // Третий ряд - информация и связь
        List<InlineKeyboardButton> row3 = new ArrayList<>();
        InlineKeyboardButton userInfoButton = new InlineKeyboardButton();
        userInfoButton.setText("👤 Инфо о пользователе");
        userInfoButton.setCallbackData("inline_admin_app_userinfo_" + applicationId);
        row3.add(userInfoButton);

        InlineKeyboardButton contactButton = new InlineKeyboardButton();
        contactButton.setText("💬 Написать");
        contactButton.setUrl("https://t.me/" + (applicationService.find(applicationId).getUser().getUsername() != null ? applicationService.find(applicationId).getUser().getUsername() : "cosanostra_support"));
        row3.add(contactButton);

        // === ДОПОЛНИТЕЛЬНЫЕ ДЕЙСТВИЯ ===
        // Четвертый ряд - дополнительные статусы
        List<InlineKeyboardButton> row4 = new ArrayList<>();
        InlineKeyboardButton freeButton = new InlineKeyboardButton();
        freeButton.setText("🟢 Освободить");
        freeButton.setCallbackData("inline_admin_app_free_" + applicationId);
        row4.add(freeButton);

        // === НАВИГАЦИЯ ===
        // Пятый ряд - мои заявки и все заявки
        List<InlineKeyboardButton> row5 = new ArrayList<>();
        InlineKeyboardButton myAppsButton = new InlineKeyboardButton();
        myAppsButton.setText("👨‍💼 Мои заявки");
        myAppsButton.setCallbackData("inline_admin_my_applications");
        row5.add(myAppsButton);

        InlineKeyboardButton allAppsButton = new InlineKeyboardButton();
        allAppsButton.setText("📋 Все заявки");
        allAppsButton.setCallbackData("inline_admin_all");
        row5.add(allAppsButton);

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

        // Второй ряд
        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton takeButton = new InlineKeyboardButton();
        takeButton.setText("🎯 Взять заявку");
        takeButton.setCallbackData("inline_admin_take");
        row2.add(takeButton);

        InlineKeyboardButton myAppsButton = new InlineKeyboardButton();
        myAppsButton.setText("👨‍💼 Мои заявки");
        myAppsButton.setCallbackData("inline_admin_my_applications");
        row2.add(myAppsButton);

        // Третий ряд - навигация
        List<InlineKeyboardButton> row3 = new ArrayList<>();
        InlineKeyboardButton backButton = new InlineKeyboardButton();
        backButton.setText("🔙 Назад");
        backButton.setCallbackData("inline_admin_back");
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

    private boolean validateAmount(BigDecimal amount, String currency, Long chatId, MyBot bot) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                    "❌ Сумма должна быть больше 0", createEnterAmountInlineKeyboard()));
            return false;
        }

        if (currency.equals("RUB") && amount.compareTo(BigDecimal.valueOf(1000)) < 0) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                    "❌ Минимальная сумма заявки 1000 рублей", createEnterAmountInlineKeyboard()));
            return false;
        }

        if (currency.equals("BTC") && amount.compareTo(BigDecimal.valueOf(0.00001)) < 0) {
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                    "❌ Минимальное количество BTC: 0.00001", createEnterAmountInlineKeyboard()));
            return false;
        }

        return true;
    }

    private void processBroadcastMessage(Long chatId, User user, Update update, MyBot bot) {
        try {
            System.out.println("DEBUG: Processing broadcast message");

            if (update.getMessage() == null) {
                System.out.println("DEBUG: No message in update");
                return;
            }

            System.out.println("DEBUG: Message type: " + (update.getMessage().hasText() ? "text" : "other"));

            // Принимаем любое сообщение в состоянии ADMIN_BROADCAST_MESSAGE

            // Получаем список всех активных пользователей
            List<User> activeUsers = userService.findAllActiveUsers();
            System.out.println("DEBUG: Found " + activeUsers.size() + " active users for broadcast");

            if (activeUsers.isEmpty()) {
                lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                    "❌ Нет активных пользователей для рассылки",
                    createBackToAdminKeyboard()));
                return;
            }

            // Логируем первых 5 пользователей для отладки
            for (int i = 0; i < Math.min(5, activeUsers.size()); i++) {
                User targetUser = activeUsers.get(i);
                System.out.println("DEBUG: User " + (i+1) + ": ID=" + targetUser.getId() + ", TelegramID=" + targetUser.getTelegramId() + ", Username=" + targetUser.getUsername());
            }

            // Счетчики для отчета
            int successCount = 0;
            int errorCount = 0;

            // Рассылаем сообщение всем пользователям
            System.out.println("DEBUG: Starting broadcast to " + activeUsers.size() + " users");
            for (User targetUser : activeUsers) {
                try {
                    System.out.println("DEBUG: Sending message to user " + targetUser.getId() + " (" + targetUser.getUsername() + ")");

                    // Для текстовых сообщений используем sendMessage, для остальных - copyMessage
                    if (update.getMessage().hasText()) {
                        bot.sendMessage(targetUser.getTelegramId(), update.getMessage().getText());
                        System.out.println("DEBUG: Sent text message to user " + targetUser.getId());
                    } else {
                        bot.copyMessage(targetUser.getTelegramId(), chatId, update.getMessage().getMessageId());
                        System.out.println("DEBUG: Copied message to user " + targetUser.getId());
                    }
                    successCount++;
                } catch (Exception e) {
                    errorCount++;
                    System.err.println("Ошибка отправки сообщения пользователю " + targetUser.getId() + ": " + e.getMessage());
                }
            }

            // Отправляем отчет администратору
            String reportMessage = String.format(
                "📢 Рассылка завершена!\n\n" +
                "✅ Успешно отправлено: %d\n" +
                "❌ Ошибок: %d\n" +
                "👥 Всего пользователей: %d",
                successCount, errorCount, activeUsers.size()
            );

            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId, reportMessage, createBackToAdminKeyboard()));

            // Возвращаем в главное меню админа
            user.setState(UserState.ADMIN_MAIN_MENU);
            userService.update(user);

        } catch (Exception e) {
            System.err.println("Ошибка при обработке рассылки: " + e.getMessage());
            lastMessageId.put(chatId, bot.sendMessageWithKeyboard(chatId,
                "❌ Ошибка при обработке рассылки: " + e.getMessage(),
                createBackToAdminKeyboard()));

            user.setState(UserState.ADMIN_MAIN_MENU);
            userService.update(user);
        }
    }

    private String formatCryptoName(ValueType valueType) {
        if (valueType == null) return "Неизвестно";
        switch (valueType) {
            case BTC: return "Bitcoin (BTC)";
            case LTC: return "Litecoin (LTC)";
            case XMR: return "Monero (XMR)";
            case RUB: return "Рубли (RUB)";
            default: return valueType.name();
        }
    }

    private String formatApplicationAmount(Application application) {
        if (application == null) return "0";

        BigDecimal amount = null;
        ValueType currencyType = null;

        // Определяем сумму и тип валюты для отображения
        if (application.getUserValueGiveType() == ValueType.RUB) {
            amount = application.getCalculatedGiveValue();
            currencyType = ValueType.RUB;
        } else if (application.getUserValueGetType() == ValueType.RUB) {
            amount = application.getCalculatedGetValue();
            currencyType = ValueType.RUB;
        } else if (application.getUserValueGiveType() == ValueType.BTC) {
            amount = application.getCalculatedGiveValue();
            currencyType = ValueType.BTC;
        } else if (application.getUserValueGetType() == ValueType.BTC) {
            amount = application.getCalculatedGetValue();
            currencyType = ValueType.BTC;
        } else if (application.getUserValueGiveType() == ValueType.LTC) {
            amount = application.getCalculatedGiveValue();
            currencyType = ValueType.LTC;
        } else if (application.getUserValueGetType() == ValueType.LTC) {
            amount = application.getCalculatedGetValue();
            currencyType = ValueType.LTC;
        } else if (application.getUserValueGiveType() == ValueType.XMR) {
            amount = application.getCalculatedGiveValue();
            currencyType = ValueType.XMR;
        } else if (application.getUserValueGetType() == ValueType.XMR) {
            amount = application.getCalculatedGetValue();
            currencyType = ValueType.XMR;
        }

        if (amount == null) return "Неизвестно";

        // Форматируем в зависимости от типа валюты
        if (currencyType == ValueType.RUB) {
            return formatRubAmount(amount);
        } else {
            // Для криптовалют конвертируем ValueType в CryptoCurrency
            CryptoCurrency crypto = null;
            if (currencyType == ValueType.BTC) {
                crypto = CryptoCurrency.BTC;
            } else if (currencyType == ValueType.LTC) {
                crypto = CryptoCurrency.LTC;
            } else if (currencyType == ValueType.XMR) {
                crypto = CryptoCurrency.XMR;
            }

            if (crypto != null) {
                return formatCryptoAmount(amount, crypto);
            } else {
                return formatRubAmount(amount); // fallback
            }
        }
    }

    /**
     * Показывает меню пользователей для админа
     */
    private void showAdminUsersMenu(Long chatId, MyBot bot) {
        String message = "👥 Управление пользователями\n\nВыберите действие:";

        InlineKeyboardMarkup inlineKeyboard = createAdminUsersMenuInlineKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);
    }

    /**
     * Показывает форму поиска пользователя в меню пользователей
     */
    private void showAdminUsersSearch(Long chatId, MyBot bot) {
        String message = "🔍 Поиск пользователя\n\nВведите username (без @) или ID пользователя:";

        InlineKeyboardMarkup inlineKeyboard = createBackToAdminUsersMenuKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);
    }

    /**
     * Обрабатывает меню пользователей админа
     */
    private void processAdminUsersMenu(Long chatId, User user, String text, MyBot bot) {
        switch (text) {
            case "🔙 Назад":
                user.setState(UserState.ADMIN_MAIN_MENU);
                userService.update(user);
                showAdminMainMenu(chatId, bot);
                break;
            default:
                showAdminUsersMenu(chatId, bot);
                break;
        }
    }

    /**
     * Обрабатывает поиск пользователя в меню пользователей
     */
    private void processAdminUsersSearchUser(Long chatId, User user, String text, MyBot bot) {
        try {
            User foundUser = null;

            // Проверяем, является ли текст числом (ID пользователя)
            try {
                Long userId = Long.parseLong(text.trim());
                foundUser = userService.find(userId);
            } catch (NumberFormatException e) {
                // Если не число, ищем по username
                foundUser = userService.findByUsername(text.trim());
            }

            if (foundUser != null) {
                // Показываем детали найденного пользователя
                showUserDetailsForAdmin(chatId, foundUser, bot);
                user.setState(UserState.ADMIN_USERS_MENU);
                userService.update(user);
            } else {
                String message = "❌ Пользователь не найден. Попробуйте другой username или ID.";
                int messageId = bot.sendMessageWithKeyboard(chatId, message, createBackToAdminUsersMenuKeyboard());
                lastMessageId.put(chatId, messageId);
            }
        } catch (Exception e) {
            System.err.println("Ошибка при поиске пользователя: " + e.getMessage());
            String message = "❌ Ошибка при поиске пользователя. Попробуйте еще раз.";
            int messageId = bot.sendMessageWithKeyboard(chatId, message, createBackToAdminUsersMenuKeyboard());
            lastMessageId.put(chatId, messageId);
        }
    }

    /**
     * Показывает детали пользователя для админа в меню пользователей
     */
    private void showUserDetailsForAdmin(Long chatId, User targetUser, MyBot bot) {
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

        InlineKeyboardMarkup inlineKeyboard = createBackToAdminUsersMenuKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message, inlineKeyboard);
        lastMessageId.put(chatId, messageId);
    }

    /**
     * Создает клавиатуру для меню пользователей
     */
    private InlineKeyboardMarkup createAdminUsersMenuInlineKeyboard() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // Первый ряд - все пользователи
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton allUsersButton = new InlineKeyboardButton();
        allUsersButton.setText("👥 Все пользователи");
        allUsersButton.setCallbackData("inline_admin_all_users");
        row1.add(allUsersButton);

        // Второй ряд - последние пользователи и поиск
        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton recentUsersButton = new InlineKeyboardButton();
        recentUsersButton.setText("🆕 Последние пользователи");
        recentUsersButton.setCallbackData("inline_admin_recent_users");
        row2.add(recentUsersButton);

        InlineKeyboardButton searchUserButton = new InlineKeyboardButton();
        searchUserButton.setText("🔍 Поиск пользователя");
        searchUserButton.setCallbackData("inline_admin_users_search");
        row2.add(searchUserButton);

        // Третий ряд - навигация
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

    /**
     * Показывает всех пользователей с пагинацией
     */
    private void showAllUsers(Long chatId, User user, MyBot bot) {
        int page = adminAllUsersPage.getOrDefault(user.getId(), 0);
        int pageSize = 10;

        List<User> allUsers = userService.findAllActiveUsers();
        int totalUsers = allUsers.size();
        int totalPages = (int) Math.ceil((double) totalUsers / pageSize);

        System.out.println("DEBUG showAllUsers: page=" + page + ", totalUsers=" + totalUsers + ", totalPages=" + totalPages);

        // Корректируем страницу, если она вышла за пределы
        if (page >= totalPages && totalPages > 0) {
            page = totalPages - 1;
            adminAllUsersPage.put(user.getId(), page);
        }

        // Получаем пользователей для текущей страницы
        List<User> pageUsers = allUsers.stream()
                .sorted((u1, u2) -> u2.getCreatedAt().compareTo(u1.getCreatedAt())) // новые сначала
                .skip(page * pageSize)
                .limit(pageSize)
                .collect(Collectors.toList());

        StringBuilder message = new StringBuilder();

        if (pageUsers.isEmpty()) {
            message.append("📭 Нет пользователей в системе");
        } else {
            message.append(String.format("👥 Все пользователи (стр. %d/%d):\n\n", page + 1, totalPages));

            for (int i = 0; i < pageUsers.size(); i++) {
                User u = pageUsers.get(i);
                String username = u.getUsername() != null ? "@" + u.getUsername() : "нет_username";
                String fullName = u.getFirstName() + (u.getLastName() != null ? " " + u.getLastName() : "");

                message.append(String.format("""
                            👤 %s
                            🆔 ID: %d | %s
                            📅 Регистрация: %s
                            📊 Заявок: %d | 💰 Обменов: %.2f ₽
                            --------------------
                            """,
                        fullName,
                        u.getId(),
                        username,
                        u.getCreatedAt().format(DateTimeFormatter.ofPattern("dd.MM.yy HH:mm")),
                        u.getTotalApplications(),
                        u.getTotalBuyAmount().add(u.getTotalSellAmount())
                ));
            }
        }

        // Создаем клавиатуру с пагинацией
        System.out.println("DEBUG showAllUsers: Creating keyboard with page=" + page + ", totalPages=" + totalPages);
        InlineKeyboardMarkup inlineKeyboard = createAdminUsersPaginatedKeyboard(page, totalPages, "all_users");
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message.toString(), inlineKeyboard);
        lastMessageId.put(chatId, messageId);
    }

    /**
     * Показывает последних 5 пользователей
     */
    private void showRecentUsers(Long chatId, User user, MyBot bot) {
        List<User> recentUsers = userService.findRecentUsers();

        StringBuilder message = new StringBuilder();
        message.append("🆕 Последние зарегистрированные пользователи:\n\n");

        if (recentUsers.isEmpty()) {
            message.append("📭 Нет пользователей в системе");
        } else {
            for (int i = 0; i < recentUsers.size(); i++) {
                User u = recentUsers.get(i);
                String username = u.getUsername() != null ? "@" + u.getUsername() : "нет_username";
                String fullName = u.getFirstName() + (u.getLastName() != null ? " " + u.getLastName() : "");

                message.append(String.format("""
                            👤 %s
                            🆔 ID: %d | %s
                            📅 Регистрация: %s
                            📊 Заявок: %d | 💰 Обменов: %.2f ₽
                            --------------------
                            """,
                        fullName,
                        u.getId(),
                        username,
                        u.getCreatedAt().format(DateTimeFormatter.ofPattern("dd.MM.yy HH:mm")),
                        u.getTotalApplications(),
                        u.getTotalBuyAmount().add(u.getTotalSellAmount())
                ));
            }
        }

        InlineKeyboardMarkup inlineKeyboard = createBackToAdminUsersMenuKeyboard();
        int messageId = bot.sendMessageWithInlineKeyboard(chatId, message.toString(), inlineKeyboard);
        lastMessageId.put(chatId, messageId);
    }

    /**
     * Обрабатывает отображение всех пользователей
     */
    private void processAdminViewAllUsers(Long chatId, User user, String text, MyBot bot) {
        if ("🔙 Назад".equals(text)) {
            user.setState(UserState.ADMIN_USERS_MENU);
            userService.update(user);
            showAdminUsersMenu(chatId, bot);
        } else {
            showAllUsers(chatId, user, bot);
        }
    }

    /**
     * Обрабатывает отображение последних пользователей
     */
    private void processAdminViewRecentUsers(Long chatId, User user, String text, MyBot bot) {
        if ("🔙 Назад".equals(text)) {
            user.setState(UserState.ADMIN_USERS_MENU);
            userService.update(user);
            showAdminUsersMenu(chatId, bot);
        } else {
            showRecentUsers(chatId, user, bot);
        }
    }

    /**
     * Создает клавиатуру с пагинацией для пользователей
     */
    private InlineKeyboardMarkup createAdminUsersPaginatedKeyboard(int currentPage, int totalPages, String type) {
        System.out.println("DEBUG createAdminUsersPaginatedKeyboard: currentPage=" + currentPage + ", totalPages=" + totalPages + ", type=" + type);
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // Ряд с информацией о странице
        if (totalPages > 1) {
            List<InlineKeyboardButton> infoRow = new ArrayList<>();
            InlineKeyboardButton infoButton = new InlineKeyboardButton();
            infoButton.setText(String.format("📄 %d/%d", currentPage + 1, totalPages));
            infoButton.setCallbackData("inline_admin_users_page_info");
            infoRow.add(infoButton);
            rows.add(infoRow);
        }

        // Ряд с навигацией
        if (totalPages > 1) {
            List<InlineKeyboardButton> navRow = new ArrayList<>();

            if (currentPage > 0) {
                InlineKeyboardButton prevButton = new InlineKeyboardButton();
                prevButton.setText("⬅️ Назад");
                prevButton.setCallbackData("inline_admin_users_prev_all_users");
                navRow.add(prevButton);
            }

            if (currentPage < totalPages - 1) {
                InlineKeyboardButton nextButton = new InlineKeyboardButton();
                nextButton.setText("Вперед ➡️");
                nextButton.setCallbackData("inline_admin_users_next_all_users");
                navRow.add(nextButton);
            }

            if (!navRow.isEmpty()) {
                rows.add(navRow);
            }
        }

        // Ряд с кнопкой назад
        List<InlineKeyboardButton> backRow = new ArrayList<>();
        InlineKeyboardButton backButton = new InlineKeyboardButton();
        backButton.setText("🔙 Назад");
        backButton.setCallbackData("inline_admin_users_back");
        backRow.add(backButton);
        rows.add(backRow);

        markup.setKeyboard(rows);
        return markup;
    }

    /**
     * Создает клавиатуру для возврата в меню пользователей
     */
    private InlineKeyboardMarkup createBackToAdminUsersMenuKeyboard() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        List<InlineKeyboardButton> row = new ArrayList<>();
        InlineKeyboardButton backButton = new InlineKeyboardButton();
        backButton.setText("🔙 Назад");
        backButton.setCallbackData("inline_admin_users_back");
        row.add(backButton);
        rows.add(row);

        markup.setKeyboard(rows);
        return markup;
    }

    /**
     * Отправляет уведомление админам о новом пользователе
     */
    private void sendNewUserNotificationToAdmins(User user, MyBot bot) {
        try {
            String notification = String.format(
                "👤 Новый пользователь!\n\n" +
                "🆔 ID: %d\n" +
                "👤 Имя: %s\n" +
                "📝 Username: @%s\n" +
                "📅 Дата регистрации: %s\n" +
                "📊 Всего пользователей: %d",
                user.getTelegramId(),
                user.getFirstName() + (user.getLastName() != null ? " " + user.getLastName() : ""),
                user.getUsername() != null ? user.getUsername() : "не указан",
                user.getCreatedAt() != null ? user.getCreatedAt().toString() : "неизвестно",
                userService.getActiveUsersCount()
            );

            for (Long adminId : adminConfig.getAdminUserIds()) {
                try {
                    bot.sendMessage(adminId, notification);
                } catch (Exception e) {
                    System.err.println("Не удалось отправить уведомление админу " + adminId + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("Ошибка при отправке уведомления о новом пользователе: " + e.getMessage());
        }
    }

}