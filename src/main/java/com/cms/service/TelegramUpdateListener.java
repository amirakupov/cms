package com.cms.service;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Long-polls Telegram for admin actions.
 *
 * <p>Two dedicated single-thread executors, not the shared scheduling pool: a blocking
 * getUpdates would permanently occupy one of the two threads configured in
 * spring.task.scheduling.pool.size, and a regeneration can hold a thread for minutes while
 * GPT answers. Keeping the poll loop and the work queue apart means the bot stays
 * responsive while an article is being rewritten; a single work thread also serialises
 * admin actions, so two updates cannot race the same review.
 */
@Service
public class TelegramUpdateListener {

    private static final Logger log = LoggerFactory.getLogger(TelegramUpdateListener.class);

    private static final long POLL_BACKOFF_MS = 5_000;
    private static final String CALLBACK_PREFIX = "rv";

    private final TelegramClient telegram;
    private final PostReviewService reviewService;
    private final ContentReviewScheduler scheduler;
    private final ReviewNotifier notifier;

    private final ExecutorService pollExecutor =
            Executors.newSingleThreadExecutor(runnable -> named(runnable, "telegram-poll"));
    private final ExecutorService workExecutor =
            Executors.newSingleThreadExecutor(runnable -> named(runnable, "telegram-work"));

    private volatile boolean running;
    private long offset;

    public TelegramUpdateListener(TelegramClient telegram,
                                  PostReviewService reviewService,
                                  ContentReviewScheduler scheduler,
                                  ReviewNotifier notifier) {
        this.telegram = telegram;
        this.reviewService = reviewService;
        this.scheduler = scheduler;
        this.notifier = notifier;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!telegram.isEnabled()) {
            log.info("Telegram is not configured; the review bot stays off");
            return;
        }
        running = true;
        pollExecutor.submit(this::pollLoop);
        log.info("Telegram review bot started");
    }

    @PreDestroy
    public void stop() {
        running = false;
        pollExecutor.shutdownNow();
        workExecutor.shutdown();
    }

    private void pollLoop() {
        while (running) {
            try {
                JsonNode updates = telegram.getUpdates(offset);
                for (JsonNode update : updates) {
                    offset = Math.max(offset, update.path("update_id").asLong() + 1);
                    JsonNode pending = update;
                    workExecutor.submit(() -> handleQuietly(pending));
                }
            } catch (Exception e) {
                // Never let the loop die: one network blip would silently kill the bot.
                if (running) {
                    log.warn("Telegram poll failed: {}", e.getMessage());
                    sleepQuietly();
                }
            }
        }
    }

    private void handleQuietly(JsonNode update) {
        try {
            handleUpdate(update);
        } catch (Exception e) {
            log.error("Failed to handle a Telegram update", e);
        }
    }

    void handleUpdate(JsonNode update) {
        if (!isFromAdmin(update)) {
            log.debug("Dropping an update that did not come from the admin chat");
            return;
        }
        if (update.has("callback_query")) {
            handleCallback(update.path("callback_query"));
        } else if (update.has("message")) {
            handleMessage(update.path("message"));
        }
    }

    /** The access control of this whole feature: anything outside the admin chat is dropped. */
    boolean isFromAdmin(JsonNode update) {
        long chatId = update.has("callback_query")
                ? update.at("/callback_query/message/chat/id").asLong()
                : update.at("/message/chat/id").asLong();
        return chatId == telegram.adminChatId();
    }

    private void handleCallback(JsonNode callback) {
        String callbackId = callback.path("id").asText();
        String[] parts = callback.path("data").asText("").split(":");

        if (parts.length != 3 || !CALLBACK_PREFIX.equals(parts[0])) {
            telegram.answerCallback(callbackId, "");
            return;
        }
        int reviewId;
        try {
            reviewId = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            telegram.answerCallback(callbackId, "");
            return;
        }
        // Answered first: Telegram keeps the button spinning until it hears back, and the
        // action itself may take a while.
        telegram.answerCallback(callbackId, "Принято");
        reviewService.handleAction(reviewId, parts[2]);
    }

    private void handleMessage(JsonNode message) {
        String text = message.path("text").asText("").strip();
        if (text.isEmpty()) {
            return;
        }
        if (text.startsWith("/generate")) {
            scheduler.generate(true);
            return;
        }
        if (text.startsWith("/")) {
            notifier.notifyIdle();
            return;
        }
        reviewService.applyInstruction(text);
    }

    private static Thread named(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(POLL_BACKOFF_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
