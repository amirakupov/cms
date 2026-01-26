package com.cms.dto;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;

@Data
public class ServiceDto {
    private int id;
    private String slug;
    private String imageSrc;
    private String serviceName;
    private long price;
    private String description;
    private String longDescription;
}
