package com.cms.dto;

import com.cms.entity.ServiceEntity;


public record ServiceResponseDto(
        Integer id,
        String slug,
        String imageSrc,
        String serviceName,
        Long price,
        String description,
        String longDescription
) {
    public static ServiceResponseDto from(ServiceEntity entity){
        return new ServiceResponseDto(
                entity.getId(),
                entity.getSlug(),
                entity.getImageSrc(),
                entity.getServiceName(),
                entity.getPrice(),
                entity.getDescription(),
                entity.getLongDescription()
        );
    }
}
