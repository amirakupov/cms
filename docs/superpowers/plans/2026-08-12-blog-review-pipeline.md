# Blog Review Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Сгенерированный по расписанию блог-пост приходит админу в Telegram, админ с телефона одобряет / отклоняет / отправляет замечания, одобренный пост публикуется на сайте.

**Architecture:** `ContentReviewScheduler` просыпается по cron, проверяет два гейта (нет открытого ревью; прошёл интервал в сутках) и отдаёт сгенерированный пост в `PostReviewService`. Тот ведёт конечный автомат ревью и через `ReviewNotifier` отправляет превью в Telegram. `TelegramUpdateListener` на отдельном потоке дёргает `getUpdates`, фильтрует по chat id админа и возвращает действия обратно в `PostReviewService`. Граф зависимостей ациклический — ради этого `@Scheduled` уезжает из `ContentGenerationService` в новый планировщик.

**Tech Stack:** Java 21, Spring Boot 4.0.2, Spring Data JPA + PostgreSQL, jsoup 1.18.3, Telegram Bot API через `RestClient`, JUnit 5 + AssertJ + Mockito.

**Спек:** `docs/superpowers/specs/2026-08-12-blog-review-pipeline-design.md`

## Global Constraints

- Java 21, Spring Boot 4.0.2. Новые зависимости в `pom.xml` **не добавляются**.
- Тесты — чистый JUnit 5 + AssertJ + Mockito, моки через конструктор. **Никакого `@SpringBootTest`, `@DataJpaTest` или иного подъёма Spring-контекста.** `./mvnw verify` обязан проходить без БД и без токена Telegram.
- Пакеты по слоям, как в проекте: `entity`, `repo`, `service`, `util`, `config`. Новых пакетов не заводить.
- Конфигурация читается через `@Value` в конструкторе — как в `YandexGptService`. `@ConfigurationProperties` не вводить.
- JSON от внешних API разбирается через `JsonNode` — как в `YandexGptService`. DTO-классы на типы Telegram не создавать.
- Комментарии в коде — на английском, как в существующих файлах. Тексты для админа — на русском.
- Комментарий пишется только там, где объясняет **почему**, а не что. Пересказ кода не нужен.
- **Коммиты без трейлера `Co-Authored-By`.**
- Ветка: `feature/blog-review-pipeline` (уже создана, в ней лежит спек).
- Лимит сообщения Telegram — 4096 символов. Лимит `callback_data` — 64 байта.
- Токен бота находится в URL запросов к Bot API — полные URL логировать запрещено.

---

### Task 1: TelegramHtmlFormatter

Чистая функция без зависимостей от Spring: HTML статьи → куски, готовые к отправке с `parse_mode=HTML`. Telegram не понимает `h2`, `p`, `ul`, `li`, поэтому блочная структура схлопывается в текст с пустыми строками.

**Files:**
- Create: `src/main/java/com/cms/util/TelegramHtmlFormatter.java`
- Test: `src/test/java/com/cms/util/TelegramHtmlFormatterTest.java`

**Interfaces:**
- Consumes: ничего (первая задача)
- Produces:
  - `public static List<String> TelegramHtmlFormatter.toMessages(String articleHtml)`
  - `public static String TelegramHtmlFormatter.escape(String text)`
  - `public static final int TelegramHtmlFormatter.MAX_MESSAGE_LENGTH = 4096`
  - package-private `static String render(String)` и `static List<String> split(String)` — только для тестов

- [ ] **Step 1: Написать падающий тест**

Создать `src/test/java/com/cms/util/TelegramHtmlFormatterTest.java`:

```java
package com.cms.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramHtmlFormatterTest {

    @Test
    void rendersHeadingsAsBold() {
        assertThat(TelegramHtmlFormatter.render("<h2>Заголовок</h2><p>Текст</p>"))
                .isEqualTo("<b>Заголовок</b>\n\nТекст");
    }

    @Test
    void rendersListItemsWithBullets() {
        assertThat(TelegramHtmlFormatter.render("<ul><li>Раз</li><li>Два</li></ul>"))
                .isEqualTo("• Раз\n• Два");
    }

    @Test
    void keepsInlineEmphasis() {
        assertThat(TelegramHtmlFormatter.render("<p>Это <strong>важно</strong> и <em>ясно</em></p>"))
                .isEqualTo("Это <b>важно</b> и <i>ясно</i>");
    }

    @Test
    void dropsTagsTelegramDoesNotKnowButKeepsTheirText() {
        assertThat(TelegramHtmlFormatter.render("<p>Текст <span>внутри</span></p>"))
                .isEqualTo("Текст внутри");
    }

    @Test
    void escapesMarkupCharactersInText() {
        // Without this Telegram rejects the whole message as malformed HTML.
        assertThat(TelegramHtmlFormatter.render("<p>5 &lt; 10 &amp; 20 &gt; 3</p>"))
                .isEqualTo("5 &lt; 10 &amp; 20 &gt; 3");
    }

    @Test
    void keepsLinksWithHref() {
        assertThat(TelegramHtmlFormatter.render("<p>См. <a href=\"https://a.ru\">тут</a></p>"))
                .isEqualTo("См. <a href=\"https://a.ru\">тут</a>");
    }

    @Test
    void flattensNestedBlockContainers() {
        assertThat(TelegramHtmlFormatter.render("<div><p>Раз</p><p>Два</p></div>"))
                .isEqualTo("Раз\n\nДва");
    }

    @Test
    void shortArticleStaysOneMessage() {
        assertThat(TelegramHtmlFormatter.toMessages("<h2>Т</h2><p>Коротко</p>")).hasSize(1);
    }

    @Test
    void longArticleIsSplitOnParagraphBoundaries() {
        String paragraph = "<p>" + "Предложение про здоровье. ".repeat(4) + "</p>";
        List<String> messages = TelegramHtmlFormatter.toMessages(paragraph.repeat(80));

        assertThat(messages).hasSizeGreaterThan(1);
        assertThat(messages).allSatisfy(m ->
                assertThat(m.length()).isLessThanOrEqualTo(TelegramHtmlFormatter.MAX_MESSAGE_LENGTH));
        assertThat(messages).allSatisfy(m -> assertThat(m).doesNotStartWith(" "));
    }

    @Test
    void singleOverlongParagraphIsCutWithoutBreakingMarkup() {
        String giant = "<p>" + "слово ".repeat(1200) + "</p>";

        List<String> messages = TelegramHtmlFormatter.toMessages(giant);

        assertThat(messages).hasSizeGreaterThan(1);
        assertThat(messages).allSatisfy(m ->
                assertThat(m.length()).isLessThanOrEqualTo(TelegramHtmlFormatter.MAX_MESSAGE_LENGTH));
        // A chunk must never end inside a tag.
        assertThat(messages).allSatisfy(m -> assertThat(m.lastIndexOf('<')).isLessThanOrEqualTo(m.lastIndexOf('>')));
    }

    @Test
    void handlesEmptyAndNullInput() {
        assertThat(TelegramHtmlFormatter.toMessages(null)).isEmpty();
        assertThat(TelegramHtmlFormatter.toMessages("")).isEmpty();
        assertThat(TelegramHtmlFormatter.toMessages("   ")).isEmpty();
    }

    @Test
    void escapeHandlesAmpersandBeforeAngleBrackets() {
        assertThat(TelegramHtmlFormatter.escape("a & b < c")).isEqualTo("a &amp; b &lt; c");
        assertThat(TelegramHtmlFormatter.escape(null)).isEmpty();
    }
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run: `./mvnw test -Dtest=TelegramHtmlFormatterTest`
Expected: FAIL — компиляция не проходит, `TelegramHtmlFormatter` не существует.

- [ ] **Step 3: Реализовать форматтер**

Создать `src/main/java/com/cms/util/TelegramHtmlFormatter.java`:

```java
package com.cms.util;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Renders article HTML into chunks Telegram accepts with parse_mode=HTML.
 *
 * <p>Telegram supports a small inline subset only - b, i, u, s, a, code, pre, blockquote -
 * and knows nothing about the h2/p/ul/li the articles are written in, so block structure is
 * flattened into text separated by blank lines. One message also caps at 4096 characters,
 * which a 700-1000 word article always exceeds.
 */
public final class TelegramHtmlFormatter {

    /** Telegram's hard cap on the text of a single sendMessage call. */
    public static final int MAX_MESSAGE_LENGTH = 4096;

    private static final String BLOCK_SEPARATOR = "\n\n";

    private static final Set<String> BLOCK_TAGS = Set.of(
            "h1", "h2", "h3", "h4", "h5", "h6", "p", "ul", "ol", "div", "blockquote", "section", "article");

    private TelegramHtmlFormatter() {
    }

    /** Article HTML to ready-to-send Telegram-HTML chunks, in order. */
    public static List<String> toMessages(String articleHtml) {
        return split(render(articleHtml));
    }

    /** Escapes the three characters Telegram reads as markup. Ampersand must go first. */
    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String render(String articleHtml) {
        if (articleHtml == null || articleHtml.isBlank()) {
            return "";
        }
        Document document = Jsoup.parseBodyFragment(articleHtml);
        StringBuilder out = new StringBuilder();
        for (Node node : document.body().childNodes()) {
            appendBlock(node, out);
        }
        return out.toString().strip();
    }

    private static void appendBlock(Node node, StringBuilder out) {
        if (node instanceof TextNode text) {
            if (!text.text().isBlank()) {
                out.append(escape(text.text().strip())).append(BLOCK_SEPARATOR);
            }
            return;
        }
        if (!(node instanceof Element element)) {
            return;
        }
        switch (element.tagName()) {
            case "h1", "h2", "h3", "h4", "h5", "h6" ->
                    out.append("<b>").append(inline(element)).append("</b>").append(BLOCK_SEPARATOR);
            case "ul", "ol" -> {
                for (Element item : element.select("> li")) {
                    out.append("• ").append(inline(item)).append('\n');
                }
                out.append('\n');
            }
            case "br" -> out.append('\n');
            default -> {
                if (hasBlockChildren(element)) {
                    for (Node child : element.childNodes()) {
                        appendBlock(child, out);
                    }
                    return;
                }
                String rendered = inline(element);
                if (!rendered.isBlank()) {
                    out.append(rendered).append(BLOCK_SEPARATOR);
                }
            }
        }
    }

    private static boolean hasBlockChildren(Element element) {
        return element.children().stream().anyMatch(child -> BLOCK_TAGS.contains(child.tagName()));
    }

    private static String inline(Element element) {
        StringBuilder out = new StringBuilder();
        for (Node node : element.childNodes()) {
            appendInline(node, out);
        }
        return out.toString().strip();
    }

    private static void appendInline(Node node, StringBuilder out) {
        if (node instanceof TextNode text) {
            out.append(escape(text.text()));
            return;
        }
        if (!(node instanceof Element element)) {
            return;
        }
        switch (element.tagName()) {
            case "b", "strong" -> out.append("<b>").append(inline(element)).append("</b>");
            case "i", "em" -> out.append("<i>").append(inline(element)).append("</i>");
            case "u", "ins" -> out.append("<u>").append(inline(element)).append("</u>");
            case "s", "strike", "del" -> out.append("<s>").append(inline(element)).append("</s>");
            case "code" -> out.append("<code>").append(inline(element)).append("</code>");
            case "br" -> out.append('\n');
            case "a" -> {
                String href = element.attr("href");
                if (href.isBlank()) {
                    out.append(inline(element));
                } else {
                    out.append("<a href=\"").append(escape(href)).append("\">")
                            .append(inline(element)).append("</a>");
                }
            }
            // Anything Telegram does not know is dropped; its text survives.
            default -> out.append(inline(element));
        }
    }

    static List<String> split(String rendered) {
        List<String> chunks = new ArrayList<>();
        if (rendered == null || rendered.isEmpty()) {
            return chunks;
        }
        StringBuilder current = new StringBuilder();
        for (String block : rendered.split("\n{2,}")) {
            if (block.length() > MAX_MESSAGE_LENGTH) {
                flush(current, chunks);
                hardSplit(block, chunks);
                continue;
            }
            int projected = current.isEmpty()
                    ? block.length()
                    : current.length() + BLOCK_SEPARATOR.length() + block.length();
            if (projected > MAX_MESSAGE_LENGTH) {
                flush(current, chunks);
            }
            if (!current.isEmpty()) {
                current.append(BLOCK_SEPARATOR);
            }
            current.append(block);
        }
        flush(current, chunks);
        return chunks;
    }

    private static void flush(StringBuilder current, List<String> chunks) {
        if (!current.isEmpty()) {
            chunks.add(current.toString());
            current.setLength(0);
        }
    }

    /**
     * Last resort for one block longer than the limit. Markup is stripped first: cutting
     * inside a tag, or between an opening and closing pair, yields HTML that Telegram
     * rejects outright, and a paragraph this long is malformed content anyway.
     */
    private static void hardSplit(String block, List<String> chunks) {
        String plain = escape(Jsoup.parse(block).text());
        int start = 0;
        while (start < plain.length()) {
            int end = Math.min(start + MAX_MESSAGE_LENGTH, plain.length());
            if (end < plain.length()) {
                int lastSpace = plain.lastIndexOf(' ', end);
                if (lastSpace > start) {
                    end = lastSpace;
                }
            }
            chunks.add(plain.substring(start, end).strip());
            start = end;
        }
    }
}
```

- [ ] **Step 4: Убедиться, что тесты проходят**

Run: `./mvnw test -Dtest=TelegramHtmlFormatterTest`
Expected: PASS, 12 тестов.

- [ ] **Step 5: Закоммитить**

```bash
git add src/main/java/com/cms/util/TelegramHtmlFormatter.java \
        src/test/java/com/cms/util/TelegramHtmlFormatterTest.java
git commit -m "feat: render article HTML into Telegram-safe message chunks"
```

---

### Task 2: GEO-промпт и регенерация статьи

`ContentGenerationService` получает GEO-ориентированный системный промпт и метод `regenerate`, переписывающий существующий пост по замечаниям редактора. `@Scheduled` пока **остаётся на месте** — его заменит Task 7, чтобы не было промежутка без генерации вовсе.

**Files:**
- Modify: `src/main/java/com/cms/service/ContentGenerationService.java`
- Test: `src/test/java/com/cms/service/ContentGenerationServiceTest.java` (дополнить существующий)

**Interfaces:**
- Consumes: `YandexGptService.generate(String systemPrompt, String userPrompt)`, `BlogPostRepository`, `SlugUtil.toSlug(String)` — всё существует
- Produces:
  - `public BlogPostEntity ContentGenerationService.regenerate(BlogPostEntity post, String instruction)` — переписывает и сохраняет тот же пост, бросает `IllegalStateException` после `MAX_ATTEMPTS` неудач
  - `public BlogPostEntity ContentGenerationService.generateNow()` — существует, сигнатура не меняется

- [ ] **Step 1: Написать падающие тесты**

Дописать в `src/test/java/com/cms/service/ContentGenerationServiceTest.java`. Добавить к существующим импортам:

```java
import com.cms.entity.BlogPostEntity;
import com.cms.entity.PageStatus;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
```

Заменить блок `setUp` так, чтобы мок GPT был доступен в тестах:

```java
    private BlogPostRepository repository;
    private YandexGptService gptService;
    private ContentGenerationService service;

    @BeforeEach
    void setUp() {
        repository = mock(BlogPostRepository.class);
        gptService = mock(YandexGptService.class);
        // Zero backoff: the retry-path test would otherwise sit through 15 seconds of sleeps.
        service = new ContentGenerationService(gptService, repository, new ObjectMapper(), true, 0L);
    }
```

Добавить новые тесты:

```java
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
```

**Почему пятиаргументный конструктор:** `regenerationFailsLoudlyWhenTheModelKeepsReturningGarbage` прогоняет все 3 попытки, а текущий backoff `5s * attempt` добавил бы к сборке 15 секунд сна. Поэтому в Step 3 backoff превращается из константы в поле, а package-private конструктор позволяет тесту обнулить его.

- [ ] **Step 2: Убедиться, что тесты падают**

Run: `./mvnw test -Dtest=ContentGenerationServiceTest`
Expected: FAIL — метода `regenerate` не существует, компиляция не проходит.

- [ ] **Step 3: Реализовать**

В `src/main/java/com/cms/service/ContentGenerationService.java`:

Заменить константу backoff на поле и добавить тестовый конструктор:

```java
    private static final long DEFAULT_RETRY_BACKOFF_MS = 5_000;

    private final long retryBackoffMs;
```

В существующем конструкторе добавить `this.retryBackoffMs = DEFAULT_RETRY_BACKOFF_MS;` и рядом положить второй конструктор:

```java
    /** Test seam: lets unit tests exercise the retry path without waiting out the backoff. */
    ContentGenerationService(YandexGptService gptService,
                             BlogPostRepository blogPostRepository,
                             ObjectMapper objectMapper,
                             boolean enabled,
                             long retryBackoffMs) {
        this.gptService = gptService;
        this.blogPostRepository = blogPostRepository;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.retryBackoffMs = retryBackoffMs;
    }
```

Заменить в `generateNow` `RETRY_BACKOFF_MS * attempt` на `retryBackoffMs * attempt`.

Сделать `systemPrompt()` package-private (убрать `private`) и заменить его тело:

```java
    String systemPrompt() {
        return """
                Ты — медицинский копирайтер для сайта клиники.
                Напиши статью для блога на 700–1000 слов.

                СТРУКТУРА (от неё зависит попадание статьи в ответы поисковых AI):
                - Первый абзац — прямой сжатый ответ на главный вопрос темы, 2–3 предложения.
                  Начинай сразу с сути. Никаких «в современном мире», «как известно», «сегодня всё чаще».
                - Подзаголовки <h2> формулируй как вопросы, которые люди задают вслух.
                - Каждый раздел должен быть понятен сам по себе, без чтения предыдущих:
                  не пиши «как сказано выше», «этот метод», «данный подход» — называй предмет явно.
                - Абзацы короткие: 2–4 предложения.
                - Где есть перечисление — <ul> или <ol>, а не сплошной текст.
                - В конце раздел <h2>Частые вопросы</h2> и 3–5 пар:
                  <h3>вопрос</h3><p>ответ в 1–3 предложения</p>.
                - Самая последняя строка — фраза о необходимости очной консультации врача.

                ЗАПРЕЩЕНО:
                - Ставить диагнозы и назначать лечение.
                - Выдумывать статистику, проценты, исследования, цитаты, имена врачей.
                - Называть препараты и дозировки.
                - Вода и общие рассуждения без конкретики.

                Ответ верни строго в JSON формате, одним объектом:
                {
                  "title": "заголовок — вопрос или конкретное утверждение, до 70 символов",
                  "body": "полный текст в HTML (h2, h3, p, ul, ol, li, strong, em)",
                  "metaDescription": "мета-описание до 160 символов: прямой ответ, а не интрига",
                  "keywords": "ключевые слова через запятую"
                }

                Не оборачивай ответ в markdown. Не добавляй текст до или после JSON.
                Все кавычки внутри значений экранируй.""";
    }
```

Добавить промпт правок, публичный `regenerate` и вспомогательные методы:

```java
    private String revisionPrompt(BlogPostEntity post, String instruction) {
        return """
                Текущая версия статьи.
                Заголовок: %s
                Текст: %s

                Замечания редактора: %s

                Перепиши статью целиком с учётом замечаний. Соблюдай все требования
                к структуре и все запреты. Верни тот же JSON-объект."""
                .formatted(post.getTitle(), post.getBody(), instruction);
    }

    /**
     * Rewrites an existing post in place from the editor's notes. Retries transient GPT
     * failures the same way the first generation does.
     *
     * @throws IllegalStateException if every attempt failed; the previous text is untouched
     */
    public BlogPostEntity regenerate(BlogPostEntity post, String instruction) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return applyRevision(post, instruction);
            } catch (RuntimeException e) {
                lastFailure = e;
                log.warn("Revision attempt {}/{} failed for post {}: {}",
                        attempt, MAX_ATTEMPTS, post.getId(), e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    sleepQuietly(retryBackoffMs * attempt);
                }
            }
        }
        throw new IllegalStateException("Could not revise post " + post.getId(), lastFailure);
    }

    private BlogPostEntity applyRevision(BlogPostEntity post, String instruction) {
        String raw = gptService.generate(systemPrompt(), revisionPrompt(post, instruction));
        JsonNode node = parseJsonPayload(raw);

        String title = requireText(node, "title");
        String bodyHtml = requireText(node, "body");

        post.setSlug(reslug(post, title));
        post.setTitle(title);
        post.setBody(Jsoup.clean(bodyHtml, ARTICLE_SAFELIST));
        post.setMetaDescription(trim(node.path("metaDescription").asText(""), META_DESCRIPTION_LIMIT));
        post.setKeywords(trim(node.path("keywords").asText(""), KEYWORDS_LIMIT));

        return saveWithSlugFallback(post, title);
    }

    /**
     * Re-derives the slug when the title actually changed. Skipping the unchanged case
     * matters: uniqueSlug would find the post's own slug among the taken ones and append
     * a pointless "-1" on every revision.
     */
    private String reslug(BlogPostEntity post, String newTitle) {
        String base = SlugUtil.toSlug(newTitle);
        return base.equals(post.getSlug()) ? post.getSlug() : uniqueSlug(base);
    }

    private BlogPostEntity saveWithSlugFallback(BlogPostEntity post, String title) {
        try {
            return blogPostRepository.saveAndFlush(post);
        } catch (DataIntegrityViolationException e) {
            // Another instance claimed the slug between our check and the insert.
            log.warn("Slug '{}' was taken concurrently, retrying with a random suffix", post.getSlug());
            post.setSlug(SlugUtil.toSlug(title) + "-" + UUID.randomUUID().toString().substring(0, 6));
            return blogPostRepository.saveAndFlush(post);
        }
    }
```

Переписать хвост существующего `generateAndSave`, чтобы он пользовался общим сохранением — заменить блок `try { return blogPostRepository.saveAndFlush(post); } catch (...) { ... }` на:

```java
        return saveWithSlugFallback(post, title);
```

- [ ] **Step 4: Убедиться, что тесты проходят**

Run: `./mvnw test -Dtest=ContentGenerationServiceTest`
Expected: PASS. Существующие 7 тестов + 6 новых.

Если `regenerationFailsLoudlyWhenTheModelKeepsReturningGarbage` идёт дольше секунды — в тесте используется четырёхаргументный конструктор; заменить его на пятиаргументный с `0L` в конце.

- [ ] **Step 5: Закоммитить**

```bash
git add src/main/java/com/cms/service/ContentGenerationService.java \
        src/test/java/com/cms/service/ContentGenerationServiceTest.java
git commit -m "feat: GEO-oriented article prompt and revision from editor notes"
```

---

### Task 3: Модель данных ревью

Сущность, enum и репозиторий состояния ревью плюс два запроса в `BlogPostRepository`.

**Честно про тесты:** юнит-тестов здесь нет. Проверить JPQL без БД невозможно, а спек запрещает поднимать Spring-контекст в тестах. Корректность запросов подтверждается компиляцией и первым реальным стартом приложения в Task 9 — Hibernate валидирует `@Query` при инициализации фабрики репозиториев и падает на старте, если JPQL сломан.

**Files:**
- Create: `src/main/java/com/cms/entity/ReviewState.java`
- Create: `src/main/java/com/cms/entity/PostReviewEntity.java`
- Create: `src/main/java/com/cms/repo/PostReviewRepository.java`
- Modify: `src/main/java/com/cms/repo/BlogPostRepository.java`

**Interfaces:**
- Consumes: `BlogPostEntity` (существует)
- Produces:
  - `enum ReviewState { PENDING, AWAITING_EDIT, APPROVED, REJECTED }`
  - `PostReviewEntity` с геттерами/сеттерами от Lombok `@Data`: `getId()`, `getBlogPost()`, `setBlogPost(BlogPostEntity)`, `getState()`, `setState(ReviewState)`, `getTelegramMessageId()`/`setTelegramMessageId(Long)`, `getRevisionCount()`/`setRevisionCount(int)`, `getLastInstruction()`/`setLastInstruction(String)`
  - `Optional<PostReviewEntity> PostReviewRepository.findFirstByStateIn(Collection<ReviewState> states)`
  - `Optional<LocalDateTime> BlogPostRepository.findLatestAiGeneratedCreatedAt()`
  - `List<String> BlogPostRepository.findUsedTopics()` — сигнатура прежняя, запрос изменён

- [ ] **Step 1: Создать enum состояний**

`src/main/java/com/cms/entity/ReviewState.java`:

```java
package com.cms.entity;

/**
 * Where a generated post sits in the admin review pipeline.
 *
 * <p>Deliberately separate from {@link PageStatus}: "is it visible on the site" and "where
 * is it in review" are orthogonal, and PageStatus is shared with PageEntity, which has no
 * review workflow at all.
 */
public enum ReviewState {
    PENDING,
    AWAITING_EDIT,
    APPROVED,
    REJECTED
}
```

- [ ] **Step 2: Создать сущность ревью**

`src/main/java/com/cms/entity/PostReviewEntity.java`:

```java
package com.cms.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "post_review")
public class PostReviewEntity {

    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    private Integer id;

    @OneToOne(optional = false)
    @JoinColumn(name = "blog_post_id", nullable = false, unique = true)
    private BlogPostEntity blogPost;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReviewState state;

    /** Id of the message carrying the buttons; null until the preview reached Telegram. */
    private Long telegramMessageId;

    @Column(nullable = false)
    private int revisionCount;

    @Column(columnDefinition = "TEXT")
    private String lastInstruction;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 3: Создать репозиторий ревью**

`src/main/java/com/cms/repo/PostReviewRepository.java`:

```java
package com.cms.repo;

import com.cms.entity.PostReviewEntity;
import com.cms.entity.ReviewState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;

@Repository
public interface PostReviewRepository extends JpaRepository<PostReviewEntity, Integer> {

    /** The one open review, if any: the pipeline never keeps more than a single post in review. */
    Optional<PostReviewEntity> findFirstByStateIn(Collection<ReviewState> states);
}
```

- [ ] **Step 4: Изменить запросы в BlogPostRepository**

В `src/main/java/com/cms/repo/BlogPostRepository.java` добавить импорт `java.time.LocalDateTime`, заменить `findUsedTopics` и добавить запрос даты:

```java
    /**
     * Topics of previously generated posts, oldest first. Drives round-robin topic selection.
     *
     * <p>Rejected posts are excluded on purpose: counting them would burn the topic, so a
     * topic the admin turned down once would never come round again.
     */
    @Query("""
            select b.sourceTopic from BlogPostEntity b
            where b.sourceTopic is not null
              and not exists (
                  select 1 from PostReviewEntity r
                  where r.blogPost = b and r.state = com.cms.entity.ReviewState.REJECTED
              )
            order by b.id asc
            """)
    List<String> findUsedTopics();

    /** Newest AI post's creation time; drives the cadence gate. Empty when none exist yet. */
    @Query("select max(b.createdAt) from BlogPostEntity b where b.aiGenerated = true")
    Optional<LocalDateTime> findLatestAiGeneratedCreatedAt();
```

- [ ] **Step 5: Проверить, что всё компилируется и прежние тесты живы**

Run: `./mvnw -q clean test`
Expected: PASS. Изменение `findUsedTopics` — только в тексте `@Query`, сигнатура прежняя, поэтому `ContentGenerationServiceTest` продолжает работать на моке.

- [ ] **Step 6: Закоммитить**

```bash
git add src/main/java/com/cms/entity/ReviewState.java \
        src/main/java/com/cms/entity/PostReviewEntity.java \
        src/main/java/com/cms/repo/PostReviewRepository.java \
        src/main/java/com/cms/repo/BlogPostRepository.java
git commit -m "feat: review state model and topic rotation that ignores rejected posts"
```

---

### Task 4: TelegramClient

Транспорт к Bot API. Юнит-тестами не покрывается — это тонкая обёртка над HTTP, тест на неё проверял бы мок `RestClient`, а не поведение. Так же решено для `TelegramUpdateListener` в спеке.

**Files:**
- Create: `src/main/java/com/cms/service/TelegramClient.java`

**Interfaces:**
- Consumes: `ObjectMapper` (бин Spring Boot), свойства `telegram.*`
- Produces:
  - `boolean isEnabled()` — false при пустом токене или chat id
  - `long adminChatId()`
  - `long sendMessage(String html, Map<String, Object> replyMarkup)` — возвращает `message_id`, `replyMarkup` может быть `null`
  - `void clearKeyboard(long messageId)`
  - `void answerCallback(String callbackQueryId, String text)`
  - `JsonNode getUpdates(long offset)` — массив апдейтов
  - `class TelegramClient.TelegramException extends RuntimeException`

- [ ] **Step 1: Реализовать клиент**

`src/main/java/com/cms/service/TelegramClient.java`:

```java
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
```

- [ ] **Step 2: Проверить компиляцию и что прежние тесты живы**

Run: `./mvnw -q clean test`
Expected: PASS.

- [ ] **Step 3: Закоммитить**

```bash
git add src/main/java/com/cms/service/TelegramClient.java
git commit -m "feat: Telegram Bot API transport"
```

---

### Task 5: ReviewNotifier

Единственное место, где живут тексты сообщений админу и раскладка кнопок.

**Files:**
- Create: `src/main/java/com/cms/service/ReviewNotifier.java`
- Test: `src/test/java/com/cms/service/ReviewNotifierTest.java`

**Interfaces:**
- Consumes: `TelegramClient` (Task 4), `TelegramHtmlFormatter.toMessages` / `.escape` (Task 1), `PostReviewEntity` (Task 3)
- Produces:
  - `long sendPreview(PostReviewEntity review, boolean allowEdit)` — возвращает id сообщения с кнопками
  - `void notifyPublished(BlogPostEntity post)`
  - `void notifyRejected()`
  - `void askForInstruction()`
  - `void notifyRegenerating()`
  - `void notifyRegenerationFailed(String reason)`
  - `void notifyRevisionLimit(int maxRevisions)`
  - `void notifyIdle()`

- [ ] **Step 1: Написать падающий тест**

`src/test/java/com/cms/service/ReviewNotifierTest.java`:

```java
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
```

- [ ] **Step 2: Убедиться, что тест падает**

Run: `./mvnw test -Dtest=ReviewNotifierTest`
Expected: FAIL — `ReviewNotifier` не существует.

- [ ] **Step 3: Реализовать**

`src/main/java/com/cms/service/ReviewNotifier.java`:

```java
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
        telegram.sendMessage(
                "✅ Опубликовано: " + siteBaseUrl + "/blog/" + post.getSlug(), null);
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
        telegram.sendMessage(
                "⚠️ Не удалось переписать: " + TelegramHtmlFormatter.escape(reason)
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
```

- [ ] **Step 4: Убедиться, что тесты проходят**

Run: `./mvnw test -Dtest=ReviewNotifierTest`
Expected: PASS, 7 тестов.

- [ ] **Step 5: Закоммитить**

```bash
git add src/main/java/com/cms/service/ReviewNotifier.java \
        src/test/java/com/cms/service/ReviewNotifierTest.java
git commit -m "feat: compose review preview messages and inline keyboard"
```

---

### Task 6: PostReviewService — конечный автомат

Переходы состояний, идемпотентность, лимит ревизий. Зависит от `ContentGenerationService` и `ReviewNotifier`, но не от `TelegramClient`, поэтому тестируется целиком на моках.

**Files:**
- Create: `src/main/java/com/cms/service/PostReviewService.java`
- Test: `src/test/java/com/cms/service/PostReviewServiceTest.java`

**Interfaces:**
- Consumes: `PostReviewRepository.findFirstByStateIn` / `findById` / `saveAndFlush` (Task 3), `BlogPostRepository.saveAndFlush`, `ContentGenerationService.regenerate` (Task 2), `ReviewNotifier` (Task 5), `TelegramClient.clearKeyboard` (Task 4)
- Produces:
  - `PostReviewEntity submitForReview(BlogPostEntity post)`
  - `void deliver(PostReviewEntity review)` — повторная отправка превью, ошибку не пробрасывает
  - `void handleAction(int reviewId, String action)` — action ∈ `approve` / `reject` / `edit`
  - `void applyInstruction(String instruction)`

- [ ] **Step 1: Написать падающий тест**

`src/test/java/com/cms/service/PostReviewServiceTest.java`:

```java
package com.cms.service;

import com.cms.entity.BlogPostEntity;
import com.cms.entity.PageStatus;
import com.cms.entity.PostReviewEntity;
import com.cms.entity.ReviewState;
import com.cms.repo.BlogPostRepository;
import com.cms.repo.PostReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostReviewServiceTest {

    private PostReviewRepository reviewRepository;
    private BlogPostRepository blogPostRepository;
    private ContentGenerationService generationService;
    private ReviewNotifier notifier;
    private TelegramClient telegram;
    private PostReviewService service;

    private PostReviewEntity review;

    @BeforeEach
    void setUp() {
        reviewRepository = mock(PostReviewRepository.class);
        blogPostRepository = mock(BlogPostRepository.class);
        generationService = mock(ContentGenerationService.class);
        notifier = mock(ReviewNotifier.class);
        telegram = mock(TelegramClient.class);
        service = new PostReviewService(
                reviewRepository, blogPostRepository, generationService, notifier, telegram, 5);

        BlogPostEntity post = new BlogPostEntity();
        post.setId(3);
        post.setSlug("stress-i-zdorove");
        post.setTitle("Стресс и здоровье");
        post.setBody("<p>Текст</p>");
        post.setStatus(PageStatus.DRAFT);

        review = new PostReviewEntity();
        review.setId(11);
        review.setBlogPost(post);
        review.setState(ReviewState.PENDING);
        review.setTelegramMessageId(100L);

        when(reviewRepository.findById(11)).thenReturn(Optional.of(review));
        when(reviewRepository.saveAndFlush(any(PostReviewEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(blogPostRepository.saveAndFlush(any(BlogPostEntity.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void approvePublishesThePostAndClosesTheReview() {
        service.handleAction(11, "approve");

        assertThat(review.getBlogPost().getStatus()).isEqualTo(PageStatus.PUBLISHED);
        assertThat(review.getState()).isEqualTo(ReviewState.APPROVED);
        verify(telegram).clearKeyboard(100L);
        verify(notifier).notifyPublished(review.getBlogPost());
    }

    @Test
    void rejectLeavesThePostAsDraft() {
        service.handleAction(11, "reject");

        assertThat(review.getBlogPost().getStatus()).isEqualTo(PageStatus.DRAFT);
        assertThat(review.getState()).isEqualTo(ReviewState.REJECTED);
        verify(notifier).notifyRejected();
    }

    @Test
    void editMovesToAwaitingEditAndAsksForNotes() {
        service.handleAction(11, "edit");

        assertThat(review.getState()).isEqualTo(ReviewState.AWAITING_EDIT);
        verify(notifier).askForInstruction();
    }

    @Test
    void secondApproveIsIgnored() {
        // Telegram redelivers unconfirmed updates and admins double-tap buttons.
        review.setState(ReviewState.APPROVED);

        service.handleAction(11, "approve");

        verify(notifier, never()).notifyPublished(any());
        verify(blogPostRepository, never()).saveAndFlush(any());
    }

    @Test
    void approveOnARejectedReviewIsIgnored() {
        review.setState(ReviewState.REJECTED);

        service.handleAction(11, "approve");

        assertThat(review.getBlogPost().getStatus()).isEqualTo(PageStatus.DRAFT);
        verify(notifier, never()).notifyPublished(any());
    }

    @Test
    void unknownReviewIdIsIgnored() {
        when(reviewRepository.findById(99)).thenReturn(Optional.empty());

        service.handleAction(99, "approve");

        verify(notifier, never()).notifyPublished(any());
    }

    @Test
    void instructionTriggersRegenerationAndANewPreview() {
        review.setState(ReviewState.AWAITING_EDIT);
        when(reviewRepository.findFirstByStateIn(any())).thenReturn(Optional.of(review));
        when(notifier.sendPreview(any(), anyBoolean())).thenReturn(200L);

        service.applyInstruction("сократи вдвое");

        verify(generationService).regenerate(review.getBlogPost(), "сократи вдвое");
        assertThat(review.getRevisionCount()).isEqualTo(1);
        assertThat(review.getLastInstruction()).isEqualTo("сократи вдвое");
        assertThat(review.getState()).isEqualTo(ReviewState.PENDING);
        assertThat(review.getTelegramMessageId()).isEqualTo(200L);
    }

    @Test
    void failedRegenerationKeepsThePreviousVersionAndReopensTheReview() {
        review.setState(ReviewState.AWAITING_EDIT);
        when(reviewRepository.findFirstByStateIn(any())).thenReturn(Optional.of(review));
        when(generationService.regenerate(any(), anyString()))
                .thenThrow(new IllegalStateException("GPT недоступен"));

        service.applyInstruction("сократи");

        assertThat(review.getState()).isEqualTo(ReviewState.PENDING);
        assertThat(review.getRevisionCount()).isZero();
        verify(notifier).notifyRegenerationFailed(anyString());
    }

    @Test
    void revisionBudgetStopsFurtherRegeneration() {
        review.setState(ReviewState.AWAITING_EDIT);
        review.setRevisionCount(5);
        when(reviewRepository.findFirstByStateIn(any())).thenReturn(Optional.of(review));

        service.applyInstruction("ещё раз");

        verify(generationService, never()).regenerate(any(), anyString());
        assertThat(review.getState()).isEqualTo(ReviewState.PENDING);
        verify(notifier).notifyRevisionLimit(5);
    }

    @Test
    void instructionWithNothingAwaitingEditJustHints() {
        when(reviewRepository.findFirstByStateIn(any())).thenReturn(Optional.empty());

        service.applyInstruction("привет");

        verify(generationService, never()).regenerate(any(), anyString());
        verify(notifier).notifyIdle();
    }

    @Test
    void submitStoresTheButtonMessageId() {
        BlogPostEntity post = review.getBlogPost();
        when(notifier.sendPreview(any(), anyBoolean())).thenReturn(300L);

        PostReviewEntity created = service.submitForReview(post);

        assertThat(created.getState()).isEqualTo(ReviewState.PENDING);
        assertThat(created.getTelegramMessageId()).isEqualTo(300L);
    }

    @Test
    void failedDeliveryLeavesTheReviewPendingWithoutAMessageId() {
        // The scheduler retries exactly this case; a thrown exception would strand the pipeline.
        review.setTelegramMessageId(null);
        when(notifier.sendPreview(any(), anyBoolean()))
                .thenThrow(new TelegramClient.TelegramException("network down"));

        service.deliver(review);

        assertThat(review.getTelegramMessageId()).isNull();
        assertThat(review.getState()).isEqualTo(ReviewState.PENDING);
    }

    @Test
    void editButtonIsOfferedOnlyWhileTheBudgetLasts() {
        review.setRevisionCount(5);
        when(notifier.sendPreview(any(), anyBoolean())).thenReturn(300L);

        service.deliver(review);

        verify(notifier).sendPreview(review, false);
    }
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run: `./mvnw test -Dtest=PostReviewServiceTest`
Expected: FAIL — `PostReviewService` не существует.

- [ ] **Step 3: Реализовать**

`src/main/java/com/cms/service/PostReviewService.java`:

```java
package com.cms.service;

import com.cms.entity.BlogPostEntity;
import com.cms.entity.PageStatus;
import com.cms.entity.PostReviewEntity;
import com.cms.entity.ReviewState;
import com.cms.repo.BlogPostRepository;
import com.cms.repo.PostReviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * The review state machine.
 *
 * <p>Every transition checks the current state first: Telegram redelivers unconfirmed
 * updates after a restart, and admins double-tap buttons, so an action that arrives twice
 * must be a no-op rather than a second publish.
 */
@Service
public class PostReviewService {

    private static final Logger log = LoggerFactory.getLogger(PostReviewService.class);

    private final PostReviewRepository reviewRepository;
    private final BlogPostRepository blogPostRepository;
    private final ContentGenerationService generationService;
    private final ReviewNotifier notifier;
    private final TelegramClient telegram;
    private final int maxRevisions;

    public PostReviewService(PostReviewRepository reviewRepository,
                             BlogPostRepository blogPostRepository,
                             ContentGenerationService generationService,
                             ReviewNotifier notifier,
                             TelegramClient telegram,
                             @Value("${telegram.max-revisions:5}") int maxRevisions) {
        this.reviewRepository = reviewRepository;
        this.blogPostRepository = blogPostRepository;
        this.generationService = generationService;
        this.notifier = notifier;
        this.telegram = telegram;
        this.maxRevisions = maxRevisions;
    }

    /** Registers a freshly generated post for review and delivers the preview. */
    public PostReviewEntity submitForReview(BlogPostEntity post) {
        PostReviewEntity review = new PostReviewEntity();
        review.setBlogPost(post);
        review.setState(ReviewState.PENDING);
        review.setRevisionCount(0);
        PostReviewEntity saved = reviewRepository.saveAndFlush(review);
        deliver(saved);
        return saved;
    }

    /**
     * Sends the preview and records the button message id.
     *
     * <p>A delivery failure is logged rather than rethrown: the review stays PENDING with a
     * null message id, and the scheduler retries it on the next run. Rethrowing here would
     * let one network blip stall the pipeline forever, because the open-review gate would
     * keep blocking new generations.
     */
    public void deliver(PostReviewEntity review) {
        try {
            long messageId = notifier.sendPreview(review, review.getRevisionCount() < maxRevisions);
            review.setTelegramMessageId(messageId);
            reviewRepository.saveAndFlush(review);
        } catch (RuntimeException e) {
            log.error("Could not deliver review {} to Telegram; will retry on the next run",
                    review.getId(), e);
        }
    }

    public void handleAction(int reviewId, String action) {
        Optional<PostReviewEntity> found = reviewRepository.findById(reviewId);
        if (found.isEmpty()) {
            log.warn("Received action '{}' for unknown review {}", action, reviewId);
            return;
        }
        PostReviewEntity review = found.get();
        switch (action) {
            case "approve" -> approve(review);
            case "reject" -> reject(review);
            case "edit" -> requestEdit(review);
            default -> log.warn("Unknown review action '{}'", action);
        }
    }

    private void approve(PostReviewEntity review) {
        if (notPending(review, "approve")) {
            return;
        }
        clearKeyboardQuietly(review);
        BlogPostEntity post = review.getBlogPost();
        post.setStatus(PageStatus.PUBLISHED);
        blogPostRepository.saveAndFlush(post);
        review.setState(ReviewState.APPROVED);
        reviewRepository.saveAndFlush(review);
        notifier.notifyPublished(post);
        log.info("Review {} approved; post {} is published", review.getId(), post.getId());
    }

    private void reject(PostReviewEntity review) {
        if (notPending(review, "reject")) {
            return;
        }
        clearKeyboardQuietly(review);
        review.setState(ReviewState.REJECTED);
        reviewRepository.saveAndFlush(review);
        notifier.notifyRejected();
        log.info("Review {} rejected; post stays a draft", review.getId());
    }

    private void requestEdit(PostReviewEntity review) {
        if (notPending(review, "edit")) {
            return;
        }
        clearKeyboardQuietly(review);
        review.setState(ReviewState.AWAITING_EDIT);
        reviewRepository.saveAndFlush(review);
        notifier.askForInstruction();
    }

    /** Applies the admin's free-text notes to whichever review is waiting for them. */
    public void applyInstruction(String instruction) {
        Optional<PostReviewEntity> found =
                reviewRepository.findFirstByStateIn(List.of(ReviewState.AWAITING_EDIT));
        if (found.isEmpty()) {
            notifier.notifyIdle();
            return;
        }
        PostReviewEntity review = found.get();

        if (review.getRevisionCount() >= maxRevisions) {
            review.setState(ReviewState.PENDING);
            reviewRepository.saveAndFlush(review);
            notifier.notifyRevisionLimit(maxRevisions);
            deliver(review);
            return;
        }

        notifier.notifyRegenerating();
        try {
            generationService.regenerate(review.getBlogPost(), instruction);
            review.setRevisionCount(review.getRevisionCount() + 1);
            review.setLastInstruction(instruction);
            review.setState(ReviewState.PENDING);
            reviewRepository.saveAndFlush(review);
            deliver(review);
        } catch (RuntimeException e) {
            log.error("Regeneration failed for review {}", review.getId(), e);
            review.setState(ReviewState.PENDING);
            reviewRepository.saveAndFlush(review);
            notifier.notifyRegenerationFailed(e.getMessage());
            deliver(review);
        }
    }

    private boolean notPending(PostReviewEntity review, String action) {
        if (review.getState() == ReviewState.PENDING) {
            return false;
        }
        log.info("Ignoring '{}' for review {} already in state {}",
                action, review.getId(), review.getState());
        return true;
    }

    /**
     * Removes the buttons so the action cannot be replayed. A failure here is not fatal:
     * the state transition is already durable, and the state check rejects a second click.
     */
    private void clearKeyboardQuietly(PostReviewEntity review) {
        Long messageId = review.getTelegramMessageId();
        if (messageId == null) {
            return;
        }
        try {
            telegram.clearKeyboard(messageId);
        } catch (RuntimeException e) {
            log.warn("Could not clear the keyboard of message {}: {}", messageId, e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Убедиться, что тесты проходят**

Run: `./mvnw test -Dtest=PostReviewServiceTest`
Expected: PASS, 13 тестов.

- [ ] **Step 5: Закоммитить**

```bash
git add src/main/java/com/cms/service/PostReviewService.java \
        src/test/java/com/cms/service/PostReviewServiceTest.java
git commit -m "feat: idempotent review state machine with revision budget"
```

---

### Task 7: ContentReviewScheduler и перенос расписания

Планировщик берёт на себя `@Scheduled` и оба гейта, а `ContentGenerationService` окончательно становится чистым генератором. Перенос делается **одним коммитом**, чтобы не было ревизии, в которой расписание отсутствует или задвоено.

**Files:**
- Create: `src/main/java/com/cms/service/ContentReviewScheduler.java`
- Modify: `src/main/java/com/cms/service/ContentGenerationService.java` (убрать `@Scheduled`, `enabled`, `generateDailyPost`)
- Modify: `src/main/java/com/cms/config/ApplicationConfig.java` (бин `Clock`)
- Modify: `src/test/java/com/cms/service/ContentGenerationServiceTest.java` (конструкторы без `enabled`)
- Test: `src/test/java/com/cms/service/ContentReviewSchedulerTest.java`

**Interfaces:**
- Consumes: `ContentGenerationService.generateNow()` (Task 2), `PostReviewService.submitForReview` / `deliver` (Task 6), `PostReviewRepository.findFirstByStateIn` и `BlogPostRepository.findLatestAiGeneratedCreatedAt` (Task 3)
- Produces:
  - `void ContentReviewScheduler.run()` — точка входа `@Scheduled`
  - `void ContentReviewScheduler.generate(boolean forced)` — `forced=true` пропускает гейт интервала, но не гейт открытого ревью
  - `@Bean Clock ApplicationConfig.clock()`
- Breaking: конструктор `ContentGenerationService` теряет параметр `boolean enabled`; становится `(YandexGptService, BlogPostRepository, ObjectMapper)` и `(YandexGptService, BlogPostRepository, ObjectMapper, long retryBackoffMs)`

- [ ] **Step 1: Написать падающий тест**

`src/test/java/com/cms/service/ContentReviewSchedulerTest.java`:

```java
package com.cms.service;

import com.cms.entity.BlogPostEntity;
import com.cms.entity.PostReviewEntity;
import com.cms.entity.ReviewState;
import com.cms.repo.BlogPostRepository;
import com.cms.repo.PostReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContentReviewSchedulerTest {

    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 12, 8, 0);

    private ContentGenerationService generationService;
    private PostReviewService reviewService;
    private PostReviewRepository reviewRepository;
    private BlogPostRepository blogPostRepository;

    @BeforeEach
    void setUp() {
        generationService = mock(ContentGenerationService.class);
        reviewService = mock(PostReviewService.class);
        reviewRepository = mock(PostReviewRepository.class);
        blogPostRepository = mock(BlogPostRepository.class);

        when(reviewRepository.findFirstByStateIn(any())).thenReturn(Optional.empty());
        when(blogPostRepository.findLatestAiGeneratedCreatedAt()).thenReturn(Optional.empty());
        when(generationService.generateNow()).thenReturn(new BlogPostEntity());
    }

    private ContentReviewScheduler scheduler(boolean enabled, int intervalDays) {
        Clock fixed = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        return new ContentReviewScheduler(generationService, reviewService,
                reviewRepository, blogPostRepository, enabled, intervalDays, fixed);
    }

    private static PostReviewEntity openReview(ReviewState state, Long messageId) {
        PostReviewEntity review = new PostReviewEntity();
        review.setId(11);
        review.setState(state);
        review.setTelegramMessageId(messageId);
        return review;
    }

    @Test
    void disabledPipelineGeneratesNothing() {
        scheduler(false, 1).run();

        verify(generationService, never()).generateNow();
    }

    @Test
    void generatesWhenThereIsNoPreviousAiPost() {
        scheduler(true, 3).run();

        verify(generationService).generateNow();
        verify(reviewService).submitForReview(any());
    }

    @Test
    void openReviewBlocksANewGeneration() {
        when(reviewRepository.findFirstByStateIn(any()))
                .thenReturn(Optional.of(openReview(ReviewState.PENDING, 100L)));

        scheduler(true, 1).run();

        verify(generationService, never()).generateNow();
        verify(reviewService, never()).deliver(any());
    }

    @Test
    void reviewAwaitingEditAlsoBlocksGeneration() {
        when(reviewRepository.findFirstByStateIn(any()))
                .thenReturn(Optional.of(openReview(ReviewState.AWAITING_EDIT, 100L)));

        scheduler(true, 1).run();

        verify(generationService, never()).generateNow();
    }

    @Test
    void undeliveredReviewIsRetriedInsteadOfBlockingForever() {
        // A Telegram outage at notify time leaves the review with no message id.
        PostReviewEntity stranded = openReview(ReviewState.PENDING, null);
        when(reviewRepository.findFirstByStateIn(any())).thenReturn(Optional.of(stranded));

        scheduler(true, 1).run();

        verify(reviewService).deliver(stranded);
        verify(generationService, never()).generateNow();
    }

    @Test
    void cadenceGateBlocksWhenTheIntervalHasNotElapsed() {
        when(blogPostRepository.findLatestAiGeneratedCreatedAt())
                .thenReturn(Optional.of(NOW.minusDays(1)));

        scheduler(true, 3).run();

        verify(generationService, never()).generateNow();
    }

    @Test
    void cadenceGateOpensOnTheExactInterval() {
        when(blogPostRepository.findLatestAiGeneratedCreatedAt())
                .thenReturn(Optional.of(NOW.minusDays(3)));

        scheduler(true, 3).run();

        verify(generationService).generateNow();
    }

    @Test
    void weeklyCadenceBlocksOnDaySix() {
        when(blogPostRepository.findLatestAiGeneratedCreatedAt())
                .thenReturn(Optional.of(NOW.minusDays(6)));

        scheduler(true, 7).run();

        verify(generationService, never()).generateNow();
    }

    @Test
    void forcedRunIgnoresTheCadenceGate() {
        when(blogPostRepository.findLatestAiGeneratedCreatedAt())
                .thenReturn(Optional.of(NOW.minusHours(1)));

        scheduler(true, 7).generate(true);

        verify(generationService).generateNow();
    }

    @Test
    void forcedRunStillRespectsTheOpenReviewGate() {
        when(reviewRepository.findFirstByStateIn(any()))
                .thenReturn(Optional.of(openReview(ReviewState.PENDING, 100L)));

        scheduler(true, 7).generate(true);

        verify(generationService, never()).generateNow();
    }

    @Test
    void generationFailureIsSwallowedSoTheSchedulerSurvives() {
        when(generationService.generateNow()).thenThrow(new IllegalStateException("GPT down"));

        scheduler(true, 1).run();

        verify(reviewService, never()).submitForReview(any());
    }
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run: `./mvnw test -Dtest=ContentReviewSchedulerTest`
Expected: FAIL — `ContentReviewScheduler` не существует.

- [ ] **Step 3: Добавить бин Clock**

В `src/main/java/com/cms/config/ApplicationConfig.java` добавить импорт `java.time.Clock` и бин:

```java
    /** Injected rather than called statically so the cadence gate can be tested at a fixed instant. */
    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }
```

- [ ] **Step 4: Реализовать планировщик**

`src/main/java/com/cms/service/ContentReviewScheduler.java`:

```java
package com.cms.service;

import com.cms.entity.BlogPostEntity;
import com.cms.entity.PostReviewEntity;
import com.cms.entity.ReviewState;
import com.cms.repo.BlogPostRepository;
import com.cms.repo.PostReviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/** Decides when a new post should be generated and hands it to the review pipeline. */
@Service
public class ContentReviewScheduler {

    private static final Logger log = LoggerFactory.getLogger(ContentReviewScheduler.class);

    private static final List<ReviewState> OPEN_STATES =
            List.of(ReviewState.PENDING, ReviewState.AWAITING_EDIT);

    private final ContentGenerationService generationService;
    private final PostReviewService reviewService;
    private final PostReviewRepository reviewRepository;
    private final BlogPostRepository blogPostRepository;
    private final boolean enabled;
    private final int intervalDays;
    private final Clock clock;

    public ContentReviewScheduler(ContentGenerationService generationService,
                                  PostReviewService reviewService,
                                  PostReviewRepository reviewRepository,
                                  BlogPostRepository blogPostRepository,
                                  @Value("${content.generation.enabled:true}") boolean enabled,
                                  @Value("${content.generation.interval-days:1}") int intervalDays,
                                  Clock clock) {
        this.generationService = generationService;
        this.reviewService = reviewService;
        this.reviewRepository = reviewRepository;
        this.blogPostRepository = blogPostRepository;
        this.enabled = enabled;
        this.intervalDays = intervalDays;
        this.clock = clock;
    }

    /**
     * Wakes up daily; the cadence itself comes from content.generation.interval-days.
     *
     * <p>The interval is not expressed as a cron field on purpose: "0 0 8 * /3 * *" means the
     * 1st, 4th, 7th ... 28th, 31st of the month, so the gap collapses to a single day at
     * every month boundary. Counting elapsed days gives exact 1/3/7 semantics and catches
     * up on its own after downtime.
     */
    @Scheduled(cron = "${content.generation.cron:0 0 8 * * *}")
    public void run() {
        generate(false);
    }

    /** @param forced skips the cadence gate; the open-review gate still applies. */
    public void generate(boolean forced) {
        if (!enabled) {
            log.debug("Scheduled content generation is disabled");
            return;
        }

        Optional<PostReviewEntity> open = reviewRepository.findFirstByStateIn(OPEN_STATES);
        if (open.isPresent()) {
            PostReviewEntity review = open.get();
            if (review.getTelegramMessageId() == null) {
                // Generation succeeded but Telegram was unreachable. Without this retry the
                // open-review gate below would block the pipeline permanently.
                log.info("Review {} was never delivered; retrying the notification", review.getId());
                reviewService.deliver(review);
            } else {
                log.info("Review {} is still open ({}); skipping generation",
                        review.getId(), review.getState());
            }
            return;
        }

        if (!forced && !intervalElapsed()) {
            log.info("Less than {} day(s) since the last AI post; skipping generation", intervalDays);
            return;
        }

        try {
            BlogPostEntity post = generationService.generateNow();
            reviewService.submitForReview(post);
            log.info("Generated post id={} slug={} and sent it for review",
                    post.getId(), post.getSlug());
        } catch (Exception e) {
            log.error("Scheduled blog post generation failed", e);
        }
    }

    boolean intervalElapsed() {
        return blogPostRepository.findLatestAiGeneratedCreatedAt()
                .map(last -> ChronoUnit.DAYS.between(last, LocalDateTime.now(clock)) >= intervalDays)
                .orElse(true);
    }
}
```

- [ ] **Step 5: Вычистить расписание из генератора**

В `src/main/java/com/cms/service/ContentGenerationService.java`:
- удалить метод `generateDailyPost()` целиком;
- удалить поле `private final boolean enabled;` и его присваивания в обоих конструкторах;
- удалить параметр `@Value("${content.generation.enabled:true}") boolean enabled` из публичного конструктора и `boolean enabled` из тестового;
- удалить импорт `org.springframework.scheduling.annotation.Scheduled`.

Итоговые сигнатуры конструкторов:

```java
    public ContentGenerationService(YandexGptService gptService,
                                    BlogPostRepository blogPostRepository,
                                    ObjectMapper objectMapper) {
        this(gptService, blogPostRepository, objectMapper, DEFAULT_RETRY_BACKOFF_MS);
    }

    /** Test seam: lets unit tests exercise the retry path without waiting out the backoff. */
    ContentGenerationService(YandexGptService gptService,
                             BlogPostRepository blogPostRepository,
                             ObjectMapper objectMapper,
                             long retryBackoffMs) {
        this.gptService = gptService;
        this.blogPostRepository = blogPostRepository;
        this.objectMapper = objectMapper;
        this.retryBackoffMs = retryBackoffMs;
    }
```

- [ ] **Step 6: Поправить существующий тест генератора**

В `src/test/java/com/cms/service/ContentGenerationServiceTest.java` заменить создание сервиса в `setUp`:

```java
        service = new ContentGenerationService(gptService, repository, new ObjectMapper(), 0L);
```

- [ ] **Step 7: Прогнать всё**

Run: `./mvnw -q clean test`
Expected: PASS. `ContentReviewSchedulerTest` — 11 тестов, остальные наборы зелёные.

- [ ] **Step 8: Закоммитить**

```bash
git add src/main/java/com/cms/service/ContentReviewScheduler.java \
        src/main/java/com/cms/service/ContentGenerationService.java \
        src/main/java/com/cms/config/ApplicationConfig.java \
        src/test/java/com/cms/service/ContentReviewSchedulerTest.java \
        src/test/java/com/cms/service/ContentGenerationServiceTest.java
git commit -m "feat: cadence and open-review gates in a dedicated scheduler"
```

---

### Task 8: TelegramUpdateListener

Фоновый поллинг, фильтр чата и диспетчеризация. Сам цикл `while` не тестируется, но **фильтр по chat id и разбор `callback_data` тестируются обязательно** — это контроль доступа: без него любой, кто найдёт бота, сможет публиковать медицинский контент на сайте клиники.

**Files:**
- Create: `src/main/java/com/cms/service/TelegramUpdateListener.java`
- Test: `src/test/java/com/cms/service/TelegramUpdateListenerTest.java`

**Interfaces:**
- Consumes: `TelegramClient` (Task 4), `PostReviewService.handleAction` / `applyInstruction` (Task 6), `ContentReviewScheduler.generate(boolean)` (Task 7), `ReviewNotifier.notifyIdle` (Task 5)
- Produces:
  - `void start()` — по `ApplicationReadyEvent`
  - `void stop()` — по `@PreDestroy`
  - package-private `void handleUpdate(JsonNode update)` — точка входа для тестов
  - package-private `boolean isFromAdmin(JsonNode update)`

- [ ] **Step 1: Написать падающий тест**

`src/test/java/com/cms/service/TelegramUpdateListenerTest.java`:

```java
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
```

- [ ] **Step 2: Убедиться, что тест падает**

Run: `./mvnw test -Dtest=TelegramUpdateListenerTest`
Expected: FAIL — `TelegramUpdateListener` не существует.

- [ ] **Step 3: Реализовать**

`src/main/java/com/cms/service/TelegramUpdateListener.java`:

```java
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
```

- [ ] **Step 4: Убедиться, что тесты проходят**

Run: `./mvnw test -Dtest=TelegramUpdateListenerTest`
Expected: PASS, 11 тестов.

- [ ] **Step 5: Закоммитить**

```bash
git add src/main/java/com/cms/service/TelegramUpdateListener.java \
        src/test/java/com/cms/service/TelegramUpdateListenerTest.java
git commit -m "feat: long-polling listener with admin-chat access control"
```

---

### Task 9: Конфигурация и проверка вживую

Свойства в `application.yml` и ручная проверка всей цепочки на реальном боте. Это единственное место, где проверяются JPQL из Task 3 и HTTP-вызовы из Task 4.

**Files:**
- Modify: `src/main/resources/application.yml`

**Interfaces:**
- Consumes: все свойства, объявленные в Task 4, 5, 6, 7
- Produces: рабочая конфигурация; новых Java-интерфейсов нет

- [ ] **Step 1: Добавить свойства**

В `src/main/resources/application.yml` заменить блок `content` и добавить блок `telegram`, а также `site-base-url` в `app`:

```yaml
app:
  upload-dir: ${APP_UPLOAD_DIR}
  cookie:
    # Set to false only for local plain-HTTP development.
    secure: ${APP_COOKIE_SECURE:true}
  # Used to link to a published post from the Telegram confirmation message.
  site-base-url: ${SITE_BASE_URL:}

content:
  generation:
    enabled: ${CONTENT_GENERATION_ENABLED:true}
    cron: ${CONTENT_GENERATION_CRON:0 0 8 * * *}
    # Whole days between posts: 1 daily, 3 every third day, 7 weekly. Deliberately not a
    # cron field - "*/3" in day-of-month collapses the gap at every month boundary.
    interval-days: ${CONTENT_GENERATION_INTERVAL_DAYS:1}

telegram:
  # An empty token or chat id switches the bot off entirely: the app still boots and still
  # generates, posts just stay drafts. Needed for local development and CI.
  bot-token: ${TELEGRAM_BOT_TOKEN:}
  admin-chat-id: ${TELEGRAM_ADMIN_CHAT_ID:}
  poll-timeout-seconds: ${TELEGRAM_POLL_TIMEOUT:30}
  # Must stay above poll-timeout-seconds or every idle long poll fails on read.
  read-timeout-seconds: ${TELEGRAM_READ_TIMEOUT:40}
  connect-timeout-seconds: ${TELEGRAM_CONNECT_TIMEOUT:10}
  max-revisions: ${TELEGRAM_MAX_REVISIONS:5}
```

- [ ] **Step 2: Убедиться, что вся сборка зелёная**

Run: `./mvnw clean verify`
Expected: PASS, все наборы тестов. Ни БД, ни токен Telegram не требуются.

- [ ] **Step 3: Проверить, что приложение поднимается без бота**

Поднять с пустым `TELEGRAM_BOT_TOKEN` и реальной БД:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/cms \
POSTGRESQL_USER=postgres POSTGRESQL_PASSWORD=postgres \
APP_UPLOAD_DIR=/tmp/uploads JWT_SECRET=$(openssl rand -hex 32) \
YANDEX_GPT_API_KEY=dummy YANDEX_GPT_FOLDER_ID=dummy \
CONTENT_GENERATION_ENABLED=false \
./mvnw spring-boot:run
```

Ожидается в логах:
- `telegram.bot-token or telegram.admin-chat-id is empty; the review bot is off`
- `Telegram is not configured; the review bot stays off`
- отсутствие ошибок Hibernate — **это и есть проверка JPQL из Task 3**: сломанный `@Query` валит старт при инициализации фабрики репозиториев
- в БД появилась таблица `post_review`

Проверить схему:

```bash
psql -h localhost -U postgres -d cms -c '\d post_review'
```

- [ ] **Step 4: Проверить полный цикл на реальном боте**

Создать бота через `@BotFather`, узнать свой chat id (написать боту и открыть `https://api.telegram.org/bot<TOKEN>/getUpdates`), затем запустить с реальными ключами и `CONTENT_GENERATION_ENABLED=true`, `SITE_BASE_URL=https://<домен>`.

Пройти сценарии по порядку:

1. Дождаться срабатывания cron (или временно поставить `CONTENT_GENERATION_CRON` на ближайшую минуту). Превью пришло, разбито на сообщения, кнопки на последнем.
2. Написать боту `/generate` при открытом ревью — генерация не запускается.
3. Нажать ✏️ → отправить «сократи вдвое» → пришла переписанная версия, в шапке `Ревизия: 1`.
4. Нажать ✅ → пришла ссылка; `GET /api/cms/blog` содержит пост.
5. Нажать ✅ на том же сообщении ещё раз — ничего не происходит, в логах `Ignoring 'approve' for review ... already in state APPROVED`.
6. Написать боту с другого аккаунта — реакции нет, в логах на уровне `debug` сообщение о сброшенном апдейте.
7. `/generate` при закрытом ревью — новый пост генерируется сразу, минуя интервал.

- [ ] **Step 5: Закоммитить**

```bash
git add src/main/resources/application.yml
git commit -m "feat: wire up Telegram review pipeline configuration"
```

---

## Что осталось за рамками

Идёт отдельными спеками, как согласовано:

- **Спек 2** — генерация картинки через YandexART, вынос записи файлов из `MediaController` в сервис, поле `coverImageUrl`, картинка в превью ревью.
- **Спек 3** — постинг одобренного поста в Telegram-канал (фото + анонс до 1024 символов + ссылка), JSON-LD разметка `Article` и `FAQPage`. Добавит поле `telegramSummary` в тот же JSON-контракт модели.

## Известные ограничения реализации

- **Один экземпляр приложения.** `@Scheduled` без распределённой блокировки и long polling с единственным потребителем предполагают ровно один инстанс.
- **Репозиторные запросы без автотестов.** JPQL из Task 3 проверяется только стартом приложения (Task 9, Step 3). Покрыть их можно было бы через `@DataJpaTest` с H2, но спек запрещает поднимать Spring-контекст в тестах — если решение изменится, это отдельная задача.
- **`TelegramClient` и цикл поллинга без автотестов** — по решению спека, проверяются вручную в Task 9, Step 4.
- **Ревизии не версионируются** — регенерация перезаписывает поля того же поста, хранятся только `revisionCount` и последняя инструкция.
