package com.seeewo4kin.bot.Entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "coupons")
@Data
public class Coupon {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String code;

    private String description;
    @Column(name = "discount_percent", precision = 5, scale = 2)
    private BigDecimal discountPercent;
    @Column(name = "discount_amount", precision = 19, scale = 8)
    private BigDecimal discountAmount;
    private Boolean isActive = true;
    private LocalDateTime validUntil;

    // Ограничение использования
    private Integer usageLimit; // null = без ограничений
    private Integer usedCount = 0;

    // Привязка к пользователю (если купон персональный)
    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    private Boolean isUsed = false;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public boolean canBeUsed() {
        if (!isActive) return false;
        if (isUsed) return false;
        if (validUntil != null && LocalDateTime.now().isAfter(validUntil)) return false;
        if (usageLimit != null && usedCount >= usageLimit) return false;
        return true;
    }
}