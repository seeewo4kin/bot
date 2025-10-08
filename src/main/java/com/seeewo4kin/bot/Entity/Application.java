package com.seeewo4kin.bot.Entity;

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
    private ValueType userValueGetType;  // Должен быть ValueType

    @Enumerated(EnumType.STRING)
    private ValueType userValueGiveType; // Должен быть ValueType

    private double userValueGetValue;
    private double userValueGiveValue;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public ValueType getUserValueGetType() {
        return userValueGetType;
    }

    public void setUserValueGetType(ValueType userValueGetType) {
        this.userValueGetType = userValueGetType;
    }

    public ValueType getUserValueGiveType() {
        return userValueGiveType;
    }

    public void setUserValueGiveType(ValueType userValueGiveType) {
        this.userValueGiveType = userValueGiveType;
    }

    public double getUserValueGetValue() {
        return userValueGetValue;
    }

    public void setUserValueGetValue(double userValueGetValue) {
        this.userValueGetValue = userValueGetValue;
    }

    public double getUserValueGiveValue() {
        return userValueGiveValue;
    }

    public void setUserValueGiveValue(double userValueGiveValue) {
        this.userValueGiveValue = userValueGiveValue;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }
}