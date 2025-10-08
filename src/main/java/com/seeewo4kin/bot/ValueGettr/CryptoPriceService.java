package com.seeewo4kin.bot.ValueGettr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CryptoPriceService {
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${crypto.api.key:}")
    private String apiKey;

    private final Map<String, Double> priceCache = new ConcurrentHashMap<>();
    private final Map<String, Long> lastCallTimestamps = new ConcurrentHashMap<>();
    private static final long RATE_LIMIT_DELAY = 61000; // 61 seconds for CoinGecko

    // Fallback rates in case APIs are unavailable
    private static final Map<String, Map<String, Double>> FALLBACK_RATES = Map.of(
            "BTC", Map.of("USD", 45000.0, "EUR", 41000.0, "RUB", 4000000.0),
            "ETH", Map.of("USD", 3000.0, "EUR", 2700.0, "RUB", 270000.0),
            "USDT", Map.of("USD", 1.0, "EUR", 0.92, "RUB", 92.0),
            "USDC", Map.of("USD", 1.0, "EUR", 0.92, "RUB", 92.0)
    );

    public CryptoPriceService() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Получение курса через CoinGecko API с rate limiting
     */
    @Cacheable(value = "cryptoPrices", unless = "#result == null")
    public Double getPriceFromCoinGecko(String cryptoId, String currency) {
        enforceRateLimit("coingecko");

        try {
            String url = String.format("https://api.coingecko.com/api/v3/simple/price?ids=%s&vs_currencies=%s",
                    cryptoId, currency.toLowerCase());

            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);

            return root.path(cryptoId).path(currency.toLowerCase()).asDouble();
        } catch (HttpClientErrorException.TooManyRequests e) {
            // Return fallback value if rate limited
            return getFallbackPrice(cryptoId, currency);
        } catch (Exception e) {
            // Return fallback for any other error
            return getFallbackPrice(cryptoId, currency);
        }
    }

    /**
     * Получение курса через Binance API с правильными символами
     */
    @Cacheable(value = "cryptoPrices", unless = "#result == null")
    public Double getPriceFromBinance(String symbol) {
        enforceRateLimit("binance");

        try {
            // Ensure proper symbol format
            String formattedSymbol = getCorrectBinanceSymbol(symbol);
            String url = String.format("https://api.binance.com/api/v3/ticker/price?symbol=%s", formattedSymbol);

            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);

            return root.path("price").asDouble();
        } catch (HttpClientErrorException.BadRequest e) {
            // Try alternative symbols or return fallback
            return tryAlternativeBinanceSymbols(symbol);
        } catch (Exception e) {
            return getFallbackPriceFromSymbol(symbol);
        }
    }

    /**
     * Универсальный метод для получения цены с улучшенной обработкой ошибок
     */
    public Double getCurrentPrice(String cryptoCurrency, String fiatCurrency) {
        String cacheKey = cryptoCurrency + "_" + fiatCurrency;

        // Проверяем кэш
        if (priceCache.containsKey(cacheKey)) {
            return priceCache.get(cacheKey);
        }

        Double price = null;

        try {
            switch (cryptoCurrency.toUpperCase()) {
                case "BTC":
                    price = getBitcoinPrice(fiatCurrency);
                    break;
                case "ETH":
                    price = getEthereumPrice(fiatCurrency);
                    break;
                case "USDT":
                    price = getUsdtPrice(fiatCurrency);
                    break;
                case "USDC":
                    price = getUsdcPrice(fiatCurrency);
                    break;
                default:
                    price = getFallbackPrice(cryptoCurrency, fiatCurrency);
            }
        } catch (Exception e) {
            price = getFallbackPrice(cryptoCurrency, fiatCurrency);
        }

        if (price != null) {
            priceCache.put(cacheKey, price);
        }

        return price;
    }

    /**
     * Методы для конкретных криптовалют с улучшенной обработкой ошибок
     */
    public Double getBitcoinPrice(String currency) {
        // Пробуем CoinGecko
        Double price = getPriceFromCoinGecko("bitcoin", currency);
        if (price != null && price > 0) return price;

        // Если не получилось, пробуем Binance
        try {
            return getPriceFromBinance("BTC" + getCorrectFiatSymbol(currency));
        } catch (Exception e) {
            return getFallbackPrice("BTC", currency);
        }
    }

    public Double getEthereumPrice(String currency) {
        Double price = getPriceFromCoinGecko("ethereum", currency);
        if (price != null && price > 0) return price;

        try {
            return getPriceFromBinance("ETH" + getCorrectFiatSymbol(currency));
        } catch (Exception e) {
            return getFallbackPrice("ETH", currency);
        }
    }

    public Double getUsdtPrice(String currency) {
        // USDT обычно привязан к доллару
        if ("USD".equalsIgnoreCase(currency)) {
            return 1.0;
        }

        // Для других валют используем прямые пары если возможно
        try {
            // Попробуем получить курс через USDT пары
            String pair = "USDT" + getCorrectFiatSymbol(currency);
            Double price = getPriceFromBinance(pair);
            if (price != null && price > 0) return price;
        } catch (Exception e) {
            // Если не получилось, используем fallback
        }

        return getFallbackPrice("USDT", currency);
    }

    public Double getUsdcPrice(String currency) {
        // USDC также привязан к доллару
        if ("USD".equalsIgnoreCase(currency)) {
            return 1.0;
        }
        return getUsdtPrice(currency); // Используем ту же логику что и для USDT
    }

    /**
     * Получение курсов для нескольких валют сразу с обработкой ошибок
     */
    public Map<String, Double> getMultiplePrices(String cryptoCurrency) {
        Map<String, Double> prices = new HashMap<>();

        try {
            prices.put("RUB", getCurrentPrice(cryptoCurrency, "RUB"));
            prices.put("USD", getCurrentPrice(cryptoCurrency, "USD"));
            prices.put("EUR", getCurrentPrice(cryptoCurrency, "EUR"));
        } catch (Exception e) {
            // Если произошла ошибка, используем fallback значения
            Map<String, Double> fallback = FALLBACK_RATES.getOrDefault(cryptoCurrency.toUpperCase(),
                    Map.of("USD", 1.0, "EUR", 0.92, "RUB", 92.0));

            prices.putAll(fallback);
        }

        // Ensure no null values
        prices.putIfAbsent("RUB", FALLBACK_RATES.getOrDefault(cryptoCurrency.toUpperCase(),
                Map.of("RUB", 92.0)).get("RUB"));
        prices.putIfAbsent("USD", FALLBACK_RATES.getOrDefault(cryptoCurrency.toUpperCase(),
                Map.of("USD", 1.0)).get("USD"));
        prices.putIfAbsent("EUR", FALLBACK_RATES.getOrDefault(cryptoCurrency.toUpperCase(),
                Map.of("EUR", 0.92)).get("EUR"));

        return prices;
    }

    /**
     * Вспомогательные методы
     */
    private String getCorrectBinanceSymbol(String symbol) {
        // Преобразуем символы к правильному формату для Binance
        Map<String, String> symbolMappings = Map.of(
                "BTCRUB", "BTCRUB",
                "ETHRUB", "ETHRUB",
                "USDTRUB", "USDTRUB",
                "USDTUSD", "BUSDUSDT", // USDT к USD через BUSD
                "BTCUSD", "BTCUSDT",
                "ETHUSD", "ETHUSDT",
                "USDTEUR", "EURUSDT"
        );

        return symbolMappings.getOrDefault(symbol.toUpperCase(), symbol.toUpperCase());
    }

    private Double tryAlternativeBinanceSymbols(String symbol) {
        // Пробуем альтернативные символы
        Map<String, String[]> alternatives = Map.of(
                "USDTRUB", new String[]{"USDTRUB", "RUBUSDT"},
                "BTCRUB", new String[]{"BTCRUB", "RUBBTC"},
                "ETHRUB", new String[]{"ETHRUB", "RUBETH"}
        );

        String[] altSymbols = alternatives.get(symbol.toUpperCase());
        if (altSymbols != null) {
            for (String altSymbol : altSymbols) {
                try {
                    Double price = getPriceFromBinance(altSymbol);
                    if (price != null && price > 0) return price;
                } catch (Exception e) {
                    continue;
                }
            }
        }

        return getFallbackPriceFromSymbol(symbol);
    }

    private String getCorrectFiatSymbol(String currency) {
        Map<String, String> fiatMappings = Map.of(
                "USD", "USDT",
                "EUR", "EUR",
                "RUB", "RUB"
        );
        return fiatMappings.getOrDefault(currency.toUpperCase(), currency.toUpperCase());
    }

    private Double getFallbackPrice(String cryptoId, String currency) {
        Map<String, Double> cryptoRates = FALLBACK_RATES.get(cryptoId.toUpperCase());
        if (cryptoRates != null) {
            return cryptoRates.getOrDefault(currency.toUpperCase(), 1.0);
        }
        return 1.0;
    }

    private Double getFallbackPriceFromSymbol(String symbol) {
        // Простая логика для получения fallback из символа
        if (symbol.contains("BTC")) {
            return getFallbackPrice("BTC", symbol.replace("BTC", ""));
        } else if (symbol.contains("ETH")) {
            return getFallbackPrice("ETH", symbol.replace("ETH", ""));
        } else if (symbol.contains("USDT")) {
            return getFallbackPrice("USDT", symbol.replace("USDT", ""));
        }
        return 1.0;
    }

    private void enforceRateLimit(String apiName) {
        Long lastCall = lastCallTimestamps.get(apiName);
        if (lastCall != null) {
            long timeSinceLastCall = System.currentTimeMillis() - lastCall;
            if (timeSinceLastCall < RATE_LIMIT_DELAY) {
                try {
                    Thread.sleep(RATE_LIMIT_DELAY - timeSinceLastCall);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        lastCallTimestamps.put(apiName, System.currentTimeMillis());
    }

    /**
     * Очистка кэша каждые 5 минут
     */
    @Scheduled(fixedRate = 300000)
    public void clearCache() {
        priceCache.clear();
    }
}