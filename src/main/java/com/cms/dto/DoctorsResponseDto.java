package com.cms.dto;

import com.cms.entity.DoctorsEntity;

public record DoctorsResponseDto (
        Integer id,
        String imgSrc,
        String name,
        String specialty,
        String bio
){
    public static DoctorsResponseDto from(DoctorsEntity entity){
        return new DoctorsResponseDto(
                entity.getId(),
                entity.getImgSrc(),
                entity.getName(),
                entity.getSpecialty(),
                entity.getBio());
    }
}
