package com.seeewo4kin.bot.Entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal; // ИЗМЕНЕНО
import java.time.LocalDateTime;

@Entity
@Table(name = "referral_stats")
@Data
public class ReferralStats {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Исправим отношение на OneToOne
    @OneToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    private Integer level1Count = 0;
    private Integer level2Count = 0;
    private Integer activeReferrals = 0;
    private Integer activeLast30DaysL1 = 0;
    private Integer activeLast30DaysL2 = 0;

    // ИЗМЕНЕНО: double на BigDecimal
    @Column(precision = 19, scale = 8)
    private BigDecimal totalExchangeAmount = BigDecimal.ZERO;
    private Integer totalExchangeCount = 0;

    // ИЗМЕНЕНО: double на BigDecimal
    @Column(precision = 19, scale = 8)
    private BigDecimal monthlyExchangeAmount = BigDecimal.ZERO;
    private Integer monthlyExchangeCount = 0;

    private BigDecimal totalEarned = BigDecimal.ZERO;

    // ИЗМЕНЕНО: double на BigDecimal
    @Column(precision = 19, scale = 8)
    private BigDecimal referralBalance = BigDecimal.ZERO;

    private LocalDateTime statsUpdatedAt;

    @PrePersist
    protected void onCreate() {
        statsUpdatedAt = LocalDateTime.now();
    }
}