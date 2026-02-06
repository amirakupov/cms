package com.cms.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "doctors")
public class DoctorsEntity {
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    Integer id;
    String imgSrc;
    String name;
    String specialty;
    @Column(length = 1000)
    String bio;
}
