package com.seeewo4kin.bot.Entity;

import jakarta.persistence.*;
import lombok.Data;
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

    private Double rewardPercent; // Процент от заявок реферала

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}