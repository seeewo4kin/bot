package com.seeewo4kin.bot.Entity;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "referral_stats")
public class ReferralStats {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Добавьте эту связь с пользователем
    @OneToOne
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    private int level1Count;
    private int level2Count;
    private int activeReferrals;
    private BigDecimal totalExchangeAmount;
    private int totalExchangeCount;
    private BigDecimal monthlyExchangeAmount;
    private int monthlyExchangeCount;
    private BigDecimal totalEarned;
    private BigDecimal level1Percent;
    private BigDecimal level2Percent;

    // Конструкторы
    public ReferralStats() {
        this.level1Count = 0;
        this.level2Count = 0;
        this.activeReferrals = 0;
        this.totalExchangeAmount = BigDecimal.ZERO;
        this.totalExchangeCount = 0;
        this.monthlyExchangeAmount = BigDecimal.ZERO;
        this.monthlyExchangeCount = 0;
        this.totalEarned = BigDecimal.ZERO;
        this.level1Percent = BigDecimal.valueOf(3.0);
        this.level2Percent = BigDecimal.valueOf(0.5);
    }

    // Геттеры и сеттеры
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public int getLevel1Count() {
        return level1Count;
    }

    public void setLevel1Count(int level1Count) {
        this.level1Count = level1Count;
    }

    public int getLevel2Count() {
        return level2Count;
    }

    public void setLevel2Count(int level2Count) {
        this.level2Count = level2Count;
    }

    public int getActiveReferrals() {
        return activeReferrals;
    }

    public void setActiveReferrals(int activeReferrals) {
        this.activeReferrals = activeReferrals;
    }

    public BigDecimal getTotalExchangeAmount() {
        return totalExchangeAmount;
    }

    public void setTotalExchangeAmount(BigDecimal totalExchangeAmount) {
        this.totalExchangeAmount = totalExchangeAmount;
    }

    public int getTotalExchangeCount() {
        return totalExchangeCount;
    }

    public void setTotalExchangeCount(int totalExchangeCount) {
        this.totalExchangeCount = totalExchangeCount;
    }

    public BigDecimal getMonthlyExchangeAmount() {
        return monthlyExchangeAmount;
    }

    public void setMonthlyExchangeAmount(BigDecimal monthlyExchangeAmount) {
        this.monthlyExchangeAmount = monthlyExchangeAmount;
    }

    public int getMonthlyExchangeCount() {
        return monthlyExchangeCount;
    }

    public void setMonthlyExchangeCount(int monthlyExchangeCount) {
        this.monthlyExchangeCount = monthlyExchangeCount;
    }

    public BigDecimal getTotalEarned() {
        return totalEarned;
    }

    public void setTotalEarned(BigDecimal totalEarned) {
        this.totalEarned = totalEarned;
    }

    public BigDecimal getLevel1Percent() {
        return level1Percent;
    }

    public void setLevel1Percent(BigDecimal level1Percent) {
        this.level1Percent = level1Percent;
    }

    public BigDecimal getLevel2Percent() {
        return level2Percent;
    }

    public void setLevel2Percent(BigDecimal level2Percent) {
        this.level2Percent = level2Percent;
    }
}