package com.cms.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class YandexGptService {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String modelUri;

    public YandexGptService(
            @Value("${yandex.gpt.api-key}") String apiKey,
            @Value("${yandex.gpt.folder-id}") String folderId,
            @Value("${yandex.gpt.model:yandexgpt/latest}") String model,
            ObjectMapper objectMapper
    ) {
        this.objectMapper = objectMapper;
        this.modelUri = "gpt://" + folderId + "/" + model;
        this.restClient = RestClient.builder()
                .baseUrl("https://llm.api.cloud.yandex.net")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Api-Key " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public String generate(String systemPrompt, String userPrompt) {
        Map<String, Object> body = Map.of(
                "modelUri", modelUri,
                "completionOptions", Map.of(
                        "stream", false,
                        "temperature", 0.6,
                        "maxTokens", 2000
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

        try {
            JsonNode root = objectMapper.readTree(response);
            return root.at("/result/alternatives/0/message/text").asText();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse YandexGPT response", e);
        }
    }
}
