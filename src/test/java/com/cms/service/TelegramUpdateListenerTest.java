package com.cms.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramUpdateListenerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long ADMIN = 555L;

    private TelegramClient telegram;
    private PostReviewService reviewService;
    private ContentReviewScheduler scheduler;
    private ReviewNotifier notifier;
    private TelegramUpdateListener listener;

    @BeforeEach
    void setUp() {
        telegram = mock(TelegramClient.class);
        reviewService = mock(PostReviewService.class);
        scheduler = mock(ContentReviewScheduler.class);
        notifier = mock(ReviewNotifier.class);
        when(telegram.adminChatId()).thenReturn(ADMIN);
        listener = new TelegramUpdateListener(telegram, reviewService, scheduler, notifier);
    }

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode callback(long chatId, String data) {
        return json("""
                {"update_id": 1, "callback_query": {"id": "cb1", "data": "%s",
                 "message": {"message_id": 100, "chat": {"id": %d}}}}"""
                .formatted(data, chatId));
    }

    private static JsonNode message(long chatId, String text) {
        return json("""
                {"update_id": 2, "message": {"message_id": 101,
                 "chat": {"id": %d}, "text": "%s"}}""".formatted(chatId, text));
    }

    @Test
    void acceptsCallbacksFromTheAdminChat() {
        assertThat(listener.isFromAdmin(callback(ADMIN, "rv:11:approve"))).isTrue();
    }

    @Test
    void rejectsUpdatesFromAnyOtherChat() {
        // The only thing standing between a stranger and publishing to the clinic's site.
        assertThat(listener.isFromAdmin(callback(999L, "rv:11:approve"))).isFalse();
        assertThat(listener.isFromAdmin(message(999L, "привет"))).isFalse();
    }

    @Test
    void strangersCannotTriggerAnyAction() {
        listener.handleUpdate(callback(999L, "rv:11:approve"));
        listener.handleUpdate(message(999L, "сократи"));

        verify(reviewService, never()).handleAction(anyInt(), anyString());
        verify(reviewService, never()).applyInstruction(anyString());
        verify(scheduler, never()).generate(anyBoolean());
    }

    @Test
    void callbackDataIsRoutedToTheReviewService() {
        listener.handleUpdate(callback(ADMIN, "rv:11:approve"));

        verify(reviewService).handleAction(11, "approve");
    }

    @Test
    void callbackIsAnsweredBeforeTheWorkStarts() {
        // Telegram spins the button until answerCallbackQuery arrives.
        listener.handleUpdate(callback(ADMIN, "rv:11:reject"));

        verify(telegram).answerCallback(anyString(), anyString());
        verify(reviewService).handleAction(11, "reject");
    }

    @Test
    void malformedCallbackDataIsAnsweredAndDropped() {
        listener.handleUpdate(callback(ADMIN, "garbage"));

        verify(telegram).answerCallback(anyString(), anyString());
        verify(reviewService, never()).handleAction(anyInt(), anyString());
    }

    @Test
    void nonNumericReviewIdIsDropped() {
        listener.handleUpdate(callback(ADMIN, "rv:abc:approve"));

        verify(reviewService, never()).handleAction(anyInt(), anyString());
    }

    @Test
    void plainTextBecomesAnEditInstruction() {
        listener.handleUpdate(message(ADMIN, "сократи вдвое"));

        verify(reviewService).applyInstruction("сократи вдвое");
    }

    @Test
    void generateCommandForcesARun() {
        listener.handleUpdate(message(ADMIN, "/generate"));

        verify(scheduler).generate(true);
        verify(reviewService, never()).applyInstruction(anyString());
    }

    @Test
    void unknownCommandIsNotTreatedAsAnInstruction() {
        listener.handleUpdate(message(ADMIN, "/whatever"));

        verify(reviewService, never()).applyInstruction(anyString());
        verify(notifier).notifyIdle();
    }

    @Test
    void emptyMessageIsIgnored() {
        listener.handleUpdate(message(ADMIN, ""));

        verify(reviewService, never()).applyInstruction(anyString());
    }
}
