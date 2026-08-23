package com.cms.service;

import com.cms.entity.BlogPostEntity;
import com.cms.entity.PageStatus;
import com.cms.repo.BlogPostRepository;
import com.cms.util.SlugUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ContentGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ContentGenerationService.class);

    private static final int MAX_ATTEMPTS = 3;
    private static final long DEFAULT_RETRY_BACKOFF_MS = 5_000;
    private static final int META_DESCRIPTION_LIMIT = 160;
    private static final int KEYWORDS_LIMIT = 500;

    /** Article HTML is model-authored: allow formatting tags only, never scripts or handlers. */
    private static final Safelist ARTICLE_SAFELIST = Safelist.relaxed()
            .removeTags("img")
            .addAttributes("a", "rel", "target");

    private final YandexGptService gptService;
    private final BlogPostRepository blogPostRepository;
    private final ObjectMapper objectMapper;
    private final long retryBackoffMs;

    private static final List<String> TOPICS = List.of(
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
            "Как проходит приём невролога и какие обследования назначают"
    );

    /**
     * Explicitly marked: the class has a second, test-only constructor, and Spring refuses to
     * pick between two candidates on its own - it looks for a no-arg one and fails startup.
     */
    @Autowired
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

    /**
     * Generates and stores one post as a DRAFT. Retries transient GPT failures.
     *
     * @throws IllegalStateException if every attempt failed
     */
    public BlogPostEntity generateNow() {
        TopicChoice choice = pickTopic();
        log.info("Generating blog post on topic: {} (repeat #{})", choice.topic(), choice.repeatCount());

        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return generateAndSave(choice);
            } catch (RuntimeException e) {
                lastFailure = e;
                log.warn("Generation attempt {}/{} failed for topic '{}': {}",
                        attempt, MAX_ATTEMPTS, choice.topic(), e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    sleepQuietly(retryBackoffMs * attempt);
                }
            }
        }
        throw new IllegalStateException(
                "Could not generate a blog post for topic: " + choice.topic(), lastFailure);
    }

    private BlogPostEntity generateAndSave(TopicChoice choice) {
        String raw = gptService.generate(systemPrompt(), userPrompt(choice));
        JsonNode node = parseJsonPayload(raw);

        String title = requireText(node, "title");
        String bodyHtml = requireText(node, "body");

        BlogPostEntity post = new BlogPostEntity();
        post.setTitle(title);
        post.setBody(Jsoup.clean(bodyHtml, ARTICLE_SAFELIST));
        post.setMetaDescription(trim(node.path("metaDescription").asText(""), META_DESCRIPTION_LIMIT));
        post.setKeywords(trim(node.path("keywords").asText(""), KEYWORDS_LIMIT));
        post.setSlug(uniqueSlug(SlugUtil.toSlug(title)));
        post.setStatus(PageStatus.DRAFT);
        post.setAiGenerated(true);
        post.setSourceTopic(choice.topic());

        return saveWithSlugFallback(post, title);
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

    private String userPrompt(TopicChoice choice) {
        if (choice.repeatCount() == 0) {
            return "Тема: " + choice.topic();
        }
        // Every topic has been used already - ask for a genuinely different angle
        // instead of silently republishing near-duplicate content.
        return "Тема: " + choice.topic()
                + "\nНа сайте уже опубликовано статей по этой теме: " + choice.repeatCount()
                + ". Раскрой тему под новым, не повторяющимся углом"
                + " (другая аудитория, другой аспект, другой формат подачи)"
                + " и придумай отличающийся заголовок.";
    }

    /**
     * Extracts the JSON object from a model answer, tolerating markdown fences and
     * stray prose around it without mangling backticks inside the article body.
     */
    JsonNode parseJsonPayload(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("Model answer contained no JSON object: " + preview(raw));
        }
        String json = raw.substring(start, end + 1);
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isObject()) {
                throw new IllegalStateException("Model answer was not a JSON object: " + preview(raw));
            }
            return node;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Model answer was not valid JSON: " + preview(raw), e);
        }
    }

    private static String requireText(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new IllegalStateException("Model answer is missing required field '" + field + "'");
        }
        return value;
    }

    private static String trim(String value, int limit) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String clean = value.strip();
        return clean.length() <= limit ? clean : clean.substring(0, limit);
    }

    /**
     * Picks the least-used topic so that manually created posts cannot skew rotation and
     * every topic is exhausted before any is repeated.
     */
    TopicChoice pickTopic() {
        List<String> used = blogPostRepository.findUsedTopics();

        Map<String, Integer> counts = new HashMap<>();
        TOPICS.forEach(t -> counts.put(t, 0));
        used.stream().filter(counts::containsKey).forEach(t -> counts.merge(t, 1, Integer::sum));

        Map.Entry<String, Integer> least = counts.entrySet().stream()
                .min(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparing(e -> TOPICS.indexOf(e.getKey())))
                .orElseThrow();

        if (least.getValue() > 0) {
            log.warn("All {} topics have been used at least {} time(s); "
                            + "consider extending the topic list to avoid duplicate content",
                    TOPICS.size(), least.getValue());
        }
        return new TopicChoice(least.getKey(), least.getValue());
    }

    /** Resolves slug collisions with a single query instead of one per candidate. */
    private String uniqueSlug(String base) {
        Set<String> taken = Set.copyOf(blogPostRepository.findSlugsStartingWith(base));
        if (!taken.contains(base)) {
            return base;
        }
        for (int i = 1; i <= taken.size() + 1; i++) {
            String candidate = base + "-" + i;
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
        return base + "-" + UUID.randomUUID().toString().substring(0, 6);
    }

    private static String preview(String s) {
        if (s == null) {
            return "<null>";
        }
        String clean = s.strip();
        return clean.length() <= 300 ? clean : clean.substring(0, 300) + "...";
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    record TopicChoice(String topic, int repeatCount) {
    }
}
