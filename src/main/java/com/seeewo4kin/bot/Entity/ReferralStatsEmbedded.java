package com.seeewo4kin.bot.Entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Data;

import java.math.BigDecimal;

@Embeddable
@Data
public class ReferralStatsEmbedded {
    private Integer level1Count = 0;
    private Integer level2Count = 0;
    private Integer activeReferrals = 0;
    private Integer activeLast30DaysL1 = 0;
    private Integer activeLast30DaysL2 = 0;
    private BigDecimal totalExchangeAmount = BigDecimal.ZERO;
    private Integer totalExchangeCount = 0;
    private BigDecimal monthlyExchangeAmount = BigDecimal.ZERO;
    private Integer monthlyExchangeCount = 0;
    private BigDecimal totalEarned = BigDecimal.ZERO;

    @Column(name = "referral_stats_balance", precision = 19, scale = 8)
    private BigDecimal referralBalance = BigDecimal.ZERO;

    // Отдельные объемы по уровням
    @Column(name = "level1_volume", precision = 19, scale = 8)
    private BigDecimal level1Volume = BigDecimal.ZERO;

    @Column(name = "level2_volume", precision = 19, scale = 8)
    private BigDecimal level2Volume = BigDecimal.ZERO;

    // Повышенные проценты за достижения
    @Column(name = "level1_bonus_percent", precision = 5, scale = 2)
    private BigDecimal level1BonusPercent = BigDecimal.ZERO;

    @Column(name = "level2_bonus_percent", precision = 5, scale = 2)
    private BigDecimal level2BonusPercent = BigDecimal.ZERO;

    // Флаги полученных бонусов (битовая маска)
    @Column(name = "received_bonuses_flags")
    private Long receivedBonusesFlags = 0L;

    // Конструктор по умолчанию для JPA
    public ReferralStatsEmbedded() {
        // Явная инициализация всех полей
        this.level1Count = 0;
        this.level2Count = 0;
        this.activeReferrals = 0;
        this.activeLast30DaysL1 = 0;
        this.activeLast30DaysL2 = 0;
        this.totalExchangeAmount = BigDecimal.ZERO;
        this.totalExchangeCount = 0;
        this.monthlyExchangeAmount = BigDecimal.ZERO;
        this.monthlyExchangeCount = 0;
        this.totalEarned = BigDecimal.ZERO;
        this.referralBalance = BigDecimal.ZERO;
        this.level1Volume = BigDecimal.ZERO;
        this.level2Volume = BigDecimal.ZERO;
        this.level1BonusPercent = BigDecimal.ZERO;
        this.level2BonusPercent = BigDecimal.ZERO;
        this.receivedBonusesFlags = 0L;
    }

    // Метод для безопасного получения level1Count
    public Integer getLevel1CountSafe() {
        return level1Count != null ? level1Count : 0;
    }
    public Integer getLevel2CountSafe() {
        return level2Count != null ? level2Count : 0;
    }


    // Метод для безопасного получения totalEarned
    public BigDecimal getTotalEarnedSafe() {
        return totalEarned != null ? totalEarned : BigDecimal.ZERO;
    }

    // Методы для безопасного получения объемов по уровням
    public BigDecimal getLevel1VolumeSafe() {
        return level1Volume != null ? level1Volume : BigDecimal.ZERO;
    }

    public BigDecimal getLevel2VolumeSafe() {
        return level2Volume != null ? level2Volume : BigDecimal.ZERO;
    }

    public Long getReceivedBonusesFlags() {
        return receivedBonusesFlags != null ? receivedBonusesFlags : 0L;
    }

    public void setReceivedBonusesFlags(Long receivedBonusesFlags) {
        this.receivedBonusesFlags = receivedBonusesFlags;
    }

    // Метод для обеспечения инициализации всех полей (защита от null значений)
    public void ensureInitialized() {
        if (this.level1Count == null) this.level1Count = 0;
        if (this.level2Count == null) this.level2Count = 0;
        if (this.activeReferrals == null) this.activeReferrals = 0;
        if (this.activeLast30DaysL1 == null) this.activeLast30DaysL1 = 0;
        if (this.activeLast30DaysL2 == null) this.activeLast30DaysL2 = 0;
        if (this.totalExchangeAmount == null) this.totalExchangeAmount = BigDecimal.ZERO;
        if (this.totalExchangeCount == null) this.totalExchangeCount = 0;
        if (this.monthlyExchangeAmount == null) this.monthlyExchangeAmount = BigDecimal.ZERO;
        if (this.monthlyExchangeCount == null) this.monthlyExchangeCount = 0;
        if (this.totalEarned == null) this.totalEarned = BigDecimal.ZERO;
        if (this.referralBalance == null) this.referralBalance = BigDecimal.ZERO;
        if (this.level1Volume == null) this.level1Volume = BigDecimal.ZERO;
        if (this.level2Volume == null) this.level2Volume = BigDecimal.ZERO;
        if (this.level1BonusPercent == null) this.level1BonusPercent = BigDecimal.ZERO;
        if (this.level2BonusPercent == null) this.level2BonusPercent = BigDecimal.ZERO;
        if (this.receivedBonusesFlags == null) this.receivedBonusesFlags = 0L;
    }
}
