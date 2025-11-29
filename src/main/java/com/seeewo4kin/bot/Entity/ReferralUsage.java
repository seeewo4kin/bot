package com.seeewo4kin.bot.Entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "referral_usage")
public class ReferralUsage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referral_code_id", nullable = false)
    private ReferralCode referralCode;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    // Конструкторы
    public ReferralUsage() {
        this.usedAt = LocalDateTime.now();
    }

    public ReferralUsage(User user, ReferralCode referralCode) {
        this();
        this.user = user;
        this.referralCode = referralCode;
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

    public ReferralCode getReferralCode() {
        return referralCode;
    }

    public void setReferralCode(ReferralCode referralCode) {
        this.referralCode = referralCode;
    }

    public LocalDateTime getUsedAt() {
        return usedAt;
    }

    public void setUsedAt(LocalDateTime usedAt) {
        this.usedAt = usedAt;
    }
}