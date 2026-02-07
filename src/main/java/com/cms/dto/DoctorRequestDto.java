package com.cms.dto;

import com.cms.entity.DoctorsEntity;

public record DoctorRequestDto(
        String imgSrc,
        String name,
        String specialty,
        String bio
) {
    public static DoctorsEntity toEntity(DoctorRequestDto request){
        DoctorsEntity entity = new DoctorsEntity();
        entity.setImgSrc(request.imgSrc());
        entity.setName(request.name());
        entity.setSpecialty(request.specialty());
        entity.setBio(request.bio());
        return entity;
    }
}
