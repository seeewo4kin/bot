package com.seeewo4kin.bot.Entity;

import jakarta.persistence.*;
import lombok.Data;
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

    private Double totalExchangeAmount = 0.0;
    private Integer totalExchangeCount = 0;

    private Double monthlyExchangeAmount = 0.0;
    private Integer monthlyExchangeCount = 0;

    private Double totalEarned = 0.0;
    private Double referralBalance = 0.0;

    private LocalDateTime statsUpdatedAt;

    @PrePersist
    protected void onCreate() {
        statsUpdatedAt = LocalDateTime.now();
    }
}