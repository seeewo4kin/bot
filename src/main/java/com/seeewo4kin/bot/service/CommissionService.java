package com.seeewo4kin.bot.service;

import com.seeewo4kin.bot.Config.CommissionConfig;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

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

        return commissionConfig.calculateCommission(amount);
    }

    public BigDecimal calculateTotalWithCommission(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        return commissionConfig.calculateTotalWithCommission(amount);
    }

    public BigDecimal calculateTotalWithoutCommission(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal commission = calculateCommission(amount);
        return amount.subtract(commission);
    }

    public BigDecimal getCommissionPercent(BigDecimal amount) {
        if (amount == null) {
            return BigDecimal.valueOf(5.0); // дефолтное значение
        }

        return commissionConfig.getCommissionPercent(amount);
    }
}
