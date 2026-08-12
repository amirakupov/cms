package com.cms.service;

import com.cms.entity.BlogPostEntity;
import com.cms.entity.PostReviewEntity;
import com.cms.entity.ReviewState;
import com.cms.repo.BlogPostRepository;
import com.cms.repo.PostReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
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
