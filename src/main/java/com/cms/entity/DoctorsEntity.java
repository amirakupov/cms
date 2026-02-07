package com.cms.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "doctors")
public class DoctorsEntity {
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    private Integer id;
    private String imgSrc;
    private String name;
    private String specialty;
    @Column(length = 1000)
    private String bio;
}
