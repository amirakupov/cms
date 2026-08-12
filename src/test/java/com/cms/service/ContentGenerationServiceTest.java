package com.cms.service;

import com.cms.repo.BlogPostRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContentGenerationServiceTest {

    private BlogPostRepository repository;
    private ContentGenerationService service;

    @BeforeEach
    void setUp() {
        repository = mock(BlogPostRepository.class);
        service = new ContentGenerationService(
                mock(YandexGptService.class), repository, new ObjectMapper(), true);
    }

    @Test
    void parsesJsonWrappedInMarkdownFences() {
        JsonNode node = service.parseJsonPayload("""
                ```json
                {"title": "Заголовок", "body": "<p>Текст</p>"}
                ```""");
        assertThat(node.path("title").asText()).isEqualTo("Заголовок");
    }

    @Test
    void keepsBackticksInsideTheArticleBody() {
        JsonNode node = service.parseJsonPayload(
                "{\"title\": \"T\", \"body\": \"<p>Пример: ```код```</p>\"}");
        assertThat(node.path("body").asText()).contains("```код```");
    }

    @Test
    void toleratesProseAroundTheJson() {
        JsonNode node = service.parseJsonPayload(
                "Конечно! Вот статья:\n{\"title\": \"T\", \"body\": \"B\"}\nГотово.");
        assertThat(node.path("title").asText()).isEqualTo("T");
    }

    @Test
    void rejectsTruncatedJson() {
        assertThatThrownBy(() -> service.parseJsonPayload("{\"title\": \"T\", \"body\": \"unterminat"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsAnswerWithoutJson() {
        assertThatThrownBy(() -> service.parseJsonPayload("Извините, не могу помочь."))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no JSON object");
    }

    @Test
    void picksAnUnusedTopicFirst() {
        when(repository.findUsedTopics()).thenReturn(List.of(
                "Профилактика сердечно-сосудистых заболеваний",
                "Как подготовиться к первому визиту к терапевту"));

        ContentGenerationService.TopicChoice choice = service.pickTopic();

        assertThat(choice.repeatCount()).isZero();
        assertThat(choice.topic()).isEqualTo("Современные методы диагностики заболеваний");
    }

    @Test
    void topicRotationIgnoresManuallyCreatedPosts() {
        // Manual posts have no sourceTopic, so they must not shift the rotation.
        when(repository.findUsedTopics()).thenReturn(List.of());

        assertThat(service.pickTopic().topic())
                .isEqualTo("Профилактика сердечно-сосудистых заболеваний");
    }

    @Test
    void reportsRepeatCountOnceEveryTopicIsUsed() {
        when(repository.findUsedTopics()).thenReturn(List.of(
                "Профилактика сердечно-сосудистых заболеваний",
                "Как подготовиться к первому визиту к терапевту",
                "Современные методы диагностики заболеваний",
                "Здоровое питание: рекомендации врачей",
                "Когда нужно обращаться к неврологу",
                "Вакцинация взрослых: что нужно знать",
                "Профилактика заболеваний позвоночника",
                "Чек-ап: зачем нужны регулярные обследования",
                "Стресс и его влияние на здоровье",
                "Сезонные заболевания: профилактика и лечение"));

        ContentGenerationService.TopicChoice choice = service.pickTopic();

        assertThat(choice.repeatCount()).isEqualTo(1);
    }
}
