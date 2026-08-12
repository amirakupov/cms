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
