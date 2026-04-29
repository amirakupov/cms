package com.cms.dto;

import com.cms.entity.BlogPostEntity;
import com.cms.entity.PageStatus;

public record BlogPostRequestDto(
        String slug,
        String title,
        String body,
        String metaDescription,
        String keywords,
        String status
) {
    public static BlogPostEntity toEntity(BlogPostRequestDto dto) {
        BlogPostEntity e = new BlogPostEntity();
        e.setSlug(dto.slug());
        e.setTitle(dto.title());
        e.setBody(dto.body());
        e.setMetaDescription(dto.metaDescription());
        e.setKeywords(dto.keywords());
        e.setStatus(dto.status() != null ? PageStatus.valueOf(dto.status()) : PageStatus.DRAFT);
        return e;
    }
}
