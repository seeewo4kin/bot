package com.seeewo4kin.bot.ValueGettr;

import com.seeewo4kin.bot.Enums.ValueType;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class ExchangeRateCache {
    private final Map<String, Double> rateCache = new ConcurrentHashMap<>();
    private final Map<String, Long> cacheTimestamps = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 300000; // 5 минут

    private final CryptoPriceService cryptoPriceService;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public ExchangeRateCache(CryptoPriceService cryptoPriceService) {
        this.cryptoPriceService = cryptoPriceService;
        // Очистка устаревших записей каждую минуту
        scheduler.scheduleAtFixedRate(this::cleanupExpiredCache, 60, 60, TimeUnit.SECONDS);
    }

    public Double getCachedRate(String fromCurrency, String toCurrency) {
        String cacheKey = fromCurrency + "_" + toCurrency;
        Long timestamp = cacheTimestamps.get(cacheKey);

        if (timestamp != null && System.currentTimeMillis() - timestamp < CACHE_TTL_MS) {
            return rateCache.get(cacheKey);
        }
        return null;
    }

    public void putRate(String fromCurrency, String toCurrency, Double rate) {
        String cacheKey = fromCurrency + "_" + toCurrency;
        if (rate != null && rate > 0) {
            rateCache.put(cacheKey, rate);
            cacheTimestamps.put(cacheKey, System.currentTimeMillis());
        }
    }

    public Double getExchangeRate(ValueType fromCurrency, ValueType toCurrency) {
        if (fromCurrency == toCurrency) {
            return 1.0;
        }

        // Проверяем кэш
        Double cachedRate = getCachedRate(fromCurrency.name(), toCurrency.name());
        if (cachedRate != null) {
            return cachedRate;
        }

        // Если в кэше нет, вычисляем курс через USD
        Double rate = calculateExchangeRateViaUSD(fromCurrency, toCurrency);
        if (rate != null && rate > 0) {
            putRate(fromCurrency.name(), toCurrency.name(), rate);
            return rate;
        }

        // Если не удалось получить курс, используем прямой расчет
        return calculateDirectExchangeRate(fromCurrency, toCurrency);
    }

    private Double calculateExchangeRateViaUSD(ValueType fromCurrency, ValueType toCurrency) {
        try {
            // Получаем цены в USD
            Double fromUsdPrice = cryptoPriceService.getCurrentPrice(fromCurrency.name(), "USD");
            Double toUsdPrice = cryptoPriceService.getCurrentPrice(toCurrency.name(), "USD");

            if (fromUsdPrice != null && toUsdPrice != null && toUsdPrice != 0) {
                return fromUsdPrice / toUsdPrice;
            }
        } catch (Exception e) {
            System.err.println("Error calculating exchange rate via USD: " + e.getMessage());
        }
        return null;
    }

    private Double calculateDirectExchangeRate(ValueType fromCurrency, ValueType toCurrency) {
        try {
            // Пробуем получить прямую цену
            Map<String, Double> fromPrices = cryptoPriceService.getMultiplePrices(fromCurrency.name());
            Map<String, Double> toPrices = cryptoPriceService.getMultiplePrices(toCurrency.name());

            // Пробуем разные валюты для расчета
            String[] fiats = {"USD", "RUB", "EUR"};
            for (String fiat : fiats) {
                Double fromPrice = fromPrices.get(fiat);
                Double toPrice = toPrices.get(fiat);

                if (fromPrice != null && toPrice != null && toPrice != 0) {
                    return fromPrice / toPrice;
                }
            }
        } catch (Exception e) {
            System.err.println("Error calculating direct exchange rate: " + e.getMessage());
        }

        // Final fallback
        return getFallbackRate(fromCurrency, toCurrency);
    }

    private Double getFallbackRate(ValueType fromCurrency, ValueType toCurrency) {
        // Упрощенные курсы для fallback через USD
        Map<ValueType, Double> usdRates = Map.of(
                ValueType.BTC, 45000.0,
                ValueType.RUB, 0.0109,
                ValueType.COUPONS, 1.0
        );

        Double fromUsd = usdRates.get(fromCurrency);
        Double toUsd = usdRates.get(toCurrency);

        if (fromUsd != null && toUsd != null && toUsd != 0) {
            return fromUsd / toUsd;
        }

        return 1.0;
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