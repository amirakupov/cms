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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin transport over the Telegram Bot API. Knows nothing about blog posts or reviews.
 *
 * <p>The bot token sits in the request path, so request URLs must never be logged - only
 * method names.
 */
@Service
public class TelegramClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final long adminChatId;
    private final int pollTimeoutSeconds;
    private final boolean enabled;

    public TelegramClient(
            @Value("${telegram.bot-token:}") String botToken,
            @Value("${telegram.admin-chat-id:}") String adminChatId,
            @Value("${telegram.poll-timeout-seconds:30}") int pollTimeoutSeconds,
            @Value("${telegram.read-timeout-seconds:40}") long readTimeoutSeconds,
            @Value("${telegram.connect-timeout-seconds:10}") long connectTimeoutSeconds,
            ObjectMapper objectMapper) {

        this.objectMapper = objectMapper;
        this.pollTimeoutSeconds = pollTimeoutSeconds;
        this.enabled = !botToken.isBlank() && !adminChatId.isBlank();
        this.adminChatId = this.enabled ? Long.parseLong(adminChatId.trim()) : 0L;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds));
        // Must exceed poll-timeout-seconds, otherwise every idle long poll dies on read.
        factory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));

        this.restClient = RestClient.builder()
                .baseUrl("https://api.telegram.org/bot" + botToken)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        if (!this.enabled) {
            log.info("telegram.bot-token or telegram.admin-chat-id is empty; the review bot is off");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long adminChatId() {
        return adminChatId;
    }

    /** Sends HTML text to the admin chat and returns the new message id. */
    public long sendMessage(String html, Map<String, Object> replyMarkup) {
        Map<String, Object> body = new HashMap<>();
        body.put("chat_id", adminChatId);
        body.put("text", html);
        body.put("parse_mode", "HTML");
        body.put("disable_web_page_preview", true);
        if (replyMarkup != null) {
            body.put("reply_markup", replyMarkup);
        }
        return call("sendMessage", body).path("message_id").asLong();
    }

    /** Drops the inline keyboard so an already-handled action cannot be clicked again. */
    public void clearKeyboard(long messageId) {
        call("editMessageReplyMarkup", Map.of(
                "chat_id", adminChatId,
                "message_id", messageId,
                "reply_markup", Map.of("inline_keyboard", List.of())));
    }

    /** Must be called for every callback, or the button spins in the client forever. */
    public void answerCallback(String callbackQueryId, String text) {
        call("answerCallbackQuery", Map.of(
                "callback_query_id", callbackQueryId,
                "text", text == null ? "" : text));
    }

    /** Long-polls for updates newer than offset. Blocks up to poll-timeout-seconds. */
    public JsonNode getUpdates(long offset) {
        return call("getUpdates", Map.of(
                "offset", offset,
                "timeout", pollTimeoutSeconds,
                "allowed_updates", List.of("message", "callback_query")));
    }

    private JsonNode call(String method, Map<String, Object> body) {
        String response = restClient.post()
                .uri("/" + method)
                .body(body)
                .retrieve()
                .body(String.class);

        if (response == null || response.isBlank()) {
            throw new TelegramException(method + " returned an empty body");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(response);
        } catch (Exception e) {
            throw new TelegramException("Could not parse the " + method + " response", e);
        }
        if (!root.path("ok").asBoolean(false)) {
            throw new TelegramException(
                    method + " failed: " + root.path("description").asText("unknown error"));
        }
        return root.path("result");
    }

    public static class TelegramException extends RuntimeException {
        public TelegramException(String message) {
            super(message);
        }

        public TelegramException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
