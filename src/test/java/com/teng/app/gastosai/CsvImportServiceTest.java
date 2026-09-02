package com.teng.app.gastosai;

import com.teng.app.gastosai.dto.ImportResult;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.Expense;
import com.teng.app.gastosai.entity.FeatureKey;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.exception.FeatureLockedException;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.service.CategoryService;
import com.teng.app.gastosai.service.CsvImportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CsvImportServiceTest {

    @Mock ExpenseRepository expenseRepository;
    @Mock CategoryService categoryService;
    @Mock PlatformTransactionManager transactionManager;

    @InjectMocks CsvImportService csvImportService;

    private User user() {
        return User.builder().id(1L).email("u@test.com").name("Test").password("pw").build();
    }

    private Category category(String name) {
        return Category.builder().id(1L).name(name).build();
    }

    private MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "expenses.csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }

    // --- happy path ---

    @Test
    void import_validRows_importsAll() throws IOException {
        String content = "date,amount,category,description\n"
                + "2026-06-01,500.00,Food,Lunch\n"
                + "2026-06-02,200.50,Transport,Grab\n";

        when(categoryService.getOrCreateByName(anyString(), any())).thenAnswer(inv ->
                category(inv.getArgument(0)));

        ImportResult result = csvImportService.importCsv(csv(content), user());

        assertThat(result.imported()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(0);
        assertThat(result.errors()).isEmpty();
        ArgumentCaptor<Expense> cap = ArgumentCaptor.forClass(Expense.class);
        verify(expenseRepository, times(2)).save(cap.capture());
        // Regression guard: amountInBaseCurrency must be set (DB column is NOT NULL in prod;
        // omitting it broke every CSV import even though the mocked/H2 path didn't catch it).
        assertThat(cap.getAllValues()).allSatisfy(e -> {
            assertThat(e.getAmountInBaseCurrency()).isNotNull();
            assertThat(e.getAmountInBaseCurrency()).isEqualByComparingTo(e.getAmount());
            assertThat(e.getCurrency()).isEqualTo("PHP");
        });
    }

    // --- missing amount column → skip ---

    @Test
    void import_missingAmount_skipsRow() throws IOException {
        String content = "date,category,description\n"
                + "2026-06-01,Food,Lunch\n";

        ImportResult result = csvImportService.importCsv(csv(content), user());

        assertThat(result.imported()).isEqualTo(0);
        assertThat(result.skipped()).isEqualTo(1);
        verify(expenseRepository, never()).save(any());
    }

    // --- zero or negative amount → skip ---

    @Test
    void import_zeroAmount_skipsRow() throws IOException {
        String content = "date,amount,category,description\n"
                + "2026-06-01,0.00,Food,Free\n";

        ImportResult result = csvImportService.importCsv(csv(content), user());

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.imported()).isEqualTo(0);
    }

    @Test
    void import_negativeAmount_skipsRow() throws IOException {
        String content = "date,amount,category\n"
                + "2026-06-01,-100,Food\n";

        ImportResult result = csvImportService.importCsv(csv(content), user());

        assertThat(result.skipped()).isEqualTo(1);
    }

    // --- amount with currency symbol stripped ---

    @Test
    void import_amountWithPesoSign_parsed() throws IOException {
        String content = "date,amount,category\n"
                + "2026-06-01,₱1500.00,Food\n";

        when(categoryService.getOrCreateByName(anyString(), any())).thenReturn(category("Food"));

        ImportResult result = csvImportService.importCsv(csv(content), user());

        assertThat(result.imported()).isEqualTo(1);
    }

    // --- no category column → defaults to Uncategorized ---

    @Test
    void import_noCategory_defaultsToUncategorized() throws IOException {
        String content = "date,amount,description\n"
                + "2026-06-01,100.00,Misc\n";

        when(categoryService.getOrCreateByName(eq("Uncategorized"), any())).thenReturn(category("Uncategorized"));

        ImportResult result = csvImportService.importCsv(csv(content), user());

        assertThat(result.imported()).isEqualTo(1);
        verify(categoryService).getOrCreateByName(eq("Uncategorized"), any());
    }

    // --- invalid amount format → error row ---

    @Test
    void import_invalidAmountFormat_recordsError() throws IOException {
        String content = "date,amount,category\n"
                + "2026-06-01,notanumber,Food\n";

        ImportResult result = csvImportService.importCsv(csv(content), user());

        assertThat(result.imported()).isEqualTo(0);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0)).contains("Row 2");
    }

    // --- mixed valid + invalid rows ---

    @Test
    void import_mixedRows_countsSeparately() throws IOException {
        String content = "date,amount,category\n"
                + "2026-06-01,500.00,Food\n"    // imported
                + "2026-06-02,0,Food\n"          // skipped (zero)
                + "2026-06-03,bad,Food\n"         // error
                + "2026-06-04,200.00,Transport\n"; // imported

        when(categoryService.getOrCreateByName(anyString(), any())).thenAnswer(inv ->
                category(inv.getArgument(0)));

        ImportResult result = csvImportService.importCsv(csv(content), user());

        assertThat(result.imported()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.errors()).hasSize(1);
    }

    // --- date formats ---

    @Test
    void import_dateFormatMmDdYyyy_parsed() throws IOException {
        String content = "date,amount,category\n"
                + "06/14/2026,300.00,Food\n";

        when(categoryService.getOrCreateByName(anyString(), any())).thenReturn(category("Food"));

        ImportResult result = csvImportService.importCsv(csv(content), user());

        assertThat(result.imported()).isEqualTo(1);
    }

    @Test
    void import_noDate_fallsBackToNow() throws IOException {
        String content = "amount,category\n"
                + "100.00,Food\n";

        when(categoryService.getOrCreateByName(anyString(), any())).thenReturn(category("Food"));

        ImportResult result = csvImportService.importCsv(csv(content), user());

        assertThat(result.imported()).isEqualTo(1);
    }

    // --- empty file ---

    @Test
    void import_emptyFile_returnsZero() throws IOException {
        String content = "date,amount,category\n";

        ImportResult result = csvImportService.importCsv(csv(content), user());

        assertThat(result.imported()).isEqualTo(0);
        assertThat(result.skipped()).isEqualTo(0);
        assertThat(result.errors()).isEmpty();
    }

    // --- strict mode ---

    @Test
    void strict_allValid_importsAllInOneTransaction() throws IOException {
        String content = "date,amount,category\n"
                + "2026-06-01,500.00,Food\n"
                + "2026-06-02,200.00,Transport\n";

        when(categoryService.getOrCreateByName(anyString(), any())).thenAnswer(inv -> category(inv.getArgument(0)));

        ImportResult result = csvImportService.importCsv(csv(content), user(), true);

        assertThat(result.imported()).isEqualTo(2);
        assertThat(result.errors()).isEmpty();
        verify(expenseRepository, times(2)).save(any());
    }

    @Test
    void strict_anyBadRow_rejectsWholeFile_persistsNothing() throws IOException {
        String content = "date,amount,category\n"
                + "2026-06-01,500.00,Food\n"      // valid
                + "2026-06-02,0,Food\n"            // would-skip -> error in strict
                + "2026-06-03,bad,Food\n";          // error

        ImportResult result = csvImportService.importCsv(csv(content), user(), true);

        assertThat(result.imported()).isEqualTo(0);
        assertThat(result.skipped()).isEqualTo(0);
        assertThat(result.errors()).hasSize(2);
        verify(expenseRepository, never()).save(any());
    }

    // --- abuse limits ---

    @Test
    void import_overRowCap_rejectsWholeFile_persistsNothing() throws IOException {
        ReflectionTestUtils.setField(csvImportService, "maxRows", 2);
        String content = "date,amount,category\n"
                + "2026-06-01,10,Food\n"
                + "2026-06-02,20,Food\n"
                + "2026-06-03,30,Food\n"; // 3 data rows > cap of 2

        ImportResult result = csvImportService.importCsv(csv(content), user());

        assertThat(result.imported()).isZero();
        assertThat(result.skipped()).isZero();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0)).contains("exceeds");
        verify(expenseRepository, never()).save(any());
    }

    @Test
    void import_overlongFields_truncatedToCaps() throws IOException {
        String longCategory = "c".repeat(200);      // cap 100
        String longDescription = "d".repeat(600);    // cap 500
        String content = "date,amount,category,description\n"
                + "2026-06-01,10," + longCategory + "," + longDescription + "\n";

        when(categoryService.getOrCreateByName(anyString(), any())).thenAnswer(inv ->
                category(inv.getArgument(0)));

        ImportResult result = csvImportService.importCsv(csv(content), user());

        assertThat(result.imported()).isEqualTo(1);
        ArgumentCaptor<String> catCap = ArgumentCaptor.forClass(String.class);
        verify(categoryService).getOrCreateByName(catCap.capture(), any());
        assertThat(catCap.getValue()).hasSize(100);
        ArgumentCaptor<Expense> expCap = ArgumentCaptor.forClass(Expense.class);
        verify(expenseRepository).save(expCap.capture());
        assertThat(expCap.getValue().getDescription()).hasSize(500);
    }

    // --- category cap (TEN-329) ---

    @Test
    void import_categoryCapHitMidFile_finishesTheFile_andNamesTheLimit() throws IOException {
        String content = "date,amount,category\n"
                + "2026-06-01,100.00,Food\n"        // existing category -> imports
                + "2026-06-02,200.00,Vacation\n"    // would be new -> refused
                + "2026-06-03,300.00,Food\n"        // existing category -> still imports
                + "2026-06-04,400.00,Travel\n";     // would be new -> refused

        when(categoryService.getOrCreateByName(anyString(), any())).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            if ("Food".equals(name)) return category(name);
            throw new FeatureLockedException(FeatureKey.CUSTOM_CATEGORIES,
                    "Your plan is limited to 5 categories. Upgrade to add more.");
        });

        ImportResult result = csvImportService.importCsv(csv(content), user());

        // The refusal used to rethrow, so rows 3 and 4 never ran and the caller got a 402 with no
        // count — while row 2 stayed committed.
        assertThat(result.imported()).isEqualTo(2);
        assertThat(result.limitReached()).isEqualTo("CUSTOM_CATEGORIES");
        assertThat(result.errors()).hasSize(2);
        assertThat(result.errors().get(0)).contains("Row 3").contains("Vacation");
        assertThat(result.errors().get(1)).contains("Row 5").contains("Travel");
        verify(expenseRepository, times(2)).save(any());
    }

    @Test
    void import_noCapHit_leavesLimitReachedNull() throws IOException {
        String content = "date,amount,category\n"
                + "2026-06-01,100.00,Food\n";

        when(categoryService.getOrCreateByName(anyString(), any())).thenReturn(category("Food"));

        ImportResult result = csvImportService.importCsv(csv(content), user());

        assertThat(result.limitReached()).isNull();
    }

    @Test
    void strict_categoryCapHit_stillRefusesTheWholeFile() throws IOException {
        String content = "date,amount,category\n"
                + "2026-06-01,100.00,Food\n"
                + "2026-06-02,200.00,Vacation\n";

        // Strict runs inside a TransactionTemplate; the refusal must escape so the whole file rolls
        // back and the request answers 402 naming CUSTOM_CATEGORIES.
        when(categoryService.getOrCreateByName(anyString(), any())).thenAnswer(inv -> {
            if ("Food".equals(inv.getArgument(0))) return category("Food");
            throw new FeatureLockedException(FeatureKey.CUSTOM_CATEGORIES, "capped");
        });

        assertThatThrownBy(() -> csvImportService.importCsv(csv(content), user(), true))
                .isInstanceOf(FeatureLockedException.class)
                .extracting(e -> ((FeatureLockedException) e).getFeature())
                .isEqualTo(FeatureKey.CUSTOM_CATEGORIES);
        verify(transactionManager).rollback(any());
    }

    @Test
    void buildTemplate_returnsCsvWithHeader() throws IOException {
        String csv = new String(csvImportService.buildTemplate(), StandardCharsets.UTF_8);
        assertThat(csv).contains("date,amount,category,description");
        assertThat(csv).contains("Food");
    }
}
