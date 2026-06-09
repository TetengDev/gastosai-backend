package com.teng.app.gastosai.dto;

import java.util.List;

public record ImportResult(int imported, int skipped, List<String> errors) {}