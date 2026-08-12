package com.cms.service;

import com.cms.entity.BlogPostEntity;
import com.cms.entity.PageStatus;
import com.cms.entity.PostReviewEntity;
import com.cms.entity.ReviewState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewNotifierTest {

    private TelegramClient telegram;
    private ReviewNotifier notifier;

    @BeforeEach
    void setUp() {
        telegram = mock(TelegramClient.class);
        notifier = new ReviewNotifier(telegram, "https://clinic.ru");
    }

    private static PostReviewEntity review(String body) {
        BlogPostEntity post = new BlogPostEntity();
        post.setId(3);
        post.setSlug("stress-i-zdorove");
        post.setTitle("Стресс и здоровье");
        post.setBody(body);
        post.setMetaDescription("Как стресс влияет на организм");
        post.setKeywords("стресс, здоровье");
        post.setSourceTopic("Стресс и его влияние на здоровье");
        post.setStatus(PageStatus.DRAFT);

        PostReviewEntity review = new PostReviewEntity();
        review.setId(11);
        review.setBlogPost(post);
        review.setState(ReviewState.PENDING);
        return review;
    }

    @Test
    void previewStartsWithAHeaderCarryingTitleAndSeoFields() {
        when(telegram.sendMessage(anyString(), any())).thenReturn(100L);

        notifier.sendPreview(review("<p>Текст</p>"), true);

        ArgumentCaptor<String> texts = ArgumentCaptor.forClass(String.class);
        verify(telegram, atLeastOnce()).sendMessage(texts.capture(), any());
        assertThat(texts.getAllValues().getFirst())
                .contains("Стресс и здоровье")
                .contains("Как стресс влияет на организм")
                .contains("стресс, здоровье");
    }

    @Test
    void buttonsRideOnlyOnTheLastMessage() {
        // A long body forces several chunks; only the final one may carry the keyboard.
        String body = "<p>" + "Предложение про здоровье. ".repeat(4) + "</p>";
        when(telegram.sendMessage(anyString(), any())).thenReturn(100L, 101L, 102L, 103L, 104L);

        notifier.sendPreview(review(body.repeat(80)), true);

        ArgumentCaptor<Map<String, Object>> markups = ArgumentCaptor.forClass(Map.class);
        verify(telegram, atLeastOnce()).sendMessage(anyString(), markups.capture());
        List<Map<String, Object>> all = markups.getAllValues();
        assertThat(all).hasSizeGreaterThan(1);
        assertThat(all.subList(0, all.size() - 1)).allSatisfy(m -> assertThat(m).isNull());
        assertThat(all.getLast()).isNotNull();
    }

    @Test
    void returnsTheIdOfTheMessageHoldingTheButtons() {
        when(telegram.sendMessage(anyString(), any())).thenReturn(100L, 101L);

        long messageId = notifier.sendPreview(review("<p>Короткий текст</p>"), true);

        assertThat(messageId).isEqualTo(101L);
    }

    @Test
    void callbackDataEncodesReviewIdAndAction() {
        when(telegram.sendMessage(anyString(), any())).thenReturn(100L);

        notifier.sendPreview(review("<p>Текст</p>"), true);

        ArgumentCaptor<Map<String, Object>> markups = ArgumentCaptor.forClass(Map.class);
        verify(telegram, atLeastOnce()).sendMessage(anyString(), markups.capture());
        assertThat(markups.getAllValues().getLast().toString())
                .contains("rv:11:approve")
                .contains("rv:11:reject")
                .contains("rv:11:edit");
    }

    @Test
    void editButtonDisappearsWhenRevisionsAreExhausted() {
        when(telegram.sendMessage(anyString(), any())).thenReturn(100L);

        notifier.sendPreview(review("<p>Текст</p>"), false);

        ArgumentCaptor<Map<String, Object>> markups = ArgumentCaptor.forClass(Map.class);
        verify(telegram, atLeastOnce()).sendMessage(anyString(), markups.capture());
        String markup = markups.getAllValues().getLast().toString();
        assertThat(markup).contains("rv:11:approve").contains("rv:11:reject");
        assertThat(markup).doesNotContain("rv:11:edit");
    }

    @Test
    void headerEscapesMarkupCharactersInTheTitle() {
        PostReviewEntity review = review("<p>Текст</p>");
        review.getBlogPost().setTitle("Давление < 120 & пульс");
        when(telegram.sendMessage(anyString(), any())).thenReturn(100L);

        notifier.sendPreview(review, true);

        ArgumentCaptor<String> texts = ArgumentCaptor.forClass(String.class);
        verify(telegram, atLeastOnce()).sendMessage(texts.capture(), any());
        assertThat(texts.getAllValues().getFirst()).contains("&lt; 120 &amp;");
    }

    @Test
    void publishedNoticeLinksToTheLivePost() {
        BlogPostEntity post = review("<p>Текст</p>").getBlogPost();

        notifier.notifyPublished(post);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(telegram).sendMessage(text.capture(), any());
        assertThat(text.getValue()).contains("https://clinic.ru/blog/stress-i-zdorove");
    }
}
