package com.seeewo4kin.bot.Config;

import com.seeewo4kin.bot.Entity.CommissionRange;
import com.seeewo4kin.bot.repository.CommissionRangeRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Configuration
public class CommissionConfig {

    private final CommissionRangeRepository commissionRangeRepository;
    private final TreeMap<BigDecimal, BigDecimal> commissionRanges = new TreeMap<>();
    
    // Значения по умолчанию из application.properties
    private final BigDecimal range1Min, range1Max, range1Percent;
    private final BigDecimal range2Min, range2Max, range2Percent;
    private final BigDecimal range3Min, range3Max, range3Percent;
    private final BigDecimal range4Min, range4Max, range4Percent;
    private final BigDecimal range5Min, range5Max, range5Percent;
    private final BigDecimal range6Min, range6Max, range6Percent;
    private final BigDecimal range7Min, range7Max, range7Percent;

    public CommissionConfig(
            CommissionRangeRepository commissionRangeRepository,
            // Fallback значения из application.properties для инициализации
            @Value("${bot.commission.range1.min:1000}") BigDecimal range1Min,
            @Value("${bot.commission.range1.max:1999}") BigDecimal range1Max,
            @Value("${bot.commission.range1.percent:5.0}") BigDecimal range1Percent,
            @Value("${bot.commission.range2.min:2000}") BigDecimal range2Min,
            @Value("${bot.commission.range2.max:2999}") BigDecimal range2Max,
            @Value("${bot.commission.range2.percent:4.0}") BigDecimal range2Percent,
            @Value("${bot.commission.range3.min:3000}") BigDecimal range3Min,
            @Value("${bot.commission.range3.max:4999}") BigDecimal range3Max,
            @Value("${bot.commission.range3.percent:3.3}") BigDecimal range3Percent,
            @Value("${bot.commission.range4.min:5000}") BigDecimal range4Min,
            @Value("${bot.commission.range4.max:9999}") BigDecimal range4Max,
            @Value("${bot.commission.range4.percent:3.1}") BigDecimal range4Percent,
            @Value("${bot.commission.range5.min:10000}") BigDecimal range5Min,
            @Value("${bot.commission.range5.max:14999}") BigDecimal range5Max,
            @Value("${bot.commission.range5.percent:3.0}") BigDecimal range5Percent,
            @Value("${bot.commission.range6.min:15000}") BigDecimal range6Min,
            @Value("${bot.commission.range6.max:19999}") BigDecimal range6Max,
            @Value("${bot.commission.range6.percent:3.0}") BigDecimal range6Percent,
            @Value("${bot.commission.range7.min:20000}") BigDecimal range7Min,
            @Value("${bot.commission.range7.max:24999}") BigDecimal range7Max,
            @Value("${bot.commission.range7.percent:2.9}") BigDecimal range7Percent) {
        this.commissionRangeRepository = commissionRangeRepository;
        this.range1Min = range1Min;
        this.range1Max = range1Max;
        this.range1Percent = range1Percent;
        this.range2Min = range2Min;
        this.range2Max = range2Max;
        this.range2Percent = range2Percent;
        this.range3Min = range3Min;
        this.range3Max = range3Max;
        this.range3Percent = range3Percent;
        this.range4Min = range4Min;
        this.range4Max = range4Max;
        this.range4Percent = range4Percent;
        this.range5Min = range5Min;
        this.range5Max = range5Max;
        this.range5Percent = range5Percent;
        this.range6Min = range6Min;
        this.range6Max = range6Max;
        this.range6Percent = range6Percent;
        this.range7Min = range7Min;
        this.range7Max = range7Max;
        this.range7Percent = range7Percent;
    }

    @PostConstruct
    public void init() {
        // Удаляем диапазоны 25000+ и 30000+ если они есть
        removeUnwantedRanges();
        
        loadFromDatabase();
        
        // Если БД пустая, инициализируем значениями из application.properties
        if (commissionRanges.isEmpty()) {
            initializeDefaultRanges();
        }
    }
    
    private void removeUnwantedRanges() {
        // Удаляем диапазоны 25000+ и 30000+
        commissionRangeRepository.findByMinAmount(new BigDecimal("25000"))
                .ifPresent(commissionRangeRepository::delete);
        commissionRangeRepository.findByMinAmount(new BigDecimal("30000"))
                .ifPresent(commissionRangeRepository::delete);
        System.out.println("COMMISSION DEBUG: Removed unwanted commission ranges (25000+, 30000+)");
    }

    private void loadFromDatabase() {
        commissionRanges.clear();
        List<CommissionRange> ranges = commissionRangeRepository.findAllByOrderByMinAmountAsc();
        for (CommissionRange range : ranges) {
            commissionRanges.put(range.getMinAmount(), range.getPercent());
        }
        System.out.println("COMMISSION DEBUG: Loaded " + ranges.size() + " commission ranges from database");
    }

    private void initializeDefaultRanges() {
        // Используем значения из application.properties
        saveRange(range1Min, range1Max, range1Percent);
        saveRange(range2Min, range2Max, range2Percent);
        saveRange(range3Min, range3Max, range3Percent);
        saveRange(range4Min, range4Max, range4Percent);
        saveRange(range5Min, range5Max, range5Percent);
        saveRange(range6Min, range6Max, range6Percent);
        saveRange(range7Min, range7Max, range7Percent);
        
        loadFromDatabase();
        System.out.println("COMMISSION DEBUG: Initialized default commission ranges in database");
    }

    private void saveRange(BigDecimal minAmount, BigDecimal maxAmount, BigDecimal percent) {
        CommissionRange range = commissionRangeRepository.findByMinAmount(minAmount)
                .orElse(new CommissionRange());
        range.setMinAmount(minAmount);
        range.setMaxAmount(maxAmount);
        range.setPercent(percent);
        commissionRangeRepository.save(range);
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
        // Сохраняем в БД
        CommissionRange range = commissionRangeRepository.findByMinAmount(minAmount)
                .orElse(new CommissionRange());
        range.setMinAmount(minAmount);
        range.setPercent(percent);
        commissionRangeRepository.save(range);
        
        // Перезагружаем из БД для синхронизации
        loadFromDatabase();
        
        System.out.println("COMMISSION DEBUG: Updated commission range in database: " + minAmount + " -> " + percent + "%");
    }
    
    public void updateCommissionRange(BigDecimal minAmount, BigDecimal maxAmount, BigDecimal percent) {
        // Сохраняем в БД
        CommissionRange range = commissionRangeRepository.findByMinAmount(minAmount)
                .orElse(new CommissionRange());
        range.setMinAmount(minAmount);
        range.setMaxAmount(maxAmount);
        range.setPercent(percent);
        commissionRangeRepository.save(range);
        
        // Перезагружаем из БД для синхронизации
        loadFromDatabase();
        
        System.out.println("COMMISSION DEBUG: Updated commission range in database: " + minAmount + "-" + maxAmount + " -> " + percent + "%");
    }
    
    public void reloadFromDatabase() {
        loadFromDatabase();
    }

    public String getCommissionRangesDisplay() {
        StringBuilder sb = new StringBuilder();
        sb.append("💰 Комиссии обмена:\n\n");

        List<CommissionRange> ranges = commissionRangeRepository.findAllByOrderByMinAmountAsc();
        for (CommissionRange range : ranges) {
            BigDecimal minAmount = range.getMinAmount();
            BigDecimal maxAmount = range.getMaxAmount();
            BigDecimal percent = range.getPercent();
            
            if (maxAmount != null) {
                sb.append(String.format("• %.0f-%.0f ₽: %.1f%%\n", minAmount, maxAmount, percent));
            } else {
                sb.append(String.format("• От %.0f ₽: %.1f%%\n", minAmount, percent));
            }
        }

        return sb.toString();
    }
}