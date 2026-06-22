package com.teng.app.gastosai.dto;

import com.teng.app.gastosai.entity.Bucket;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Bulk-assign categories to budgeting buckets. A null bucket clears the assignment. */
public record BucketAssignmentRequest(@NotNull @Valid List<Item> assignments) {

    public record Item(@NotNull Long categoryId, Bucket bucket) {
    }
}
