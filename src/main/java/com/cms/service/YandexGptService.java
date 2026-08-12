package com.cms.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class YandexGptService {

    private static final Logger log = LoggerFactory.getLogger(YandexGptService.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String modelUri;
    private final int maxTokens;
    private final double temperature;

    public YandexGptService(
            @Value("${yandex.gpt.api-key}") String apiKey,
            @Value("${yandex.gpt.folder-id}") String folderId,
            @Value("${yandex.gpt.model:yandexgpt/latest}") String model,
            @Value("${yandex.gpt.max-tokens:8000}") int maxTokens,
            @Value("${yandex.gpt.temperature:0.6}") double temperature,
            @Value("${yandex.gpt.connect-timeout-seconds:10}") long connectTimeoutSeconds,
            @Value("${yandex.gpt.read-timeout-seconds:180}") long readTimeoutSeconds,
            ObjectMapper objectMapper
    ) {
        this.objectMapper = objectMapper;
        this.modelUri = "gpt://" + folderId + "/" + model;
        this.maxTokens = maxTokens;
        this.temperature = temperature;

        // Without explicit timeouts a stalled connection blocks the calling thread forever.
        // On the scheduler thread that would silently stop every other @Scheduled task.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds));
        requestFactory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));

        this.restClient = RestClient.builder()
                .baseUrl("https://llm.api.cloud.yandex.net")
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Api-Key " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public String generate(String systemPrompt, String userPrompt) {
        Map<String, Object> body = Map.of(
                "modelUri", modelUri,
                "completionOptions", Map.of(
                        "stream", false,
                        "temperature", temperature,
                        "maxTokens", maxTokens
                ),
                "messages", List.of(
                        Map.of("role", "system", "text", systemPrompt),
                        Map.of("role", "user", "text", userPrompt)
                )
        );

        String response = restClient.post()
                .uri("/foundationModels/v1/completion")
                .body(body)
                .retrieve()
                .body(String.class);

        if (response == null || response.isBlank()) {
            throw new GptException("YandexGPT returned an empty response body");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(response);
        } catch (Exception e) {
            throw new GptException("Failed to parse YandexGPT response envelope", e);
        }

        JsonNode alternative = root.at("/result/alternatives/0");
        if (alternative.isMissingNode()) {
            throw new GptException("YandexGPT response contained no alternatives: " + truncate(response));
        }

        // ALTERNATIVE_STATUS_TRUNCATED_FINAL means maxTokens cut the answer off mid-way,
        // which reliably produces unparseable JSON downstream.
        String status = alternative.path("status").asText("");
        if (status.contains("TRUNCATED")) {
            throw new GptException("YandexGPT answer was truncated (status=" + status
                    + "); raise yandex.gpt.max-tokens (current: " + maxTokens + ")");
        }

        String text = alternative.at("/message/text").asText("");
        if (text.isBlank()) {
            throw new GptException("YandexGPT returned an empty completion");
        }

        log.debug("YandexGPT usage: {}", root.at("/result/usage"));
        return text;
    }

    private static String truncate(String s) {
        return s.length() <= 500 ? s : s.substring(0, 500) + "...";
    }

    public static class GptException extends RuntimeException {
        public GptException(String message) {
            super(message);
        }

        public GptException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
