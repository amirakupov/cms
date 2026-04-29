package com.cms.repo;

import com.cms.entity.BlogPostEntity;
import com.cms.entity.PageStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BlogPostRepository extends JpaRepository<BlogPostEntity, Integer> {
    Optional<BlogPostEntity> findBySlug(String slug);
    List<BlogPostEntity> findByStatus(PageStatus status);
}
