package com.cms.dto;

import com.cms.entity.PageEntity;
import com.cms.entity.PageStatus;

public record PageRequestDto(
        String slug,
        String title,
        String body,
        String metaDescription,
        String status
) {
    public static PageEntity toEntity(PageRequestDto dto) {
        PageEntity e = new PageEntity();
        e.setSlug(dto.slug());
        e.setTitle(dto.title());
        e.setBody(dto.body());
        e.setMetaDescription(dto.metaDescription());
        e.setStatus(dto.status() != null ? PageStatus.valueOf(dto.status()) : PageStatus.DRAFT);
        return e;
    }
}
