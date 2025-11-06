package com.seeewo4kin.bot.Entity;

import com.seeewo4kin.bot.Enums.UserState;
import jakarta.persistence.*;
import lombok.Data;

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

    // Бонусный баланс для использования в заявках
    private Double bonusBalance = 200.0; // Начальный бонус 200 рублей

    // Реферальный баланс для вывода
    private Double referralBalance = 0.0;

    @Column(name = "used_bonus_balance")
    private Double usedBonusBalance = 0.0;

    // Поле для хранения использованного реферального кода
    private String usedReferralCode;

    @Enumerated(EnumType.STRING)
    private UserState state = UserState.START;

    // Статистика
    private Integer completedBuyApplications = 0;
    private Integer completedSellApplications = 0;
    private Integer totalApplications = 0;
    private Double totalBuyAmount = 0.0;
    private Double totalSellAmount = 0.0;
    private Double totalCommissionPaid = 0.0;

    // Реферальная система
    @ManyToOne
    @JoinColumn(name = "invited_by_id")
    private User invitedBy;

    private LocalDateTime invitedAt;

    // Исправим отношение на OneToOne
    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private ReferralStats referralStats;

    // Добавим связь с реферальными кодами
    @OneToMany(mappedBy = "owner", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<ReferralCode> referralCodes = new ArrayList<>();

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (this.referralStats == null) {
            this.referralStats = new ReferralStats();
            this.referralStats.setUser(this);
        }
    }

    // Геттеры для статистики рефералов
    public Integer getReferralCount() {
        return referralStats != null ? referralStats.getLevel1Count() : 0;
    }

    public Double getReferralEarnings() {
        return referralStats != null ? referralStats.getTotalEarned() : 0.0;
    }

    public boolean hasUsedReferralCode() {
        return usedReferralCode != null && !usedReferralCode.trim().isEmpty();
    }

    public Double getUsedBonusBalance() {
        return usedBonusBalance != null ? usedBonusBalance : 0.0;
    }

    public void setUsedBonusBalance(Double usedBonusBalance) {
        this.usedBonusBalance = usedBonusBalance;
    }
}