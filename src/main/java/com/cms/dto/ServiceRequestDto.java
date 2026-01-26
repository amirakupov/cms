package com.cms.dto;

import lombok.Data;

@Data
public class ServiceRequestDto {
    private int id;
    private String slug;
    private String imageSrc;
    private String serviceName;
    private long price;
    private String description;
    private String longDescription;
}
