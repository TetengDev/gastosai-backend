package com.teng.app.gastosai;

import com.teng.app.gastosai.ai.ExpenseParser;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ExpenseParserPromptTest {

    @Test
    void buildSystemPrompt_containsInjectedDate() {
        LocalDate date = LocalDate.of(2026, 6, 10);
        String prompt = ExpenseParser.buildSystemPrompt(date);
        assertThat(prompt).contains("2026-06-10");
    }

    @Test
    void buildSystemPrompt_containsMealPlanCategory() {
        String prompt = ExpenseParser.buildSystemPrompt(LocalDate.now());
        assertThat(prompt).contains("Meal Plan");
    }

    @Test
    void buildSystemPrompt_containsTransportationCategory() {
        String prompt = ExpenseParser.buildSystemPrompt(LocalDate.now());
        assertThat(prompt).contains("Transportation");
    }

    @Test
    void buildSystemPrompt_containsPhilippineReference() {
        String prompt = ExpenseParser.buildSystemPrompt(LocalDate.now());
        assertThat(prompt).contains("Philippine");
    }

    @Test
    void buildSystemPrompt_containsYesterdayDate() {
        LocalDate today = LocalDate.of(2026, 6, 10);
        String prompt = ExpenseParser.buildSystemPrompt(today);
        assertThat(prompt).contains("2026-06-09");
    }
}
