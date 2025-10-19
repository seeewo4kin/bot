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

    private Double bonusBalance = 0.0;


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
    private String usedReferralCode;
    private Double referralEarnings = 0.0;
    private Integer referralCount = 0;

    @OneToMany(mappedBy = "owner")
    private List<ReferralCode> referralCodes = new ArrayList<>();

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}