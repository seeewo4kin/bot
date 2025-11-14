package com.seeewo4kin.bot.Config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal; // ИЗМЕНЕНО
import java.math.RoundingMode; // ДОБАВЛЕНО
import java.util.Map;
import java.util.TreeMap;

@Configuration
public class CommissionConfig {

    // ИЗМЕНЕНО: Тип ключа и значения на BigDecimal
    private final TreeMap<BigDecimal, BigDecimal> commissionRanges = new TreeMap<>();

    public CommissionConfig(
            // ИЗМЕНЕНО: Все типы параметров на BigDecimal
            @Value("${bot.commission.range1.min:1000}") BigDecimal range1Min,
            @Value("${bot.commission.range1.max:1999}") BigDecimal range1Max,
            @Value("${bot.commission.range1.percent:5}") BigDecimal range1Percent,
            @Value("${bot.commission.range2.min:2000}") BigDecimal range2Min,
            @Value("${bot.commission.range2.max:2999}") BigDecimal range2Max,
            @Value("${bot.commission.range2.percent:10}") BigDecimal range2Percent,
            @Value("${bot.commission.range3.min:3000}") BigDecimal range3Min,
            @Value("${bot.commission.range3.max:4999}") BigDecimal range3Max,
            @Value("${bot.commission.range3.percent:15}") BigDecimal range3Percent,
            @Value("${bot.commission.range4.min:5000}") BigDecimal range4Min,
            @Value("${bot.commission.range4.max:9999}") BigDecimal range4Max,
            @Value("${bot.commission.range4.percent:20}") BigDecimal range4Percent,
            @Value("${bot.commission.range5.min:10000}") BigDecimal range5Min,
            @Value("${bot.commission.range5.max:14999}") BigDecimal range5Max,
            @Value("${bot.commission.range5.percent:25}") BigDecimal range5Percent,
            @Value("${bot.commission.range6.min.min:15000}") BigDecimal range6Min,
            @Value("${bot.commission.range6.max.max:19999}") BigDecimal range6Max,
            @Value("${bot.commission.range6.percent:25}") BigDecimal range6Percent,
            @Value("${bot.commission.range7.min:20000}") BigDecimal range7Min,
            @Value("${bot.commission.range7.max:24999}") BigDecimal range7Max,
            @Value("${bot.commission.range7.percent:25}") BigDecimal range7Percent,
            @Value("${bot.commission.range8.min:25000}") BigDecimal range8Min,
            @Value("${bot.commission.range8.max:29999}") BigDecimal range8Max,
            @Value("${bot.commission.range8.percent:25}") BigDecimal range8Percent,
            @Value("${bot.commission.range9.min:30000}") BigDecimal range9Min,
            @Value("${bot.commission.range9.percent:30}") BigDecimal range9Percent) {

        commissionRanges.put(range1Min, range1Percent);
        commissionRanges.put(range2Min, range2Percent);
        commissionRanges.put(range3Min, range3Percent);
        commissionRanges.put(range4Min, range4Percent);
        commissionRanges.put(range5Min, range5Percent);
        commissionRanges.put(range6Min, range6Percent);
        commissionRanges.put(range7Min, range7Percent);
        commissionRanges.put(range8Min, range8Percent);
        commissionRanges.put(range9Min, range9Percent);
    }

    // ИЗМЕНЕНО: Тип параметра и возвращаемого значения
    public BigDecimal getCommissionPercent(BigDecimal amount) {
        Map.Entry<BigDecimal, BigDecimal> floorEntry = commissionRanges.floorEntry(amount);
        if (floorEntry != null) {
            return floorEntry.getValue();
        }
        // Если сумма меньше минимальной, используем самую высокую комиссию
        return commissionRanges.firstEntry().getValue();
    }

    // ИЗМЕНЕНО: Тип параметра и возвращаемого значения + логика
    public BigDecimal calculateCommission(BigDecimal amount) {
        BigDecimal percent = getCommissionPercent(amount);
        // Используем безопасное деление BigDecimal
        return amount.multiply(percent).divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP);
    }

    // ИЗМЕНЕНО: Тип параметра и возвращаемого значения + логика
    public BigDecimal calculateTotalWithCommission(BigDecimal amount) {
        return amount.add(calculateCommission(amount));
    }

    // ИЗМЕНЕНО: Тип возвращаемого значения
    public TreeMap<BigDecimal, BigDecimal> getCommissionRanges() {
        return new TreeMap<>(commissionRanges);
    }

    // ИЗМЕНЕНО: Типы параметров
    public void updateCommissionRange(BigDecimal minAmount, BigDecimal percent) {
        commissionRanges.put(minAmount, percent);
    }
}