package com.teng.app.gastosai.ai;

import com.teng.app.gastosai.dto.ParsedExpenseResult;

import java.time.LocalDate;

public interface ExpenseParser {
    LlmResult<ParsedExpenseResult> parse(String text);

    static String buildSystemPrompt(LocalDate today) {
        LocalDate yesterday = today.minusDays(1);
        return """
                You are an expense parser for a Filipino user tracking expenses in Philippine Peso (₱).
                Today's date in Philippine Time (UTC+8) is %s.

                Given a free-form text, extract expense information and return ONLY a JSON object — no markdown, no extra text.

                JSON fields:
                - amount: number (required, positive, Philippine Peso; extract the number even when no currency symbol is present)
                - category: string (required; you MUST use one of the exact values listed below — no other values are allowed)
                - date: string or null (ISO 8601 "yyyy-MM-ddTHH:mm:ss"; resolve relative time words — "today"/"ngayon"/"kanina" → %sT12:00:00; "yesterday"/"kahapon"/"kagabi" → %sT12:00:00; null only when no time reference is present at all)
                - description: string (required, concise description of what was spent on)
                - confidence: "HIGH" if amount and description are clearly identifiable; "LOW" if uncertain or ambiguous

                Allowed category values (use exactly as written):
                Meal Plan, Transportation, Monthly Utilities, Training/Upskilling, Hygiene Essentials,
                Cleaning Essentials, Transaction Fees, Date, Family Contributions, Monthly Personal,
                Vacation, Cleaning Essentials, Extras, Uncategorized

                Category mapping rules — apply these before choosing:
                - Food delivery (FoodPanda, Grab Food), restaurants, cafés, coffee shops, milk tea, snacks, groceries, meals → Meal Plan
                - Grab (ride), Angkas, jeep, bus, MRT, LRT, taxi, Uber, toll, parking, gas, fuel → Transportation
                - Electricity, water, internet, WiFi, phone bill, Meralco, Globe, Smart, PLDT, Netflix, Spotify, subscriptions → Monthly Utilities
                - Courses, books, certifications, seminars, tutorials, Udemy, SkillShare → Training/Upskilling
                - Soap, shampoo, conditioner, toothpaste, skincare, lotion, deodorant, personal care, grooming → Hygiene Essentials
                - Detergent, bleach, alcohol, disinfectant, tissue, cleaning supplies → Cleaning Essentials
                - Bank/transfer/convenience fees, payment charges → Transaction Fees
                - Romantic dinner, movies with partner, gifts for partner, couple activities → Date
                - Family remittance, sending money home, family support, pasalubong → Family Contributions
                - Personal monthly allowance, personal spending → Monthly Personal
                - Travel, flights, hotels, trips, vacations → Vacation
                - Clothing, gadgets, accessories, online shopping (Lazada, Shopee), anything else → Extras
                - Intent unclear, essential info missing → Uncategorized
                """.formatted(today, today, yesterday);
    }
}
