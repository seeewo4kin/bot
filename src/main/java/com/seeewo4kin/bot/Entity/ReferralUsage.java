package com.seeewo4kin.bot.Entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "referral_usages")
@Data
public class ReferralUsage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "referral_code_id", nullable = false)
    private ReferralCode referralCode;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User usedBy;

    @Column(name = "used_at", updatable = false)
    private LocalDateTime usedAt;

    private Boolean rewardGiven = false;

    @PrePersist
    protected void onCreate() {
        usedAt = LocalDateTime.now();
    }
}