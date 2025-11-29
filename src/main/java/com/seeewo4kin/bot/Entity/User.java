package com.seeewo4kin.bot.Entity;

import com.seeewo4kin.bot.Enums.UserState;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal; // ИЗМЕНЕНО
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

    @Column(name = "bonus_balance", precision = 19, scale = 8)
    private BigDecimal bonusBalance = BigDecimal.valueOf(200);
    // Реферальный баланс для вывода
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
    @Column(name = "referral_earnings", precision = 19, scale = 8)
    private BigDecimal referralEarnings = BigDecimal.ZERO;

    // ИЗМЕНЕНО: double на BigDecimal
    @Column(precision = 19, scale = 8)
    private BigDecimal totalCommissionPaid = BigDecimal.ZERO;

    // Реферальная система
    @ManyToOne
    @JoinColumn(name = "invited_by_id")
    private User invitedBy;

    private LocalDateTime invitedAt;

    // Исправим отношение на embedded
    @Embedded
    private ReferralStatsEmbedded referralStats;

    // Добавим связь с реферальными кодами
    @OneToMany(mappedBy = "owner", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<ReferralCode> referralCodes = new ArrayList<>();

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (this.referralStats == null) {
            this.referralStats = new ReferralStatsEmbedded();
        }
    }

    @PostLoad
    protected void onLoad() {
        // Обеспечиваем инициализацию реферальной статистики при загрузке из БД
        if (this.referralStats != null) {
            this.referralStats.ensureInitialized();
        } else {
            this.referralStats = new ReferralStatsEmbedded();
        }
    }

    // Геттеры для статистики рефералов
    public Integer getReferralCount() {
        return referralStats != null ? referralStats.getLevel1CountSafe() : 0;
    }

    public BigDecimal getReferralEarnings() {
        return referralStats != null ? referralStats.getTotalEarnedSafe() : BigDecimal.ZERO;
    }

    public boolean hasUsedReferralCode() {
        return usedReferralCode != null && !usedReferralCode.trim().isEmpty();
    }

    public void setReferralCount(int count) {
        if (this.referralStats != null) {
            this.referralStats.setLevel1Count(count);
        }
    }

    public void setUsedBonusBalance(BigDecimal usedBonusBalance) {
        this.usedBonusBalance = usedBonusBalance;
    }
}