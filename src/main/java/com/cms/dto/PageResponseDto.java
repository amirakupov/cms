package com.cms.dto;

import com.cms.entity.PageEntity;

import java.time.LocalDateTime;

public record PageResponseDto(
        Integer id,
        String slug,
        String title,
        String body,
        String metaDescription,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static PageResponseDto from(PageEntity entity) {
        return new PageResponseDto(
                entity.getId(),
                entity.getSlug(),
                entity.getTitle(),
                entity.getBody(),
                entity.getMetaDescription(),
                entity.getStatus().name(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
