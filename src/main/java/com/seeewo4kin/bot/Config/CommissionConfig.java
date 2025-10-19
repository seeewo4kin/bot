package com.seeewo4kin.bot.Config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import java.util.Map;
import java.util.TreeMap;

@Configuration
public class CommissionConfig {

    private final TreeMap<Double, Double> commissionRanges = new TreeMap<>();

    public CommissionConfig(
            @Value("${bot.commission.range1.min:1000}") double range1Min,
            @Value("${bot.commission.range1.max:1999}") double range1Max,
            @Value("${bot.commission.range1.percent:5}") double range1Percent,
            @Value("${bot.commission.range2.min:2000}") double range2Min,
            @Value("${bot.commission.range2.max:2999}") double range2Max,
            @Value("${bot.commission.range2.percent:4}") double range2Percent,
            @Value("${bot.commission.range3.min:3000}") double range3Min,
            @Value("${bot.commission.range3.max:4999}") double range3Max,
            @Value("${bot.commission.range3.percent:3}") double range3Percent,
            @Value("${bot.commission.range4.min:5000}") double range4Min,
            @Value("${bot.commission.range4.percent:2}") double range4Percent) {

        commissionRanges.put(range1Min, range1Percent);
        commissionRanges.put(range2Min, range2Percent);
        commissionRanges.put(range3Min, range3Percent);
        commissionRanges.put(range4Min, range4Percent);
    }

    public double getCommissionPercent(double amount) {
        Map.Entry<Double, Double> floorEntry = commissionRanges.floorEntry(amount);
        if (floorEntry != null) {
            return floorEntry.getValue();
        }
        // Если сумма меньше минимальной, используем самую высокую комиссию
        return commissionRanges.firstEntry().getValue();
    }

    public double calculateCommission(double amount) {
        double percent = getCommissionPercent(amount);
        return amount * percent / 100;
    }

    public double calculateTotalWithCommission(double amount) {
        return amount + calculateCommission(amount);
    }

    public TreeMap<Double, Double> getCommissionRanges() {
        return new TreeMap<>(commissionRanges);
    }

    public void updateCommissionRange(double minAmount, double percent) {
        commissionRanges.put(minAmount, percent);
    }
}