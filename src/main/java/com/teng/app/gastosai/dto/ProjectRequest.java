package com.teng.app.gastosai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The new name for an existing tag. Renaming keeps the tag's id, and so keeps its expenses. */
public record ProjectRequest(
		@NotBlank @Size(max = 60) String name
) {}
