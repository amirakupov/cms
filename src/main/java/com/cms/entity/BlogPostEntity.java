package com.cms.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "blog_post")
public class BlogPostEntity {
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    private Integer id;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(length = 512)
    private String metaDescription;

    @Column(length = 512)
    private String keywords;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PageStatus status;

    private boolean aiGenerated;

    /** Topic this post was generated from; drives topic rotation. Null for manual posts. */
    @Column(length = 255)
    private String sourceTopic;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
