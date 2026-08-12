package com.cms.repo;

import com.cms.entity.PostReviewEntity;
import com.cms.entity.ReviewState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;

@Repository
public interface PostReviewRepository extends JpaRepository<PostReviewEntity, Integer> {

    /** The one open review, if any: the pipeline never keeps more than a single post in review. */
    Optional<PostReviewEntity> findFirstByStateIn(Collection<ReviewState> states);
}
