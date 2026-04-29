package com.cms.dto;

import com.cms.entity.BlogPostEntity;

import java.time.LocalDateTime;

public record BlogPostResponseDto(
        Integer id,
        String slug,
        String title,
        String body,
        String metaDescription,
        String keywords,
        String status,
        boolean aiGenerated,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static BlogPostResponseDto from(BlogPostEntity entity) {
        return new BlogPostResponseDto(
                entity.getId(),
                entity.getSlug(),
                entity.getTitle(),
                entity.getBody(),
                entity.getMetaDescription(),
                entity.getKeywords(),
                entity.getStatus().name(),
                entity.isAiGenerated(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
