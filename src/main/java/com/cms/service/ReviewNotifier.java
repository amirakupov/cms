package com.cms.service;

import com.cms.entity.BlogPostEntity;
import com.cms.entity.PostReviewEntity;
import com.cms.util.TelegramHtmlFormatter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Owns every message the review pipeline sends to the admin, and the button layout. */
@Service
public class ReviewNotifier {

    private final TelegramClient telegram;
    private final String siteBaseUrl;

    public ReviewNotifier(TelegramClient telegram,
                          @Value("${app.site-base-url:}") String siteBaseUrl) {
        this.telegram = telegram;
        this.siteBaseUrl = siteBaseUrl.endsWith("/")
                ? siteBaseUrl.substring(0, siteBaseUrl.length() - 1)
                : siteBaseUrl;
    }

    /**
     * Sends the header plus the whole article and returns the id of the message carrying
     * the buttons - the only one the pipeline later needs to edit.
     *
     * @param allowEdit false once the revision budget is spent, which hides the edit button
     */
    public long sendPreview(PostReviewEntity review, boolean allowEdit) {
        List<String> chunks = new ArrayList<>();
        chunks.add(header(review));
        chunks.addAll(TelegramHtmlFormatter.toMessages(review.getBlogPost().getBody()));

        long lastMessageId = 0;
        for (int i = 0; i < chunks.size(); i++) {
            boolean last = i == chunks.size() - 1;
            lastMessageId = telegram.sendMessage(
                    chunks.get(i), last ? keyboard(review.getId(), allowEdit) : null);
        }
        return lastMessageId;
    }

    public void notifyPublished(BlogPostEntity post) {
        telegram.sendMessage("✅ Опубликовано: " + siteBaseUrl + "/blog/" + post.getSlug(), null);
    }

    public void notifyRejected() {
        telegram.sendMessage("❌ Отклонено. Следующая попытка по расписанию.", null);
    }

    public void askForInstruction() {
        telegram.sendMessage("""
                ✏️ Пришли замечания одним сообщением.

                Например: сократи, убери раздел про диагностику, добавь про детей.""", null);
    }

    public void notifyRegenerating() {
        telegram.sendMessage("⏳ Переписываю…", null);
    }

    public void notifyRegenerationFailed(String reason) {
        telegram.sendMessage("⚠️ Не удалось переписать: " + TelegramHtmlFormatter.escape(reason)
                + "\nПрежняя версия в силе.", null);
    }

    public void notifyRevisionLimit(int maxRevisions) {
        telegram.sendMessage(
                "Достигнут лимит правок (" + maxRevisions + "). Опубликовать или отклонить?", null);
    }

    public void notifyIdle() {
        telegram.sendMessage(
                "Сейчас нет поста на ревью. /generate — сгенерировать новый прямо сейчас.", null);
    }

    private String header(PostReviewEntity review) {
        BlogPostEntity post = review.getBlogPost();
        return """
                📝 Новый пост на одобрение

                <b>%s</b>

                Тема: %s
                Ревизия: %d
                Meta: %s
                Ключевые слова: %s"""
                .formatted(
                        TelegramHtmlFormatter.escape(post.getTitle()),
                        TelegramHtmlFormatter.escape(orDash(post.getSourceTopic())),
                        review.getRevisionCount(),
                        TelegramHtmlFormatter.escape(orDash(post.getMetaDescription())),
                        TelegramHtmlFormatter.escape(orDash(post.getKeywords())));
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private Map<String, Object> keyboard(Integer reviewId, boolean allowEdit) {
        List<Map<String, String>> firstRow = new ArrayList<>();
        firstRow.add(button("✅ Опубликовать", reviewId, "approve"));
        if (allowEdit) {
            firstRow.add(button("✏️ Правки", reviewId, "edit"));
        }
        return Map.of("inline_keyboard",
                List.of(firstRow, List.of(button("❌ Отклонить", reviewId, "reject"))));
    }

    /** callback_data is capped at 64 bytes by Telegram, so it carries ids rather than text. */
    private static Map<String, String> button(String label, Integer reviewId, String action) {
        return Map.of("text", label, "callback_data", "rv:" + reviewId + ":" + action);
    }
}
