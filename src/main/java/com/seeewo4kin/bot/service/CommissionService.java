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
        // Получаем процент комиссии для данной суммы
        BigDecimal commissionPercent = commissionConfig.getCommissionPercent(amount);

        // Рассчитываем комиссию: amount * (commissionPercent / 100)
        BigDecimal commission = amount.multiply(commissionPercent)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        System.out.println("DEBUG: Commission calculation - Amount: " + amount +
                ", Percent: " + commissionPercent + "%, Commission: " + commission);

        return commission;
    }

    public BigDecimal calculateTotalWithCommission(BigDecimal amount) {
        BigDecimal commission = calculateCommission(amount);
        BigDecimal total = amount.add(commission);

        System.out.println("DEBUG: Total with commission - Amount: " + amount +
                ", Total: " + total);

        return total;
    }

    public BigDecimal calculateTotalWithoutCommission(BigDecimal amount) {
        // Для продажи: сумма которую получит пользователь после вычета комиссии
        BigDecimal commission = calculateCommission(amount);
        BigDecimal total = amount.subtract(commission);

        System.out.println("DEBUG: Total without commission - Amount: " + amount +
                ", Total: " + total);

        return total;
    }

    public BigDecimal getCommissionPercent(BigDecimal amount) {
        return commissionConfig.getCommissionPercent(amount);
    }
}