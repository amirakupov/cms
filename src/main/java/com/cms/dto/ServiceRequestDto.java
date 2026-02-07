package com.cms.dto;

import com.cms.entity.ServiceEntity;

public record ServiceRequestDto (
    String slug,
    String imageSrc,
    String serviceName,
    Long price,
    String description,
    String longDescription
){
    public static ServiceEntity toEntity(ServiceRequestDto serviceRequestDto){
        ServiceEntity e = new ServiceEntity();
        e.setSlug(serviceRequestDto.slug);
        e.setImageSrc(serviceRequestDto.imageSrc());
        e.setServiceName(serviceRequestDto.serviceName());
        e.setPrice(serviceRequestDto.price());
        e.setDescription(serviceRequestDto.description());
        e.setLongDescription(serviceRequestDto.longDescription());
        return e;

    }

}
