package com.seeewo4kin.bot.ValueGettr;

import com.seeewo4kin.bot.Enums.ValueType;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class ExchangeRateCache {
    private final Map<String, BigDecimal> rateCache = new ConcurrentHashMap<>();
    private final Map<String, Long> cacheTimestamps = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 300000; // 5 минут

    private final CryptoPriceService cryptoPriceService;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public ExchangeRateCache(CryptoPriceService cryptoPriceService) {
        this.cryptoPriceService = cryptoPriceService;
        // Очистка устаревших записей каждую минуту
        scheduler.scheduleAtFixedRate(this::cleanupExpiredCache, 60, 60, TimeUnit.SECONDS);
    }

    public BigDecimal getCachedRate(String fromCurrency, String toCurrency) {
        String cacheKey = fromCurrency + "_" + toCurrency;
        Long timestamp = cacheTimestamps.get(cacheKey);

        if (timestamp != null && System.currentTimeMillis() - timestamp < CACHE_TTL_MS) {
            return rateCache.get(cacheKey);
        }
        return null;
    }

    public void putRate(String fromCurrency, String toCurrency, BigDecimal rate) {
        String cacheKey = fromCurrency + "_" + toCurrency;
        if (rate != null && rate.compareTo(BigDecimal.ZERO) > 0) {
            rateCache.put(cacheKey, rate);
            cacheTimestamps.put(cacheKey, System.currentTimeMillis());
        }
    }

    public BigDecimal getExchangeRate(ValueType fromCurrency, ValueType toCurrency) {
        if (fromCurrency == toCurrency) {
            return BigDecimal.ZERO;
        }

        // Проверяем кэш
        BigDecimal cachedRate = getCachedRate(fromCurrency.name(), toCurrency.name());
        if (cachedRate != null) {
            return cachedRate;
        }

        // Если в кэше нет, вычисляем курс через USD
        BigDecimal rate = calculateExchangeRateViaUSD(fromCurrency, toCurrency);
        if (rate != null && rate.compareTo(BigDecimal.ZERO) == 1) {
            putRate(fromCurrency.name(), toCurrency.name(), rate);
            return rate;
        }

        // Если не удалось получить курс, используем прямой расчет
        return calculateDirectExchangeRate(fromCurrency, toCurrency);
    }

    private BigDecimal calculateExchangeRateViaUSD(ValueType fromCurrency, ValueType toCurrency) {
        try {
            // Получаем цены в USD
            BigDecimal fromUsdPrice = cryptoPriceService.getCurrentPrice(fromCurrency.name(), "USD");
            BigDecimal toUsdPrice = cryptoPriceService.getCurrentPrice(toCurrency.name(), "USD");

            if (fromUsdPrice != null && toUsdPrice != null && toUsdPrice.compareTo(BigDecimal.ZERO) != 0) {
                return fromUsdPrice.divide(toUsdPrice, MathContext.DECIMAL64);
            }
        } catch (Exception e) {
            System.err.println("Error calculating exchange rate via USD: " + e.getMessage());
        }
        return null;
    }

    private BigDecimal calculateDirectExchangeRate(ValueType fromCurrency, ValueType toCurrency) {
        try {
            // Пробуем получить прямую цену
            Map<String, BigDecimal> fromPrices = cryptoPriceService.getMultiplePrices(fromCurrency.name());
            Map<String, BigDecimal> toPrices = cryptoPriceService.getMultiplePrices(toCurrency.name());

            // Пробуем разные валюты для расчета
            String[] fiats = {"USD", "RUB", "EUR"};
            for (String fiat : fiats) {
                BigDecimal fromPrice = fromPrices.get(fiat);
                BigDecimal toPrice = toPrices.get(fiat);

                if (fromPrice != null && toPrice != null && toPrice.compareTo(BigDecimal.ZERO) != 0) {
                    return fromPrice.divide(toPrice, MathContext.DECIMAL64);
                }
            }
        } catch (Exception e) {
            System.err.println("Error calculating direct exchange rate: " + e.getMessage());
        }

        // Final fallback
        return getFallbackRate(fromCurrency, toCurrency);
    }

    private BigDecimal getFallbackRate(ValueType fromCurrency, ValueType toCurrency) {
        // Упрощенные курсы для fallback через USD
        Map<ValueType, BigDecimal> usdRates = Map.of(
                ValueType.BTC, BigDecimal.valueOf(45000.00),
                ValueType.RUB, BigDecimal.valueOf(0.0109),
                ValueType.COUPONS, BigDecimal.ONE
        );

        BigDecimal fromUsd = usdRates.get(fromCurrency);
        BigDecimal toUsd = usdRates.get(toCurrency);

        if (fromUsd != null && toUsd != null && toUsd.compareTo(BigDecimal.ZERO) != 0) {
            return fromUsd.divide(toUsd, MathContext.DECIMAL64);
        }

        return BigDecimal.ONE;
    }

    private void cleanupExpiredCache() {
        long currentTime = System.currentTimeMillis();
        cacheTimestamps.entrySet().removeIf(entry ->
                currentTime - entry.getValue() > CACHE_TTL_MS
        );
        // Также удаляем из rateCache
        rateCache.keySet().removeIf(key -> !cacheTimestamps.containsKey(key));
    }

    public void shutdown() {
        scheduler.shutdown();
    }
}