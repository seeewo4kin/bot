package com.seeewo4kin.bot.service;

import com.seeewo4kin.bot.Config.CommissionConfig;
import org.springframework.stereotype.Service;

@Service
public class CommissionService {
    private final CommissionConfig commissionConfig;

    public CommissionService(CommissionConfig commissionConfig) {
        this.commissionConfig = commissionConfig;
    }

    public double calculateCommission(double amount) {
        if (amount < 1000) {
            throw new IllegalArgumentException("Минимальная сумма заявки 1000 рублей");
        }
        return commissionConfig.calculateCommission(amount);
    }

    public double calculateTotalWithCommission(double amount) {
        if (amount < 1000) {
            throw new IllegalArgumentException("Минимальная сумма заявки 1000 рублей");
        }
        return commissionConfig.calculateTotalWithCommission(amount);
    }

    public double getCommissionPercent(double amount) {
        return commissionConfig.getCommissionPercent(amount);
    }
}