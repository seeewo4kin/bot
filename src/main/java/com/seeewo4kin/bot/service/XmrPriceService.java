package com.seeewo4kin.bot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class XmrPriceService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // Кэш для хранения цен XMR
    private final Map<String, BigDecimal> priceCache = new ConcurrentHashMap<>();

    // Время жизни кэша (5 минут)
    private static final long CACHE_DURATION_MS = 5 * 60 * 1000;
    private long lastUpdateTime = 0;

    // Fallback значения для XMR на 2025 год
    private static final BigDecimal FALLBACK_XMR_USD = BigDecimal.valueOf(180.0);
    private static final BigDecimal FALLBACK_XMR_RUB = BigDecimal.valueOf(16000.0);
    private static final BigDecimal FALLBACK_XMR_EUR = BigDecimal.valueOf(165.0);

    public XmrPriceService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Получение текущей цены XMR в указанной валюте
     */
    public BigDecimal getXmrPrice(String currency) {
        // Проверяем кэш
        if (isCacheValid()) {
            BigDecimal cachedPrice = priceCache.get(currency.toUpperCase());
            if (cachedPrice != null) {
                return cachedPrice;
            }
        }

        // Обновляем цены
        updateXmrPrices();

        // Возвращаем цену или fallback
        return priceCache.getOrDefault(currency.toUpperCase(), getFallbackPrice(currency));
    }

    /**
     * Получение цен XMR во всех валютах
     */
    public Map<String, BigDecimal> getXmrPrices() {
        if (!isCacheValid()) {
            updateXmrPrices();
        }

        Map<String, BigDecimal> result = new HashMap<>(priceCache);
        if (result.isEmpty()) {
            // Возвращаем fallback значения
            result.put("USD", FALLBACK_XMR_USD);
            result.put("RUB", FALLBACK_XMR_RUB);
            result.put("EUR", FALLBACK_XMR_EUR);
        }
        return result;
    }

    /**
     * Обновление цен XMR через CoinGecko API
     */
    private void updateXmrPrices() {
        try {
            // CoinGecko API endpoint для XMR
            String url = "https://api.coingecko.com/api/v3/simple/price?ids=monero&vs_currencies=usd,rub,eur";

            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);
            JsonNode xmrNode = root.path("monero");

            if (!xmrNode.isMissingNode()) {
                BigDecimal usdPrice = BigDecimal.valueOf(xmrNode.path("usd").asDouble());
                BigDecimal rubPrice = BigDecimal.valueOf(xmrNode.path("rub").asDouble());
                BigDecimal eurPrice = BigDecimal.valueOf(xmrNode.path("eur").asDouble());

                // Проверяем валидность цен
                if (usdPrice.compareTo(BigDecimal.ZERO) > 0) {
                    priceCache.put("USD", usdPrice);
                }
                if (rubPrice.compareTo(BigDecimal.ZERO) > 0) {
                    priceCache.put("RUB", rubPrice);
                }
                if (eurPrice.compareTo(BigDecimal.ZERO) > 0) {
                    priceCache.put("EUR", eurPrice);
                }

                lastUpdateTime = System.currentTimeMillis();
                System.out.println("XMR prices updated from CoinGecko: USD=" + usdPrice + ", RUB=" + rubPrice + ", EUR=" + eurPrice);
            } else {
                System.err.println("XMR data not found in CoinGecko response");
                useFallbackPrices();
            }

        } catch (Exception e) {
            System.err.println("Error updating XMR prices from CoinGecko: " + e.getMessage());
            useFallbackPrices();
        }
    }

    /**
     * Использование fallback значений
     */
    private void useFallbackPrices() {
        priceCache.put("USD", FALLBACK_XMR_USD);
        priceCache.put("RUB", FALLBACK_XMR_RUB);
        priceCache.put("EUR", FALLBACK_XMR_EUR);
        lastUpdateTime = System.currentTimeMillis();
        System.out.println("Using fallback XMR prices");
    }

    /**
     * Проверка валидности кэша
     */
    private boolean isCacheValid() {
        return !priceCache.isEmpty() &&
               (System.currentTimeMillis() - lastUpdateTime) < CACHE_DURATION_MS;
    }

    /**
     * Получение fallback цены
     */
    private BigDecimal getFallbackPrice(String currency) {
        switch (currency.toUpperCase()) {
            case "USD": return FALLBACK_XMR_USD;
            case "RUB": return FALLBACK_XMR_RUB;
            case "EUR": return FALLBACK_XMR_EUR;
            default: return FALLBACK_XMR_USD;
        }
    }

    /**
     * Принудительное обновление цен (для тестирования)
     */
    public void forcePriceUpdate() {
        lastUpdateTime = 0; // Сбрасываем время кэша
        updateXmrPrices();
    }

    /**
     * Получение статуса сервиса
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("service", "XmrPriceService");
        status.put("api", "CoinGecko");
        status.put("cacheValid", isCacheValid());
        status.put("lastUpdate", lastUpdateTime);
        status.put("cachedPrices", new HashMap<>(priceCache));
        return status;
    }
}
