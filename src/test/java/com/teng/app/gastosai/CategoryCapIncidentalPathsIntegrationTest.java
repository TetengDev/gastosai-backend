package com.teng.app.gastosai;

import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.entity.Role;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.BudgetRepository;
import com.teng.app.gastosai.repository.CategoryRepository;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.repository.RecurringExpenseRepository;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.service.CategorySeedService;
import com.teng.app.gastosai.support.PostgresBackedTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * The four ways a category gets created without anyone visiting {@code POST /categories}, each
 * confirmed against a FREE account standing exactly at its cap, with
 * {@code gastos.monetization.enforce=true}.
 *
 * <p>TEN-319 made {@code CategoryService.getOrCreateByName} enforce the cap, which is the method
 * all four reach. TEN-327 then stopped the 13 seeded starter categories from consuming the cap, so
 * the account this class sets up is the one a real registration produces: 13 system-provided rows
 * plus an untouched allowance of 5. Every path is recorded here as enforced or exempt, because a
 * path nobody decided about is the silent bypass TEN-319 refused:
 *
 * <table><caption>Outcome per path</caption>
 * <tr><th>Path</th><th>Outcome</th></tr>
 * <tr><td>{@code ExpenseService.categorise} — {@code POST /expenses} and expense edits</td>
 *     <td><b>Enforced.</b> 402, and the expense is not written.</td></tr>
 * <tr><td>{@code ChatActionService} — add, edit, recategorize, default-category</td>
 *     <td><b>Enforced.</b> 402 on the chat request itself (TEN-327 stopped the generic catch from
 *     swallowing it into a 200 "something went wrong").</td></tr>
 * <tr><td>{@code CsvImportService.importRows}</td>
 *     <td><b>Enforced.</b> Strict: 402, whole file rolled back. Non-strict (TEN-329): the file is
 *     finished, the rows needing a new category are errors, and {@code ImportResult.limitReached}
 *     names {@code CUSTOM_CATEGORIES} — a structured field, not a per-row error line alone.</td></tr>
 * <tr><td>{@code RecurringExpenseService} — create and materialise</td>
 *     <td><b>Enforced.</b> 402, and no template is written.</td></tr>
 * <tr><td>{@code CategorySeedService} — registration provisioning</td>
 *     <td><b>Exempt.</b> Not a category the user chose; the rows are system-provided and consume
 *     nothing. See {@code EntitlementEnforcementIntegrationTest}.</td></tr>
 * <tr><td>{@code getOrCreateDefault} — {@code Uncategorized}, and the delete fallback</td>
 *     <td><b>Exempt.</b> "I did not say" is not a category anyone chose, and capping the delete
 *     fallback would trap a user at their limit with no way down.</td></tr>
 * <tr><td>Resolving an existing category, by name or alias</td>
 *     <td><b>Exempt.</b> Not a creation. A user over their cap keeps using what they have.</td></tr>
 * <tr><td>{@code AppDataLoader} — local sample data</td>
 *     <td><b>Exempt in practice.</b> Every category name in {@code ExpenseSampleData} and in the
 *     loader's own budget and recurring seeds is one of the 13 starters, so it resolves existing
 *     rows and never creates. It runs only with {@code gastos.seed-sample-data} on.</td></tr>
 * </table>
 *
 * <p>The AI provider is never reached: the chat path is exercised through
 * {@code POST /ai/chat/confirm}, which replays a previously previewed action and so runs no
 * classifier. {@code ChatActionService} is deliberately <em>not</em> mocked here — it is the
 * subject.
 */
@SpringBootTest
@TestPropertySource(properties = {"gastos.monetization.enforce=true", "gastos.ai.allow-shared-key=true"})
class CategoryCapIncidentalPathsIntegrationTest extends PostgresBackedTest {

    private static final int FREE_CATEGORY_CAP = 5;
    private static final int SEEDED_CATEGORY_COUNT = 13;

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtUtil jwtUtil;
    @Autowired CategorySeedService categorySeedService;
    @Autowired CategoryRepository categoryRepository;
    @Autowired ExpenseRepository expenseRepository;
    @Autowired BudgetRepository budgetRepository;
    @Autowired RecurringExpenseRepository recurringExpenseRepository;

    MockMvc mockMvc;
    User free;
    String freeAuth;

    /**
     * A freshly registered FREE account that has since spent its whole allowance: 13 seeded
     * starters plus 5 of its own. Nothing below is testing the cap's arithmetic — that is
     * {@code EntitlementEnforcementIntegrationTest} — only what each path does once it is reached.
     */
    @BeforeEach
    void setUp() throws Exception {
        mockMvc = webAppContextSetup(webApplicationContext).apply(springSecurity()).build();

        free = userRepository.save(User.builder()
                .name("Free User").email("free-paths@test.com")
                .password(passwordEncoder.encode("pw")).role(Role.USER).build());
        freeAuth = "Bearer " + jwtUtil.generate(free.getEmail());

        categorySeedService.seedPredefinedForUser(free);
        for (int i = 1; i <= FREE_CATEGORY_CAP; i++) {
            createCategory("Mine " + i).andExpect(status().isCreated());
        }
        assertThat(categoryRepository.countByUserAndSystemProvidedFalse(free)).isEqualTo(FREE_CATEGORY_CAP);
    }

    // ---------------------------------------------------------------- enforced paths

    @Test
    void expensePath_isRefusedWith402_andWritesNothing() throws Exception {
        mockMvc.perform(post("/expenses")
                        .header("Authorization", freeAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":150.00,\"description\":\"Lunch at Jollibee\","
                                + "\"category\":\"By Expense\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.feature").value("CUSTOM_CATEGORIES"));

        assertNothingWasCreated("By Expense");
        assertThat(expenseRepository.findAllByUserOrderByDateDesc(free)).isEmpty();
    }

    @Test
    void chatPath_isRefusedWith402_andWritesNothing() throws Exception {
        // Before TEN-327 this returned 200 with "Something went wrong while handling that. Please
        // rephrase and try again." — the refusal caught by the handler's blanket catch. Rephrasing
        // cannot buy headroom, so the user was told to retry something that could never work.
        mockMvc.perform(post("/ai/chat/confirm")
                        .header("Authorization", freeAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolName\":\"create_expense\",\"mode\":\"execute\",\"params\":"
                                + "{\"amount\":150.00,\"description\":\"Lunch at Jollibee\","
                                + "\"category\":\"By Chat\"}}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.feature").value("CUSTOM_CATEGORIES"));

        assertNothingWasCreated("By Chat");
        assertThat(expenseRepository.findAllByUserOrderByDateDesc(free)).isEmpty();
    }

    @Test
    void chatPath_budgetAction_isRefusedWith402_andStrandsNoCategory() throws Exception {
        mockMvc.perform(post("/ai/chat/confirm")
                        .header("Authorization", freeAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolName\":\"create_budget\",\"mode\":\"execute\",\"params\":"
                                + "{\"categoryName\":\"By Budget\",\"amountLimit\":4000.00}}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.feature").value("CUSTOM_CATEGORIES"));

        assertNothingWasCreated("By Budget");
        assertThat(budgetRepository.findAll()).isEmpty();
    }

    @Test
    void csvPath_nonStrict_finishesTheFile_andNamesTheLimitItHit() throws Exception {
        // TEN-329. The refusal used to be a 402 that abandoned the rest of the file, so rows naming
        // categories the account already has — which are never capped — were lost to a row that
        // named a new one. Non-strict now finishes the file: row 2 imports, rows 3 and 4 are listed
        // as errors, and `limitReached` names the entitlement so a client can offer the upgrade.
        MockMultipartFile file = csv("""
                date,amount,category,description
                2026-06-15,250.00,Mine 1,Lunch
                2026-06-16,300.00,By Csv,Dinner
                2026-06-17,120.00,By Csv Too,Snacks
                """);

        mockMvc.perform(multipart("/expenses/import").file(file).header("Authorization", freeAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.limitReached").value("CUSTOM_CATEGORIES"))
                .andExpect(jsonPath("$.errors", hasSize(2)));

        assertNothingWasCreated("By Csv");
        assertNothingWasCreated("By Csv Too");
        // The count the response reported is the count actually in the database.
        assertThat(expenseRepository.findAllByUserOrderByDateDesc(free)).hasSize(1);
    }

    @Test
    void csvPath_strictImport_isRefusedWith402_andRollsBackTheWholeFile() throws Exception {
        // The first row would import cleanly; strict mode means the second row's refusal takes it
        // with it, rather than leaving half a file behind a 402.
        MockMultipartFile file = csv("""
                date,amount,category,description
                2026-06-15,250.00,Mine 1,Lunch
                2026-06-16,300.00,By Strict Csv,Dinner
                """);

        mockMvc.perform(multipart("/expenses/import").file(file)
                        .param("strict", "true")
                        .header("Authorization", freeAuth))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.feature").value("CUSTOM_CATEGORIES"));

        assertNothingWasCreated("By Strict Csv");
        assertThat(expenseRepository.findAllByUserOrderByDateDesc(free)).isEmpty();
    }

    @Test
    void recurringPath_isRefusedWith402_andWritesNoTemplate() throws Exception {
        mockMvc.perform(post("/recurring")
                        .header("Authorization", freeAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Netflix\",\"amount\":299.00,\"categoryName\":\"By Recurring\","
                                + "\"frequency\":\"MONTHLY\",\"dayOfMonth\":15}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.feature").value("CUSTOM_CATEGORIES"));

        assertNothingWasCreated("By Recurring");
        assertThat(recurringExpenseRepository.findAllByUser(free)).isEmpty();
    }

    // ---------------------------------------------------------------- exempt paths

    /**
     * The other half of every "enforced" row above: naming a category the user already has is not
     * a creation. Without this, "the cap binds the expense path" could be satisfied by refusing
     * every expense a capped user writes.
     */
    @Test
    void atTheCap_anExpenseInAnExistingCategory_isStillRecorded() throws Exception {
        mockMvc.perform(post("/expenses")
                        .header("Authorization", freeAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":150.00,\"description\":\"Lunch\",\"category\":\"Mine 1\"}"))
                .andExpect(status().isCreated());

        // A seeded starter is an existing category like any other — being system-provided changes
        // what it costs, not whether it can be used.
        mockMvc.perform(post("/expenses")
                        .header("Authorization", freeAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":90.00,\"description\":\"Fare\",\"category\":\"Transportation\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.category").value("Transportation"));
    }

    /**
     * An expense that names no category at all lands in {@code Uncategorized}, cap or no cap.
     * Refusing to record a spend because the user has no row for "I did not say" would be the cap
     * deciding something it has no business deciding.
     */
    @Test
    void atTheCap_anExpenseNamingNoCategory_isStillRecorded() throws Exception {
        mockMvc.perform(post("/expenses")
                        .header("Authorization", freeAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":180.00,\"description\":\"Miscellaneous items\"}"))
                .andExpect(status().isCreated());

        assertThat(expenseRepository.findAllByUserOrderByDateDesc(free)).hasSize(1);
    }

    // ------------------------------------------------- the flag is a fact, not an inference

    /**
     * Renaming a starter does not promote it to user-created. The distinction lives on the row, so
     * the count cannot move because a name changed — which is precisely why TEN-327 added a column
     * instead of matching against the seed list at read time.
     */
    @Test
    void renamingASeededCategory_doesNotMoveTheCount() throws Exception {
        var starter = categoryRepository.findByUserAndNameIgnoreCase(free, "Vacation").orElseThrow();
        assertThat(starter.isSystemProvided()).isTrue();

        mockMvc.perform(put("/categories/" + starter.getId())
                        .header("Authorization", freeAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Holidays\"}"))
                .andExpect(status().isOk());

        assertThat(categoryRepository.findByUserAndNameIgnoreCase(free, "Holidays").orElseThrow()
                .isSystemProvided()).isTrue();
        assertThat(categoryRepository.countByUserAndSystemProvidedFalse(free)).isEqualTo(FREE_CATEGORY_CAP);
        assertThat(categoryRepository.countByUser(free)).isEqualTo(SEEDED_CATEGORY_COUNT + FREE_CATEGORY_CAP);
    }

    /**
     * And the mirror: re-creating a starter's name after deleting it is a user-created category
     * and costs one of the five. The name never decides — otherwise a user could delete a starter,
     * re-create it, and hold six of their own.
     */
    @Test
    void reCreatingADeletedStarter_costsOneOfTheFive() throws Exception {
        var starter = categoryRepository.findByUserAndNameIgnoreCase(free, "Vacation").orElseThrow();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/categories/" + starter.getId()).header("Authorization", freeAuth))
                .andExpect(status().isNoContent());

        // Still at the cap on user-created rows — deleting a starter frees no headroom, because a
        // starter never occupied any.
        assertThat(categoryRepository.countByUserAndSystemProvidedFalse(free)).isEqualTo(FREE_CATEGORY_CAP);
        createCategory("Vacation")
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.feature").value("CUSTOM_CATEGORIES"));
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions createCategory(String name) throws Exception {
        return mockMvc.perform(post("/categories")
                .header("Authorization", freeAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\"}"));
    }

    private static MockMultipartFile csv(String body) {
        return new MockMultipartFile("file", "expenses.csv", "text/csv",
                body.getBytes(StandardCharsets.UTF_8));
    }

    /** No category of that name, and the user's allowance is exactly where it was. */
    private void assertNothingWasCreated(String name) {
        assertThat(categoryRepository.findByUserAndNameIgnoreCase(free, name)).isEmpty();
        assertThat(categoryRepository.countByUserAndSystemProvidedFalse(free)).isEqualTo(FREE_CATEGORY_CAP);
    }
}
