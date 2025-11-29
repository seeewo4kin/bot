package com.seeewo4kin.bot.Entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal; // ИЗМЕНЕНО
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "referral_codes")
@Data
public class ReferralCode {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String code;

    @ManyToOne
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    private String description;
    private Boolean isActive = true;

    @OneToOne
    @JoinColumn(name = "coupon_id")
    private Coupon rewardCoupon;

    @OneToMany(mappedBy = "referralCode", cascade = CascadeType.ALL)
    private List<ReferralUsage> usages = new ArrayList<>();

    // ИЗМЕНЕНО: double на BigDecimal
    @Column(precision = 5, scale = 2)
    private BigDecimal rewardPercent; // Процент от заявок реферала

    private LocalDateTime expiresAt;
    private Integer usedCount = 0;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // Геттеры и сеттеры для совместимости
    public Long getOwnerId() {
        return owner != null ? owner.getId() : null;
    }

    public void setOwnerId(Long ownerId) {
        // Не используется, так как owner_id управляется связью с owner
        // Если нужно установить owner по ID, это должно делаться отдельно
    }

    public User getUser() {
        return owner;
    }

    public void setUser(User user) {
        this.owner = user;
    }
}