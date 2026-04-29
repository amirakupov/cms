package com.cms.repo;

import com.cms.entity.PageEntity;
import com.cms.entity.PageStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PageRepository extends JpaRepository<PageEntity, Integer> {
    Optional<PageEntity> findBySlug(String slug);
    List<PageEntity> findByStatus(PageStatus status);
}
