package com.cms.entity;

/**
 * Where a generated post sits in the admin review pipeline.
 *
 * <p>Deliberately separate from {@link PageStatus}: "is it visible on the site" and "where
 * is it in review" are orthogonal, and PageStatus is shared with PageEntity, which has no
 * review workflow at all.
 */
public enum ReviewState {
    PENDING,
    AWAITING_EDIT,
    APPROVED,
    REJECTED
}
