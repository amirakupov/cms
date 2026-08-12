package com.cms.service;

import com.cms.entity.BlogPostEntity;
import com.cms.entity.PageStatus;
import com.cms.entity.PostReviewEntity;
import com.cms.entity.ReviewState;
import com.cms.repo.BlogPostRepository;
import com.cms.repo.PostReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
