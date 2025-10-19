package com.seeewo4kin.bot.Entity;

import com.seeewo4kin.bot.Enums.ApplicationStatus;
import com.seeewo4kin.bot.Enums.ValueType;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "applications")
@Data
public class Application {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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
    private ApplicationStatus status = ApplicationStatus.FREE; // Новое поле

    @ManyToOne
    @JoinColumn(name = "coupon_id")
    private Coupon appliedCoupon;

    private Double finalAmountAfterDiscount;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}