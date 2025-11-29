package com.seeewo4kin.bot.ValueGettr;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class CryptoPriceService {
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // Кэш для хранения цен
    private final Map<String, BigDecimal> priceCache = new ConcurrentHashMap<>();
    private final Map<String, Map<String, BigDecimal>> multiplePriceCache = new ConcurrentHashMap<>();

    // Executor для асинхронных задач
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);

    // Символы для Binance API
    private static final Map<String, String> BINANCE_SYMBOLS = Map.of(
            "BTC", "BTCUSDT",
            "ETH", "ETHUSDT",
            "XMR", "XMRUSDT",
            "LTC", "LTCUSDT",
            "USDT", "USDTUSDT"
    );

    // Актуальные fallback rates на 2025 год
    private static final Map<String, Map<String, BigDecimal>> FALLBACK_RATES = Map.of(
            "BTC", Map.of("USD", BigDecimal.valueOf(95000.0), "RUB", BigDecimal.valueOf(8500000.0), "EUR", BigDecimal.valueOf(88000.0)),
            "ETH", Map.of("USD", BigDecimal.valueOf(3200.0), "RUB", BigDecimal.valueOf(290000.0), "EUR", BigDecimal.valueOf(2900.0)),
            "XMR", Map.of("USD", BigDecimal.valueOf(180.0), "RUB", BigDecimal.valueOf(16000.0), "EUR", BigDecimal.valueOf(165.0)),
            "LTC", Map.of("USD", BigDecimal.valueOf(85.0), "RUB", BigDecimal.valueOf(7600.0), "EUR", BigDecimal.valueOf(78.0)),
            "USDT", Map.of("USD", BigDecimal.valueOf(1.0), "RUB", BigDecimal.valueOf(89.0), "EUR", BigDecimal.valueOf(0.92)),
            "USDC", Map.of("USD", BigDecimal.valueOf(1.0), "RUB", BigDecimal.valueOf(89.0), "EUR", BigDecimal.valueOf(0.92)),
            "COUPONS", Map.of("USD", BigDecimal.valueOf(1.0), "RUB", BigDecimal.valueOf(89.0), "EUR", BigDecimal.valueOf(0.92))
    );

    public CryptoPriceService() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();

        // Запускаем периодическое обновление цен каждые 5 минут
        scheduler.scheduleAtFixedRate(this::updateAllPrices, 0, 5, TimeUnit.MINUTES);
    }

    /**
     * Обновление всех цен через Binance API
     */
    private void updateAllPrices() {
        try {
            updatePriceAsync("BTC");
            updatePriceAsync("ETH");
            updatePriceAsync("XMR");
            updatePriceAsync("LTC");
            updatePriceAsync("USDT");

            updateFiatRates();

        } catch (Exception e) {
            System.err.println("Error updating prices: " + e.getMessage());
        }
    }


    /**
     * Асинхронное обновление цены для конкретной криптовалюты
     */
    private void updatePriceAsync(String crypto) {
        scheduler.execute(() -> {
            try {
                if ("USDT".equals(crypto) || "USDC".equals(crypto)) {
                    // Для стейблкоинов используем фиксированные значения
                    Map<String, BigDecimal> prices = new HashMap<>();
                    prices.put("USD", BigDecimal.ONE);
                    // RUB и EUR будут обновлены в updateFiatRates()
                    multiplePriceCache.put(crypto, prices);
                    return;
                }

                String symbol = BINANCE_SYMBOLS.get(crypto);
                if (symbol == null) {
                    System.err.println("No Binance symbol for: " + crypto);
                    return;
                }

                // Получаем цену с Binance
                BigDecimal usdPrice = getPriceFromBinance(symbol);
                if (usdPrice != null && usdPrice.compareTo(BigDecimal.ZERO) == 1) {
                    Map<String, BigDecimal> prices = new HashMap<>();
                    prices.put("USD", usdPrice);

                    // Получаем текущие курсы RUB и EUR
                    BigDecimal usdToRub = priceCache.get("USD_RUB");
                    BigDecimal usdToEur = priceCache.get("USD_EUR");

                    if (usdToRub != null) {
                        prices.put("RUB", usdPrice.multiply(usdToRub));
                    }
                    if (usdToEur != null) {
                        prices.put("EUR", usdPrice.multiply(usdToEur));
                    }

                    multiplePriceCache.put(crypto, prices);

                    // Также обновляем отдельные цены в основном кэше
                    priceCache.put(crypto + "_USD", usdPrice);
                    if (usdToRub != null) {
                        priceCache.put(crypto + "_RUB", usdPrice.multiply(usdToRub));
                    }
                    if (usdToEur != null) {
                        priceCache.put(crypto + "_EUR", usdPrice.multiply(usdToEur));
                    }
                }
            } catch (Exception e) {
                System.err.println("Error updating price for " + crypto + ": " + e.getMessage());
            }
        });
    }

    /**
     * Получение цены с Binance API
     */
    private BigDecimal getPriceFromBinance(String symbol) {
        try {
            String url = "https://api.binance.com/api/v3/ticker/price?symbol=" + symbol;

            // Добавляем таймауты для избежания блокировок
            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);

            return BigDecimal.valueOf(root.path("price").asDouble());
        } catch (Exception e) {
            System.err.println("Error fetching price from Binance for " + symbol + ": " + e.getMessage());
            return getFallbackPrice(symbol.replace("USDT", ""), "USD");
        }
    }

    /**
     * Обновление курсов фиатных валют
     */
    private void updateFiatRates() {
        // Обновляем курсы фиатных валют синхронно
        updateUsdToRubRate();
        updateUsdToEurRate();
    }

    /**
     * Обновление курса USD/RUB
     */
    private void updateUsdToRubRate() {
        try {
            // Используем API ЦБ РФ или другие надежные источники
            String url = "https://www.cbr-xml-daily.ru/daily_json.js";
            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);

            BigDecimal usdRate = BigDecimal.valueOf(root.path("Valute").path("USD").path("Value").asDouble());
            if (usdRate != null && usdRate.compareTo(BigDecimal.ZERO) == 1) {
                priceCache.put("USD_RUB", usdRate);

                // Обновляем все цены в RUB
                updateAllPricesInRub(usdRate);
            }
        } catch (Exception e) {
            // Fallback значение (актуальный курс на 2025)
            priceCache.put("USD_RUB", BigDecimal.valueOf(89.0));
        }
    }

    /**
     * Обновление курса USD/EUR
     */
    private void updateUsdToEurRate() {
        BigDecimal eurRate = BigDecimal.valueOf(0.863); // Фиксированное актуальное значение на 2025

        try {
            // Пробуем получить актуальный курс
            String url = "https://api.exchangerate-api.com/v4/latest/USD";
            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);

            BigDecimal apiRate = BigDecimal.valueOf(root.path("rates").path("EUR").asDouble());
            if (apiRate != null && apiRate.compareTo(BigDecimal.ZERO) == 1) {
                eurRate = apiRate;
            }
        } catch (Exception e) {
            System.err.println("Error updating USD/EUR rate, using fallback: " + e.getMessage());
        }

        // Всегда устанавливаем значение
        priceCache.put("USD_EUR", eurRate);
        // Обновляем все цены в EUR
        updateAllPricesInEur(eurRate);
    }

    /**
     * Обновление всех цен в RUB при изменении курса
     */
    private void updateAllPricesInRub(BigDecimal usdToRubRate) {
        for (String crypto : multiplePriceCache.keySet()) {
            Map<String, BigDecimal> prices = multiplePriceCache.get(crypto);
            BigDecimal usdPrice = prices.get("USD");
            if (usdPrice != null) {
                prices.put("RUB", usdPrice.multiply(usdToRubRate));
                priceCache.put(crypto + "_RUB", usdPrice.multiply(usdToRubRate));
            }
        }
    }

    /**
     * Обновление всех цен в EUR при изменении курса
     */
    private void updateAllPricesInEur(BigDecimal usdToEurRate) {
        for (String crypto : multiplePriceCache.keySet()) {
            Map<String, BigDecimal> prices = multiplePriceCache.get(crypto);
            BigDecimal usdPrice = prices.get("USD");
            if (usdPrice != null) {
                prices.put("EUR", usdPrice.multiply(usdToEurRate));
                priceCache.put(crypto + "_EUR", usdPrice.multiply(usdToEurRate));
            }
        }
    }

    /**
     * Получение текущей цены с обработкой ошибок и fallback'ом
     */
    public BigDecimal getCurrentPrice(String cryptoCurrency, String fiatCurrency) {
        String cacheKey = cryptoCurrency + "_" + fiatCurrency;

        // Пробуем получить из кэша
        BigDecimal price = priceCache.get(cacheKey);
        if (price != null) {
            return price;
        }

        // Если нет в кэше, используем fallback
        return getFallbackPrice(cryptoCurrency, fiatCurrency);
    }

    /**
     * Получение цен для нескольких валют сразу
     */
    public Map<String, BigDecimal> getMultiplePrices(String cryptoCurrency) {
        Map<String, BigDecimal> prices = multiplePriceCache.get(cryptoCurrency);
        if (prices != null && !prices.isEmpty()) {
            return new HashMap<>(prices); // Возвращаем копию для безопасности
        }

        // Если нет в кэше, используем fallback
        return getFallbackMultiplePrices(cryptoCurrency);
    }

    /**
     * Fallback цены
     */
    private BigDecimal getFallbackPrice(String crypto, String fiat) {
        Map<String, BigDecimal> cryptoRates = FALLBACK_RATES.get(crypto.toUpperCase());
        if (cryptoRates != null) {
            return cryptoRates.getOrDefault(fiat.toUpperCase(), BigDecimal.ONE);
        }
        return BigDecimal.ONE;
    }

    private Map<String, BigDecimal> getFallbackMultiplePrices(String crypto) {
        return FALLBACK_RATES.getOrDefault(crypto.toUpperCase(),
                Map.of("USD", BigDecimal.valueOf(1.0), "RUB", BigDecimal.valueOf(92.0), "EUR", BigDecimal.valueOf(0.92)));
    }

    /**
     * Метод для принудительного обновления цен (можно вызывать извне)
     */
    public void forcePriceUpdate() {
        updateAllPrices();
    }

    /**
     * Синхронное обновление цен для конкретной криптовалюты
     */
    public BigDecimal getFreshPrice(String cryptoCurrency, String fiatCurrency) {
        try {
            // Пробуем обновить цену синхронно
            updatePriceSync(cryptoCurrency);
            // Обновляем фиатные курсы
            updateFiatRatesSync();

            // Возвращаем свежую цену
            return getCurrentPrice(cryptoCurrency, fiatCurrency);
        } catch (Exception e) {
            // Если обновление не удалось, возвращаем текущую цену из кэша
            return getCurrentPrice(cryptoCurrency, fiatCurrency);
        }
    }

    /**
     * Синхронное обновление цены для конкретной криптовалюты
     */
    private void updatePriceSync(String crypto) {
        try {
            if ("USDT".equals(crypto) || "USDC".equals(crypto)) {
                Map<String, BigDecimal> prices = new HashMap<>();
                prices.put("USD", BigDecimal.ONE);
                BigDecimal usdToRub = priceCache.get("USD_RUB");
                BigDecimal usdToEur = priceCache.get("USD_EUR");

                if (usdToRub != null) {
                    prices.put("RUB", BigDecimal.ONE.multiply(usdToRub));
                    priceCache.put(crypto + "_RUB", BigDecimal.ONE.multiply(usdToRub));
                }
                if (usdToEur != null) {
                    prices.put("EUR", BigDecimal.ONE.multiply(usdToEur));
                    priceCache.put(crypto + "_EUR", BigDecimal.ONE.multiply(usdToEur));
                }

                priceCache.put(crypto + "_USD", BigDecimal.ONE);
                multiplePriceCache.put(crypto, prices);
                return;
            }

            String symbol = BINANCE_SYMBOLS.get(crypto);
            if (symbol != null) {
                BigDecimal usdPrice = getPriceFromBinance(symbol);
                if (usdPrice != null && usdPrice.compareTo(BigDecimal.ZERO) == 1) {
                    Map<String, BigDecimal> prices = new HashMap<>();
                    prices.put("USD", usdPrice);
                    BigDecimal usdToRub = priceCache.get("USD_RUB");
                    BigDecimal usdToEur = priceCache.get("USD_EUR");

                    if (usdToRub != null) {
                        prices.put("RUB", usdPrice.multiply(usdToRub));
                    }
                    if (usdToEur != null) {
                        prices.put("EUR", usdPrice.multiply(usdToEur));
                    }

                    multiplePriceCache.put(crypto, prices);

                    // Обновляем отдельные цены в кэше
                    priceCache.put(crypto + "_USD", usdPrice);
                    if (usdToRub != null) {
                        priceCache.put(crypto + "_RUB", usdPrice.multiply(usdToRub));
                    }
                    if (usdToEur != null) {
                        priceCache.put(crypto + "_EUR", usdPrice.multiply(usdToEur));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error updating price for " + crypto + ": " + e.getMessage());
        }
    }

    /**
     * Синхронное обновление фиатных курсов
     */
    private void updateFiatRatesSync() {
        // Гарантируем установку базовых курсов
        if (priceCache.get("USD_RUB") == null) {
            priceCache.put("USD_RUB", BigDecimal.valueOf(89.0));
        }
        if (priceCache.get("USD_EUR") == null) {
            priceCache.put("USD_EUR", BigDecimal.valueOf(0.863));
        }

        updateUsdToRubRate();
        updateUsdToEurRate();
    }

    /**
     * Метод для принудительного обновления курса EUR (для тестирования)
     */
    public void forceEurUpdate() {
        System.out.println("FORCE EUR UPDATE DEBUG: Starting EUR update");
        updateUsdToEurRate();
        System.out.println("FORCE EUR UPDATE DEBUG: EUR update completed");
    }

    /**
     * Получение статуса кэша (для мониторинга)
     */
    public Map<String, Object> getCacheStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("cachedCryptos", multiplePriceCache.size());
        status.put("totalCachedPrices", priceCache.size());
        status.put("usdToRub", priceCache.get("USD_RUB"));
        status.put("usdToEur", priceCache.get("USD_EUR"));
        return status;
    }

    @Scheduled(fixedRate = 3000) // 5 минут
    public void scheduledPriceUpdate() {
        updateAllPrices();
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}