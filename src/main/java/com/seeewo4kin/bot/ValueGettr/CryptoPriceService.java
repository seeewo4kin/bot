package com.seeewo4kin.bot.ValueGettr;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    private final Map<String, Double> priceCache = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Double>> multiplePriceCache = new ConcurrentHashMap<>();

    // Executor для асинхронных задач
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);

    // Символы для Binance API
    private static final Map<String, String> BINANCE_SYMBOLS = Map.of(
            "BTC", "BTCUSDT",
            "ETH", "ETHUSDT",
            "XMR", "XMRUSDT",
            "LTC", "LTCUSDT",
            "USDT", "USDTUSDT" // Для USDT используем статическое значение
    );

    // Fallback rates
    private static final Map<String, Map<String, Double>> FALLBACK_RATES = Map.of(
            "BTC", Map.of("USD", 45000.0, "RUB", 4000000.0, "EUR", 41000.0),
            "ETH", Map.of("USD", 3000.0, "RUB", 270000.0, "EUR", 2700.0),
            "XMR", Map.of("USD", 150.0, "RUB", 13500.0, "EUR", 140.0),
            "LTC", Map.of("USD", 75.0, "RUB", 6800.0, "EUR", 70.0),
            "USDT", Map.of("USD", 1.0, "RUB", 92.0, "EUR", 0.92),
            "USDC", Map.of("USD", 1.0, "RUB", 92.0, "EUR", 0.92),
            "COUPONS", Map.of("USD", 1.0, "RUB", 92.0, "EUR", 0.92)
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
            // Обновляем цены для основных криптовалют
            updatePriceAsync("BTC");
            updatePriceAsync("ETH");
            updatePriceAsync("XMR");
            updatePriceAsync("LTC");
            updatePriceAsync("USDT");

            // Получаем курсы фиатных валют
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
                    Map<String, Double> prices = new HashMap<>();
                    prices.put("USD", 1.0);
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
                Double usdPrice = getPriceFromBinance(symbol);
                if (usdPrice != null && usdPrice > 0) {
                    Map<String, Double> prices = new HashMap<>();
                    prices.put("USD", usdPrice);

                    // Получаем текущие курсы RUB и EUR
                    Double usdToRub = priceCache.get("USD_RUB");
                    Double usdToEur = priceCache.get("USD_EUR");

                    if (usdToRub != null) {
                        prices.put("RUB", usdPrice * usdToRub);
                    }
                    if (usdToEur != null) {
                        prices.put("EUR", usdPrice * usdToEur);
                    }

                    multiplePriceCache.put(crypto, prices);

                    // Также обновляем отдельные цены в основном кэше
                    priceCache.put(crypto + "_USD", usdPrice);
                    if (usdToRub != null) {
                        priceCache.put(crypto + "_RUB", usdPrice * usdToRub);
                    }
                    if (usdToEur != null) {
                        priceCache.put(crypto + "_EUR", usdPrice * usdToEur);
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
    private Double getPriceFromBinance(String symbol) {
        try {
            String url = "https://api.binance.com/api/v3/ticker/price?symbol=" + symbol;

            // Добавляем таймауты для избежания блокировок
            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);

            return root.path("price").asDouble();
        } catch (Exception e) {
            System.err.println("Error fetching price from Binance for " + symbol + ": " + e.getMessage());
            return getFallbackPrice(symbol.replace("USDT", ""), "USD");
        }
    }

    /**
     * Обновление курсов фиатных валют
     */
    private void updateFiatRates() {
        scheduler.execute(() -> {
            try {
                // Используем ЦБ РФ или другие источники для курсов
                // Для примера, можно использовать CoinGecko для фиатных пар или другие API
                updateUsdToRubRate();
                updateUsdToEurRate();

            } catch (Exception e) {
                System.err.println("Error updating fiat rates: " + e.getMessage());
                // Используем fallback значения
                priceCache.put("USD_RUB", 92.0);
                priceCache.put("USD_EUR", 0.92);
            }
        });
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

            Double usdRate = root.path("Valute").path("USD").path("Value").asDouble();
            if (usdRate != null && usdRate > 0) {
                priceCache.put("USD_RUB", usdRate);

                // Обновляем все цены в RUB
                updateAllPricesInRub(usdRate);
            }
        } catch (Exception e) {
            // Fallback значение
            priceCache.put("USD_RUB", 92.0);
            System.err.println("Error updating USD/RUB rate: " + e.getMessage());
        }
    }

    /**
     * Обновление курса USD/EUR
     */
    private void updateUsdToEurRate() {
        try {
            // Используем ECB API или другие источники
            String url = "https://api.exchangerate.host/latest?base=USD&symbols=EUR";
            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);

            Double eurRate = root.path("rates").path("EUR").asDouble();
            if (eurRate != null && eurRate > 0) {
                priceCache.put("USD_EUR", eurRate);

                // Обновляем все цены в EUR
                updateAllPricesInEur(eurRate);
            }
        } catch (Exception e) {
            // Fallback значение
            priceCache.put("USD_EUR", 0.92);
            System.err.println("Error updating USD/EUR rate: " + e.getMessage());
        }
    }

    /**
     * Обновление всех цен в RUB при изменении курса
     */
    private void updateAllPricesInRub(Double usdToRubRate) {
        for (String crypto : multiplePriceCache.keySet()) {
            Map<String, Double> prices = multiplePriceCache.get(crypto);
            Double usdPrice = prices.get("USD");
            if (usdPrice != null) {
                prices.put("RUB", usdPrice * usdToRubRate);
                priceCache.put(crypto + "_RUB", usdPrice * usdToRubRate);
            }
        }
    }

    /**
     * Обновление всех цен в EUR при изменении курса
     */
    private void updateAllPricesInEur(Double usdToEurRate) {
        for (String crypto : multiplePriceCache.keySet()) {
            Map<String, Double> prices = multiplePriceCache.get(crypto);
            Double usdPrice = prices.get("USD");
            if (usdPrice != null) {
                prices.put("EUR", usdPrice * usdToEurRate);
                priceCache.put(crypto + "_EUR", usdPrice * usdToEurRate);
            }
        }
    }

    /**
     * Получение текущей цены с обработкой ошибок и fallback'ом
     */
    public Double getCurrentPrice(String cryptoCurrency, String fiatCurrency) {
        String cacheKey = cryptoCurrency + "_" + fiatCurrency;

        // Пробуем получить из кэша
        Double price = priceCache.get(cacheKey);
        if (price != null) {
            return price;
        }

        // Если нет в кэше, используем fallback
        return getFallbackPrice(cryptoCurrency, fiatCurrency);
    }

    /**
     * Получение цен для нескольких валют сразу
     */
    public Map<String, Double> getMultiplePrices(String cryptoCurrency) {
        Map<String, Double> prices = multiplePriceCache.get(cryptoCurrency);
        if (prices != null && !prices.isEmpty()) {
            return new HashMap<>(prices); // Возвращаем копию для безопасности
        }

        // Если нет в кэше, используем fallback
        return getFallbackMultiplePrices(cryptoCurrency);
    }

    /**
     * Fallback цены
     */
    private Double getFallbackPrice(String crypto, String fiat) {
        Map<String, Double> cryptoRates = FALLBACK_RATES.get(crypto.toUpperCase());
        if (cryptoRates != null) {
            return cryptoRates.getOrDefault(fiat.toUpperCase(), 1.0);
        }
        return 1.0;
    }

    private Map<String, Double> getFallbackMultiplePrices(String crypto) {
        return FALLBACK_RATES.getOrDefault(crypto.toUpperCase(),
                Map.of("USD", 1.0, "RUB", 92.0, "EUR", 0.92));
    }

    /**
     * Метод для принудительного обновления цен (можно вызывать извне)
     */
    public void forcePriceUpdate() {
        updateAllPrices();
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