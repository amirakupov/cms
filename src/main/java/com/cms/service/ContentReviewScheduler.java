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
     * <p>The interval is not expressed as a cron field on purpose: a step of 3 in
     * day-of-month means the 1st, 4th, 7th ... 28th, 31st, so the gap collapses to a single
     * day at every month boundary. Counting elapsed days gives exact 1/3/7 semantics and
     * catches up on its own after downtime.
     */
    @Scheduled(cron = "${content.generation.cron:0 0 8 * * *}")
    public void run() {
        generate(false);
    }

    /**
     * @param forced skips the cadence gate; the open-review gate still applies
     */
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
                // open-review gate would block the pipeline permanently.
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
