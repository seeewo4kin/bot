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
    private static final long CACHE_TTL_MS = 60000; // 1 минута

    private final CryptoPriceService cryptoPriceService;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    // Fallback курсы для основных пар
    private static final Map<String, Double> FALLBACK_RATES = Map.of(
            "BTC_USDT", 45000.0,
            "ETH_USDT", 3000.0,
            "USDT_RUB", 92.0,
            "USDT_EUR", 0.92,
            "BTC_ETH", 15.0,
            "BTC_RUB", 4000000.0,
            "ETH_RUB", 270000.0
    );

    public ExchangeRateCache(CryptoPriceService cryptoPriceService) {
        this.cryptoPriceService = cryptoPriceService;
        // Очистка устаревших записей каждые 30 секунд
        scheduler.scheduleAtFixedRate(this::cleanupExpiredCache, 30, 30, TimeUnit.SECONDS);
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

        // Если в кэше нет, вычисляем курс
        Double rate = calculateExchangeRate(fromCurrency, toCurrency);
        if (rate != null && rate > 0) {
            putRate(fromCurrency.name(), toCurrency.name(), rate);
            return rate;
        }

        // Если не удалось получить курс, используем fallback
        return getFallbackRate(fromCurrency, toCurrency);
    }

    private Double calculateExchangeRate(ValueType fromCurrency, ValueType toCurrency) {
        try {
            // Получаем курсы через USD
            Map<String, Double> fromPrices = cryptoPriceService.getMultiplePrices(fromCurrency.name());
            Map<String, Double> toPrices = cryptoPriceService.getMultiplePrices(toCurrency.name());

            Double fromUsdRate = fromPrices.get("USD");
            Double toUsdRate = toPrices.get("USD");

            if (fromUsdRate != null && toUsdRate != null && toUsdRate != 0) {
                return fromUsdRate / toUsdRate;
            }
        } catch (Exception e) {
            // В случае ошибки используем fallback-логику
        }

        return null;
    }

    private Double getFallbackRate(ValueType fromCurrency, ValueType toCurrency) {
        String key = fromCurrency.name() + "_" + toCurrency.name();
        Double rate = FALLBACK_RATES.get(key);

        if (rate != null) {
            return rate;
        }

        // Если прямой пары нет, пробуем обратную
        String reverseKey = toCurrency.name() + "_" + fromCurrency.name();
        Double reverseRate = FALLBACK_RATES.get(reverseKey);
        if (reverseRate != null && reverseRate != 0) {
            return 1.0 / reverseRate;
        }

        // Если ничего не нашли, используем базовые курсы через USD
        Map<ValueType, Double> usdRates = Map.of(
                ValueType.BTC, 45000.0,
                ValueType.ETH, 3000.0,
                ValueType.USDT, 1.0,
                ValueType.USDC, 1.0,
                ValueType.RUB, 0.0109, // ~92 RUB за 1 USD
                ValueType.EUR, 1.08
        );

        Double fromUsd = usdRates.get(fromCurrency);
        Double toUsd = usdRates.get(toCurrency);

        if (fromUsd != null && toUsd != null && toUsd != 0) {
            return fromUsd / toUsd;
        }

        return 1.0; // fallback
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