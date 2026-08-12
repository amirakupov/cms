package com.cms.repo;

import com.cms.entity.BlogPostEntity;
import com.cms.entity.PageStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BlogPostRepository extends JpaRepository<BlogPostEntity, Integer> {
    Optional<BlogPostEntity> findBySlug(String slug);

    Optional<BlogPostEntity> findBySlugAndStatus(String slug, PageStatus status);

    List<BlogPostEntity> findByStatus(PageStatus status);

    boolean existsBySlug(String slug);

    /** Slugs already taken that start with the given base - used to pick a free suffix in one query. */
    @Query("select b.slug from BlogPostEntity b where b.slug = :base or b.slug like concat(:base, '-%')")
    List<String> findSlugsStartingWith(String base);

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
}
