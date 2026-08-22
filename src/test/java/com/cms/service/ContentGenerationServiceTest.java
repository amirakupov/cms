package com.cms.service;

import com.cms.entity.BlogPostEntity;
import com.cms.entity.PageStatus;
import com.cms.repo.BlogPostRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContentGenerationServiceTest {

    private BlogPostRepository repository;
    private YandexGptService gptService;
    private ContentGenerationService service;

    @BeforeEach
    void setUp() {
        repository = mock(BlogPostRepository.class);
        gptService = mock(YandexGptService.class);
        // Zero backoff: the retry-path test would otherwise sit through 15 seconds of sleeps.
        service = new ContentGenerationService(gptService, repository, new ObjectMapper(), 0L);
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
                "Мигрень и головная боль напряжения: как отличить",
                "Когда головная боль требует срочного обращения к врачу"));

        ContentGenerationService.TopicChoice choice = service.pickTopic();

        assertThat(choice.repeatCount()).isZero();
        assertThat(choice.topic()).isEqualTo("Головокружение: причины и необходимые обследования");
    }

    @Test
    void topicRotationIgnoresManuallyCreatedPosts() {
        // Manual posts have no sourceTopic, so they must not shift the rotation.
        when(repository.findUsedTopics()).thenReturn(List.of());

        assertThat(service.pickTopic().topic())
                .isEqualTo("Мигрень и головная боль напряжения: как отличить");
    }

    @Test
    void reportsRepeatCountOnceEveryTopicIsUsed() {
        when(repository.findUsedTopics()).thenReturn(List.of(
                "Мигрень и головная боль напряжения: как отличить",
                "Когда головная боль требует срочного обращения к врачу",
                "Головокружение: причины и необходимые обследования",
                "Боль в пояснице: когда идти к неврологу",
                "Грыжа межпозвонкового диска: симптомы и методы лечения",
                "Защемление седалищного нерва: что делать при боли в ноге",
                "Онемение и покалывание в руках: о чём говорит симптом",
                "Профилактика инсульта: факторы риска и первые признаки",
                "Восстановление после инсульта: этапы и сроки",
                "Бессонница: причины и подходы к лечению",
                "Невропатия лицевого нерва: первые действия",
                "Тремор рук: когда это повод обследоваться",
                "Снижение памяти и концентрации: когда это не норма",
                "Как проходит приём невролога и какие обследования назначают"));

        ContentGenerationService.TopicChoice choice = service.pickTopic();

        assertThat(choice.repeatCount()).isEqualTo(1);
    }

    private static BlogPostEntity existingPost() {
        BlogPostEntity post = new BlogPostEntity();
        post.setId(7);
        post.setSlug("stress-i-ego-vliyanie-na-zdorove");
        post.setTitle("Стресс и его влияние на здоровье");
        post.setBody("<p>Старый текст</p>");
        post.setStatus(PageStatus.DRAFT);
        post.setAiGenerated(true);
        return post;
    }

    @Test
    void regenerationSendsBothCurrentTextAndEditorInstruction() {
        BlogPostEntity post = existingPost();
        when(gptService.generate(anyString(), anyString())).thenReturn(
                "{\"title\": \"Стресс и его влияние на здоровье\", \"body\": \"<p>Новый текст</p>\"}");
        when(repository.saveAndFlush(any(BlogPostEntity.class))).thenAnswer(i -> i.getArgument(0));

        service.regenerate(post, "сократи и убери раздел про диагностику");

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(gptService).generate(anyString(), userPrompt.capture());
        assertThat(userPrompt.getValue())
                .contains("Старый текст")
                .contains("сократи и убери раздел про диагностику");
    }

    @Test
    void regenerationSanitizesTheNewHtml() {
        BlogPostEntity post = existingPost();
        when(gptService.generate(anyString(), anyString())).thenReturn(
                "{\"title\": \"Стресс и его влияние на здоровье\","
                        + " \"body\": \"<p>Текст</p><script>alert(1)</script>\"}");
        when(repository.saveAndFlush(any(BlogPostEntity.class))).thenAnswer(i -> i.getArgument(0));

        service.regenerate(post, "правки");

        assertThat(post.getBody()).doesNotContain("script").contains("Текст");
    }

    @Test
    void regenerationKeepsSlugWhenTitleIsUnchanged() {
        BlogPostEntity post = existingPost();
        when(gptService.generate(anyString(), anyString())).thenReturn(
                "{\"title\": \"Стресс и его влияние на здоровье\", \"body\": \"<p>Новый</p>\"}");
        when(repository.saveAndFlush(any(BlogPostEntity.class))).thenAnswer(i -> i.getArgument(0));

        service.regenerate(post, "правки");

        // uniqueSlug would otherwise see the post's own slug as taken and append "-1".
        assertThat(post.getSlug()).isEqualTo("stress-i-ego-vliyanie-na-zdorove");
        verify(repository, never()).findSlugsStartingWith(anyString());
    }

    @Test
    void regenerationRederivesSlugWhenTitleChanged() {
        BlogPostEntity post = existingPost();
        when(gptService.generate(anyString(), anyString())).thenReturn(
                "{\"title\": \"Как стресс влияет на сердце\", \"body\": \"<p>Новый</p>\"}");
        when(repository.findSlugsStartingWith(anyString())).thenReturn(List.of());
        when(repository.saveAndFlush(any(BlogPostEntity.class))).thenAnswer(i -> i.getArgument(0));

        service.regenerate(post, "правки");

        assertThat(post.getSlug()).isEqualTo("kak-stress-vliyaet-na-serdtse");
        assertThat(post.getTitle()).isEqualTo("Как стресс влияет на сердце");
    }

    @Test
    void regenerationFailsLoudlyWhenTheModelKeepsReturningGarbage() {
        BlogPostEntity post = existingPost();
        when(gptService.generate(anyString(), anyString())).thenReturn("Извините, не могу помочь.");

        assertThatThrownBy(() -> service.regenerate(post, "правки"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void systemPromptDemandsTheGeoStructure() {
        // GEO hinges on a direct opening answer and an extractable FAQ block.
        assertThat(service.systemPrompt())
                .contains("Частые вопросы")
                .contains("прямой сжатый ответ");
    }
}
