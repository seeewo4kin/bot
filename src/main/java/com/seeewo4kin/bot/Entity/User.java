package com.seeewo4kin.bot.Entity;

import com.seeewo4kin.bot.Enums.CryptoCurrency;
import com.seeewo4kin.bot.Enums.UserState;
import com.seeewo4kin.bot.Enums.ValueType;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "users")
@Data
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private Long telegramId;

    private String username;
    private String firstName;
    private String lastName;

    // Реферальные поля
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invited_by_id")
    private User invitedBy;

    @Column(name = "invited_at")
    private LocalDateTime invitedAt;

    @Column(name = "referral_count")
    private Integer referralCount = 0;

    @Column(name = "referral_earnings", precision = 19, scale = 8)
    private BigDecimal referralEarnings = BigDecimal.ZERO;

    // УДАЛЕНО дублирование: referralBalance объявлен только один раз ниже

    @Enumerated(EnumType.ORDINAL)
    @Column(name = "user_value_get_type")
    private ValueType userValueGetType;

    @Enumerated(EnumType.ORDINAL)
    @Column(name = "user_value_give_type")
    private ValueType userValueGiveType;

    @Enumerated(EnumType.STRING)
    @Column(name = "crypto_currency")
    private CryptoCurrency cryptoCurrency;

    @Column(name = "bonus_balance", precision = 19, scale = 8)
    private BigDecimal bonusBalance = BigDecimal.valueOf(200);

    // Реферальный баланс для вывода (ОСТАВЛЕН ТОЛЬКО ОДИН РАЗ)
    @Column(name = "referral_balance", precision = 19, scale = 8)
    private BigDecimal referralBalance = BigDecimal.ZERO;

    @Column(name = "used_bonus_balance")
    private BigDecimal usedBonusBalance = BigDecimal.ZERO;

    // Поле для хранения использованного реферального кода
    private String usedReferralCode;

    @Enumerated(EnumType.STRING)
    private UserState state = UserState.START;

    // Статистика
    private Integer completedBuyApplications = 0;
    private Integer completedSellApplications = 0;
    private Integer totalApplications = 0;

    @Column(name = "total_buy_amount", precision = 19, scale = 8)
    private BigDecimal totalBuyAmount = BigDecimal.ZERO;

    @Column(name = "total_sell_amount", precision = 19, scale = 8)
    private BigDecimal totalSellAmount = BigDecimal.ZERO;

    @Column(precision = 19, scale = 8)
    private BigDecimal totalCommissionPaid = BigDecimal.ZERO;

    // Связь с реферальными кодами - ИСПРАВЛЕНО: mappedBy = "user"
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<ReferralCode> referralCodes = new ArrayList<>();

    // ДОБАВЛЕНО: поле для статистики рефералов (если нужно хранить в БД)
    @Embedded
    private ReferralStatsEmbedded referralStats;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        // Инициализация embedded статистики, если используется
        if (this.referralStats == null) {
            this.referralStats = new ReferralStatsEmbedded();
        }
    }

    @Column(name = "is_active")
    private Boolean isActive = true;


    // ИСПРАВЛЕНЫ геттеры для статистики рефералов
    public Integer getReferralCount() {
        return referralCount != null ? referralCount : 0;
    }

    public BigDecimal getReferralEarnings() {
        return referralEarnings != null ? referralEarnings : BigDecimal.ZERO;
    }

    public boolean hasUsedReferralCode() {
        return usedReferralCode != null && !usedReferralCode.trim().isEmpty();
    }

    // Геттер для referralBalance
    public BigDecimal getReferralBalance() {
        return referralBalance != null ? referralBalance : BigDecimal.ZERO;
    }

    // Геттер для bonusBalance
    public BigDecimal getBonusBalance() {
        return bonusBalance != null ? bonusBalance : BigDecimal.ZERO;
    }

    // ДОБАВЛЕНЫ отсутствующие геттеры и сеттеры для Lombok
    // (Lombok должен генерировать их автоматически, но на всякий случай)

    public void setBonusBalance(BigDecimal bonusBalance) {
        this.bonusBalance = bonusBalance != null ? bonusBalance : BigDecimal.ZERO;
    }

    public void setReferralBalance(BigDecimal referralBalance) {
        this.referralBalance = referralBalance != null ? referralBalance : BigDecimal.ZERO;
    }

    public void setReferralEarnings(BigDecimal referralEarnings) {
        this.referralEarnings = referralEarnings != null ? referralEarnings : BigDecimal.ZERO;
    }

    public void setReferralCount(Integer referralCount) {
        this.referralCount = referralCount != null ? referralCount : 0;
    }
}

// ДОБАВЛЕН embedded класс для хранения статистики рефералов в БД
@Embeddable
class ReferralStatsEmbedded {
    @Column(name = "level1_count")
    private Integer level1Count = 0;

    @Column(name = "level2_count")
    private Integer level2Count = 0;

    @Column(name = "active_referrals")
    private Integer activeReferrals = 0;

    @Column(name = "total_exchange_amount", precision = 19, scale = 8)
    private BigDecimal totalExchangeAmount = BigDecimal.ZERO;

    @Column(name = "total_exchange_count")
    private Integer totalExchangeCount = 0;

    @Column(name = "monthly_exchange_amount", precision = 19, scale = 8)
    private BigDecimal monthlyExchangeAmount = BigDecimal.ZERO;

    @Column(name = "monthly_exchange_count")
    private Integer monthlyExchangeCount = 0;

    // Геттеры и сеттеры
    public Integer getLevel1Count() {
        return level1Count != null ? level1Count : 0;
    }

    public void setLevel1Count(Integer level1Count) {
        this.level1Count = level1Count != null ? level1Count : 0;
    }

    public Integer getLevel2Count() {
        return level2Count != null ? level2Count : 0;
    }

    public void setLevel2Count(Integer level2Count) {
        this.level2Count = level2Count != null ? level2Count : 0;
    }

    public Integer getActiveReferrals() {
        return activeReferrals != null ? activeReferrals : 0;
    }

    public void setActiveReferrals(Integer activeReferrals) {
        this.activeReferrals = activeReferrals != null ? activeReferrals : 0;
    }

    public BigDecimal getTotalExchangeAmount() {
        return totalExchangeAmount != null ? totalExchangeAmount : BigDecimal.ZERO;
    }

    public void setTotalExchangeAmount(BigDecimal totalExchangeAmount) {
        this.totalExchangeAmount = totalExchangeAmount != null ? totalExchangeAmount : BigDecimal.ZERO;
    }

    public Integer getTotalExchangeCount() {
        return totalExchangeCount != null ? totalExchangeCount : 0;
    }

    public void setTotalExchangeCount(Integer totalExchangeCount) {
        this.totalExchangeCount = totalExchangeCount != null ? totalExchangeCount : 0;
    }

    public BigDecimal getMonthlyExchangeAmount() {
        return monthlyExchangeAmount != null ? monthlyExchangeAmount : BigDecimal.ZERO;
    }

    public void setMonthlyExchangeAmount(BigDecimal monthlyExchangeAmount) {
        this.monthlyExchangeAmount = monthlyExchangeAmount != null ? monthlyExchangeAmount : BigDecimal.ZERO;
    }

    public Integer getMonthlyExchangeCount() {
        return monthlyExchangeCount != null ? monthlyExchangeCount : 0;
    }

    public void setMonthlyExchangeCount(Integer monthlyExchangeCount) {
        this.monthlyExchangeCount = monthlyExchangeCount != null ? monthlyExchangeCount : 0;
    }
}