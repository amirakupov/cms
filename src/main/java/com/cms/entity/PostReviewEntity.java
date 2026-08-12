package com.cms.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "post_review")
public class PostReviewEntity {

    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    private Integer id;

    @OneToOne(optional = false)
    @JoinColumn(name = "blog_post_id", nullable = false, unique = true)
    private BlogPostEntity blogPost;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReviewState state;

    /** Id of the message carrying the buttons; null until the preview reached Telegram. */
    private Long telegramMessageId;

    @Column(nullable = false)
    private int revisionCount;

    @Column(columnDefinition = "TEXT")
    private String lastInstruction;

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
