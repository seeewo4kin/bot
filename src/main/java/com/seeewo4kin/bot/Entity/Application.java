package com.seeewo4kin.bot.Entity;

import com.seeewo4kin.bot.Enums.ApplicationStatus;
import com.seeewo4kin.bot.Enums.ValueType;
import jakarta.persistence.*;
import lombok.Data;
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

    private double userValueGetValue;
    private double userValueGiveValue;

    private double calculatedGiveValue;
    private double calculatedGetValue;

    @Enumerated(EnumType.STRING)
    private ApplicationStatus status = ApplicationStatus.FREE;

    @ManyToOne
    @JoinColumn(name = "coupon_id")
    private Coupon appliedCoupon;

    private Double finalAmountAfterDiscount;

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
        expiresAt = LocalDateTime.now().plusMinutes(5); // 5 минут
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