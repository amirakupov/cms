package com.cms.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "service")
public class ServiceEntity {
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    private Integer id;
    private String slug;
    private String imageSrc;
    private String serviceName;
    private Long price;
    private String description;
    @Column(length = 1000)
    private String longDescription;
}
