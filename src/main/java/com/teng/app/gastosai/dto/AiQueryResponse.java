package com.teng.app.gastosai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiQueryResponse(Object answer) {
}
