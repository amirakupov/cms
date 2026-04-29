package com.cms.service;

import com.cms.entity.BlogPostEntity;
import com.cms.entity.PageStatus;
import com.cms.repo.BlogPostRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class ContentGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ContentGenerationService.class);
    private static final Pattern NON_LATIN = Pattern.compile("[^\\w-]");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s]+");

    private final YandexGptService gptService;
    private final BlogPostRepository blogPostRepository;
    private final ObjectMapper objectMapper;

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
                                    ObjectMapper objectMapper) {
        this.gptService = gptService;
        this.blogPostRepository = blogPostRepository;
        this.objectMapper = objectMapper;
    }

    @Scheduled(cron = "${content.generation.cron:0 0 8 * * *}")
    public void generateDailyPost() {
        String topic = pickTopic();
        log.info("Generating blog post on topic: {}", topic);

        try {
            String systemPrompt = """
                    Ты — медицинский копирайтер для сайта клиники. \
                    Напиши SEO-оптимизированную статью для блога. \
                    Ответ верни строго в JSON формате:
                    {
                      "title": "заголовок статьи",
                      "body": "полный текст статьи в HTML формате",
                      "metaDescription": "мета-описание до 160 символов",
                      "keywords": "ключевые слова через запятую"
                    }
                    Без markdown, только чистый JSON.""";

            String raw = gptService.generate(systemPrompt, "Тема: " + topic);

            // strip possible markdown fences
            String json = raw.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();

            JsonNode node = objectMapper.readTree(json);

            BlogPostEntity post = new BlogPostEntity();
            post.setTitle(node.get("title").asText());
            post.setBody(node.get("body").asText());
            post.setMetaDescription(node.get("metaDescription").asText());
            post.setKeywords(node.get("keywords").asText());
            post.setSlug(toSlug(post.getTitle()));
            post.setStatus(PageStatus.DRAFT);
            post.setAiGenerated(true);

            blogPostRepository.save(post);
            log.info("Generated blog post: {}", post.getTitle());

        } catch (Exception e) {
            log.error("Failed to generate blog post for topic: {}", topic, e);
        }
    }

    private String pickTopic() {
        long count = blogPostRepository.count();
        return TOPICS.get((int) (count % TOPICS.size()));
    }

    private String toSlug(String input) {
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD);
        String slug = WHITESPACE.matcher(normalized).replaceAll("-");
        slug = NON_LATIN.matcher(slug).replaceAll("");
        slug = slug.toLowerCase(Locale.ROOT).replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
        if (slug.isEmpty()) slug = "post";
        // ensure uniqueness
        String base = slug;
        int i = 1;
        while (blogPostRepository.findBySlug(slug).isPresent()) {
            slug = base + "-" + i++;
        }
        return slug;
    }
}
