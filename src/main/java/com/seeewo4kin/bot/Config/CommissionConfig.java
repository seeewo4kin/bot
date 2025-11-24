package com.seeewo4kin.bot.Config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.math.BigDecimal; // ИЗМЕНЕНО
import java.math.RoundingMode; // ДОБАВЛЕНО
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class CommissionConfig {
    private final Map<String, BigDecimal> commissionRanges = new ConcurrentHashMap<>();

    public CommissionConfig() {
        // Новые значения комиссий по умолчанию
        initializeDefaultRanges();
    }

    private void initializeDefaultRanges() {
        // Устанавливаем комиссии согласно требованиям
        commissionRanges.put("1000", new BigDecimal("50.0"));   // 1000-1999 ₽
        commissionRanges.put("2000", new BigDecimal("40.0"));   // 2000-2999 ₽
        commissionRanges.put("3000", new BigDecimal("33.0"));   // 3000-4999 ₽
        commissionRanges.put("5000", new BigDecimal("31.0"));   // 5000-9999 ₽
        commissionRanges.put("10000", new BigDecimal("30.0"));  // 10000-14999 ₽
        commissionRanges.put("15000", new BigDecimal("30.0"));  // 15000-19999 ₽
        commissionRanges.put("20000", new BigDecimal("29.0"));  // 20000-29999 ₽
        commissionRanges.put("30000", new BigDecimal("28.0"));  // 30000+ ₽

        System.out.println("COMMISSION DEBUG: Default commission ranges initialized:");
        commissionRanges.forEach((key, value) ->
                System.out.println("  - " + key + ": " + value + "%")
        );
    }

    public BigDecimal getCommissionPercent(BigDecimal amount) {
        System.out.println("COMMISSION DEBUG: Getting commission for amount: " + amount);

        // Находим подходящий диапазон
        List<BigDecimal> thresholds = commissionRanges.keySet().stream()
                .map(BigDecimal::new)
                .sorted(Comparator.reverseOrder())
                .collect(Collectors.toList());

        for (BigDecimal threshold : thresholds) {
            if (amount.compareTo(threshold) >= 0) {
                BigDecimal percent = commissionRanges.get(threshold.toString());
                System.out.println("COMMISSION DEBUG: Found commission " + percent + "% for threshold " + threshold);
                return percent;
            }
        }

        // Если сумма меньше минимального порога, возвращаем комиссию для минимального порога
        BigDecimal minThreshold = thresholds.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal defaultPercent = commissionRanges.get(minThreshold.toString());
        System.out.println("COMMISSION DEBUG: Using default commission " + defaultPercent + "% for amount " + amount);

        return defaultPercent;
    }

    public void updateCommissionRange(BigDecimal minAmount, BigDecimal percent) {
        System.out.println("COMMISSION DEBUG: Updating commission - Min: " + minAmount + ", Percent: " + percent);
        commissionRanges.put(minAmount.toString(), percent);

        // Логируем обновленные настройки
        System.out.println("COMMISSION DEBUG: Updated commission ranges:");
        commissionRanges.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry ->
                        System.out.println("  - " + entry.getKey() + ": " + entry.getValue() + "%")
                );
    }

    public Map<String, BigDecimal> getAllCommissionRanges() {
        return new HashMap<>(commissionRanges);
    }

    // Новый метод для получения комиссий в читаемом формате
    public String getCommissionRangesDisplay() {
        StringBuilder sb = new StringBuilder();
        commissionRanges.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    BigDecimal minAmount = new BigDecimal(entry.getKey());
                    BigDecimal percent = entry.getValue();
                    String range = getRangeDescription(minAmount);
                    sb.append(String.format("• %s: %.1f%%\n", range, percent.doubleValue()));
                });
        return sb.toString();
    }

    private String getRangeDescription(BigDecimal minAmount) {
        // Находим следующий порог для определения диапазона
        BigDecimal nextThreshold = commissionRanges.keySet().stream()
                .map(BigDecimal::new)
                .filter(threshold -> threshold.compareTo(minAmount) > 0)
                .min(BigDecimal::compareTo)
                .orElse(null);

        if (nextThreshold != null) {
            // Вычитаем 1 для красивого отображения диапазона
            BigDecimal maxAmount = nextThreshold.subtract(BigDecimal.ONE);
            return String.format("%s-%s ₽", minAmount, maxAmount);
        } else {
            return minAmount + "+ ₽";
        }
    }
}