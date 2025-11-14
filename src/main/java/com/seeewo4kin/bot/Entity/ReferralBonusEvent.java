package com.seeewo4kin.bot.Entity;

import com.seeewo4kin.bot.Enums.BonusType;
import jakarta.persistence.*;
import jakarta.persistence.PrePersist;
import lombok.Data;

import java.math.BigDecimal; // ИЗМЕНЕНО
import java.time.LocalDateTime;

@Entity
@Table(name = "referral_bonus_events")
@Data
public class ReferralBonusEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    private BonusType bonusType;

    // ИЗМЕНЕНО: double на BigDecimal
    @Column(precision = 19, scale = 8)
    private BigDecimal amount = BigDecimal.ZERO;

    private String description;
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}