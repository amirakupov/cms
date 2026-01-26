package com.cms.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity
@Table(name = "service")
public class ServiceEntity {
    @GeneratedValue
    @Id
    private int id;
    private String slug;
    private String imageSrc;
    private String serviceName;
    private long price;
    private String description;
    private String longDescription;
}
