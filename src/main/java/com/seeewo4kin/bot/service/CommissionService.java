package com.seeewo4kin.bot.service;

import com.seeewo4kin.bot.Config.CommissionConfig;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class CommissionService {
    private final CommissionConfig commissionConfig;

    public CommissionService(CommissionConfig commissionConfig) {
        this.commissionConfig = commissionConfig;
    }

    public BigDecimal calculateCommission(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal percent = getCommissionPercent(amount);
        return amount.multiply(percent).divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
    }

    public BigDecimal calculateTotalWithCommission(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal commission = calculateCommission(amount);
        return amount.add(commission);
    }

    public BigDecimal getCommissionPercent(BigDecimal amount) {
        if (amount == null) {
            return BigDecimal.valueOf(5.0); // дефолтное значение
        }

        // Логика расчета процента комиссии
        if (amount.compareTo(BigDecimal.valueOf(1000)) >= 0 && amount.compareTo(BigDecimal.valueOf(1999)) <= 0) {
            return BigDecimal.valueOf(5.0);
        } else if (amount.compareTo(BigDecimal.valueOf(2000)) >= 0 && amount.compareTo(BigDecimal.valueOf(2999)) <= 0) {
            return BigDecimal.valueOf(4.5);
        } else if (amount.compareTo(BigDecimal.valueOf(3000)) >= 0 && amount.compareTo(BigDecimal.valueOf(4999)) <= 0) {
            return BigDecimal.valueOf(4.0);
        } else if (amount.compareTo(BigDecimal.valueOf(5000)) >= 0) {
            return BigDecimal.valueOf(3.5);
        } else {
            return BigDecimal.valueOf(5.0);
        }
    }
}
