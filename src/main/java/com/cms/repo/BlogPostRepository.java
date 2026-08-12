package com.cms.repo;

import com.cms.entity.BlogPostEntity;
import com.cms.entity.PageStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

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

    /** Topics of previously generated posts, oldest first. Drives round-robin topic selection. */
    @Query("select b.sourceTopic from BlogPostEntity b where b.sourceTopic is not null order by b.id asc")
    List<String> findUsedTopics();
}
