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
            @Value("${bot.commission.range2.percent:10}") double range2Percent,
            @Value("${bot.commission.range3.min:3000}") double range3Min,
            @Value("${bot.commission.range3.max:4999}") double range3Max,
            @Value("${bot.commission.range3.percent:15}") double range3Percent,
            @Value("${bot.commission.range4.min:5000}") double range4Min,
            @Value("${bot.commission.range4.max:9999}") double range4Max,
            @Value("${bot.commission.range4.percent:20}") double range4Percent,
            @Value("${bot.commission.range5.min:10000}") double range5Min,
            @Value("${bot.commission.range5.max:14999}") double range5Max,
            @Value("${bot.commission.range5.percent:25}") double range5Percent,
            @Value("${bot.commission.range6.min.min:15000}") double range6Min,
            @Value("${bot.commission.range6.max.max:19999}") double range6Max,
            @Value("${bot.commission.range6.percent:25}") double range6Percent,
            @Value("${bot.commission.range7.min:20000}") double range7Min,
            @Value("${bot.commission.range7.max:24999}") double range7Max,
            @Value("${bot.commission.range7.percent:25}") double range7Percent,
            @Value("${bot.commission.range8.min:25000}") double range8Min,
            @Value("${bot.commission.range8.max:29999}") double range8Max,
            @Value("${bot.commission.range8.percent:25}") double range8Percent,
            @Value("${bot.commission.range9.min:30000}") double range9Min,
            @Value("${bot.commission.range9.percent:30}") double range9Percent) {

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