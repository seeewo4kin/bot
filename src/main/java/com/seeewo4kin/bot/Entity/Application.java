package com.seeewo4kin.bot.Entity;

import com.seeewo4kin.bot.Enums.ApplicationStatus;
import com.seeewo4kin.bot.Enums.ValueType;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Entity
@Table(name = "applications")
@Data
public class Application {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String uuid = UUID.randomUUID().toString();

    private String title;
    private String description;

    @Enumerated(EnumType.STRING)
    private ValueType userValueGetType;

    @Enumerated(EnumType.STRING)
    private ValueType userValueGiveType;

    @Column(name = "user_value_get_value", precision = 19, scale = 8)
    private BigDecimal userValueGetValue;
    @Column(name = "user_value_give_value", precision = 19, scale = 8)
    private BigDecimal userValueGiveValue;

    @Column(name = "calculated_give_value", precision = 19, scale = 8)
    private BigDecimal calculatedGiveValue;
    @Column(name = "calculated_get_value", precision = 19, scale = 8)
    private BigDecimal calculatedGetValue;

    @Column(name = "commission_amount", precision = 19, scale = 8)
    private BigDecimal commissionAmount;

    @Column(name = "used_bonus_balance", precision = 19, scale = 8)
    private BigDecimal usedBonusBalance = BigDecimal.ZERO;
    private BigDecimal referralRewardLevel1 = BigDecimal.ZERO;
    private BigDecimal referralRewardLevel2 = BigDecimal.ZERO;
    private long adminId;

    @Enumerated(EnumType.STRING)
    private ApplicationStatus status = ApplicationStatus.FREE;

    @ManyToOne
    @JoinColumn(name = "coupon_id")
    private Coupon appliedCoupon;

    @Column(name = "final_amount_after_discount", precision = 19, scale = 8)
    private BigDecimal finalAmountAfterDiscount;

    private Boolean isVip = false;
    private String walletAddress;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    // Новое поле для хранения ID сообщения в Telegram
    private Integer telegramMessageId;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        expiresAt = LocalDateTime.now().plusMinutes(40); // 5 минут
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    public String getFormattedExpiresAt() {
        return expiresAt.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) + " по МСК";
    }

    public long getMinutesLeft() {
        return Math.max(0, java.time.Duration.between(LocalDateTime.now(), expiresAt).toMinutes());
    }
}