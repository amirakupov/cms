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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
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
    private static final long RETRY_BACKOFF_MS = 5_000;
    private static final int META_DESCRIPTION_LIMIT = 160;
    private static final int KEYWORDS_LIMIT = 500;

    /** Article HTML is model-authored: allow formatting tags only, never scripts or handlers. */
    private static final Safelist ARTICLE_SAFELIST = Safelist.relaxed()
            .removeTags("img")
            .addAttributes("a", "rel", "target");

    private final YandexGptService gptService;
    private final BlogPostRepository blogPostRepository;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    private static final List<String> TOPICS = List.of(
            "Профилактика сердечно-сосудистых заболеваний",
            "Как подготовиться к первому визиту к терапевту",
            "Современные методы диагностики заболеваний",
            "Здоровое питание: рекомендации врачей",
            "Когда нужно обращаться к неврологу",
            "Вакцинация взрослых: что нужно знать",
            "Профилактика заболеваний позвоночника",
            "Чек-ап: зачем нужны регулярные обследования",
            "Стресс и его влияние на здоровье",
            "Сезонные заболевания: профилактика и лечение"
    );

    public ContentGenerationService(YandexGptService gptService,
                                    BlogPostRepository blogPostRepository,
                                    ObjectMapper objectMapper,
                                    @Value("${content.generation.enabled:true}") boolean enabled) {
        this.gptService = gptService;
        this.blogPostRepository = blogPostRepository;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${content.generation.cron:0 0 8 * * *}")
    public void generateDailyPost() {
        if (!enabled) {
            log.debug("Scheduled content generation is disabled");
            return;
        }
        try {
            BlogPostEntity post = generateNow();
            log.info("Generated blog post id={} slug={} title={}", post.getId(), post.getSlug(), post.getTitle());
        } catch (Exception e) {
            log.error("Scheduled blog post generation failed after {} attempts", MAX_ATTEMPTS, e);
        }
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
                    sleepQuietly(RETRY_BACKOFF_MS * attempt);
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

        try {
            return blogPostRepository.saveAndFlush(post);
        } catch (DataIntegrityViolationException e) {
            // Another instance claimed the slug between our check and the insert.
            log.warn("Slug '{}' was taken concurrently, retrying with a random suffix", post.getSlug());
            post.setSlug(SlugUtil.toSlug(title) + "-" + UUID.randomUUID().toString().substring(0, 6));
            return blogPostRepository.saveAndFlush(post);
        }
    }

    private String systemPrompt() {
        return """
                Ты — медицинский копирайтер для сайта клиники.
                Напиши SEO-оптимизированную статью для блога на 600–900 слов.

                Требования к тексту:
                - Не ставь диагнозов и не назначай лечение.
                - Не выдумывай статистику, исследования и цитаты.
                - Добавь в конце фразу о необходимости очной консультации врача.

                Ответ верни строго в JSON формате, одним объектом:
                {
                  "title": "заголовок статьи",
                  "body": "полный текст статьи в HTML формате (h2, h3, p, ul, ol, li, strong, em)",
                  "metaDescription": "мета-описание до 160 символов",
                  "keywords": "ключевые слова через запятую"
                }

                Не оборачивай ответ в markdown. Не добавляй текст до или после JSON.
                Все кавычки внутри значений экранируй.""";
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
