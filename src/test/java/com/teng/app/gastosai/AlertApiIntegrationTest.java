package com.teng.app.gastosai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.AlertRepository;
import com.teng.app.gastosai.repository.BudgetRepository;
import com.teng.app.gastosai.repository.CategoryRepository;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class AlertApiIntegrationTest {

    @Autowired
    WebApplicationContext webApplicationContext;

    @Autowired
    UserRepository userRepository;

    @Autowired
    AlertRepository alertRepository;

    @Autowired
    BudgetRepository budgetRepository;

    @Autowired
    CategoryRepository categoryRepository;

    @Autowired
    ExpenseRepository expenseRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JwtUtil jwtUtil;

    ObjectMapper objectMapper = new ObjectMapper();

    MockMvc mockMvc;
    String authHeader;
    String currentMonth;
    String prevMonth;
    User testUser;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        alertRepository.deleteAll();
        budgetRepository.deleteAll();
        expenseRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();

        testUser = userRepository.save(User.builder()
                .name("Alert User")
                .email("alert@test.com")
                .password(passwordEncoder.encode("password"))
                .build());

        authHeader = "Bearer " + jwtUtil.generate(testUser.getEmail());

        YearMonth now = YearMonth.now();
        currentMonth = now.toString();
        prevMonth = now.minusMonths(1).toString();
    }

    @Test
    void budgetWarning_whenSpendingAt85Pct() throws Exception {
        long catId = createCategory("Groceries");
        createBudget(catId, currentMonth, 1000.00);
        postExpense(850.00, "Groceries", currentDate());

        mockMvc.perform(get("/alerts")
                        .header("Authorization", authHeader)
                        .param("month", currentMonth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("BUDGET_WARNING"))
                .andExpect(jsonPath("$[0].severity").value("WARNING"))
                .andExpect(jsonPath("$[0].categoryName").value("Groceries"));
    }

    @Test
    void budgetExceeded_whenSpendingAt100Pct() throws Exception {
        long catId = createCategory("Utilities");
        createBudget(catId, currentMonth, 500.00);
        postExpense(500.00, "Utilities", currentDate());

        mockMvc.perform(get("/alerts")
                        .header("Authorization", authHeader)
                        .param("month", currentMonth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("BUDGET_EXCEEDED"))
                .andExpect(jsonPath("$[0].severity").value("CRITICAL"));
    }

    @Test
    void spendingSpike_whenCurrentIsDoublePrevious() throws Exception {
        postExpense(1000.00, "Food", prevDate());
        postExpense(2500.00, "Food", currentDate());

        mockMvc.perform(get("/alerts")
                        .header("Authorization", authHeader)
                        .param("month", currentMonth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("SPENDING_SPIKE"))
                .andExpect(jsonPath("$[0].categoryName").value(""));
    }

    @Test
    void generateAlerts_isIdempotent() throws Exception {
        long catId = createCategory("Transport");
        createBudget(catId, currentMonth, 1000.00);
        postExpense(850.00, "Transport", currentDate());

        mockMvc.perform(get("/alerts").header("Authorization", authHeader).param("month", currentMonth))
                .andExpect(status().isOk());
        mockMvc.perform(get("/alerts").header("Authorization", authHeader).param("month", currentMonth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void markRead_setsReadTrue() throws Exception {
        long catId = createCategory("Entertainment");
        createBudget(catId, currentMonth, 1000.00);
        postExpense(850.00, "Entertainment", currentDate());

        MvcResult result = mockMvc.perform(get("/alerts")
                        .header("Authorization", authHeader)
                        .param("month", currentMonth))
                .andExpect(status().isOk())
                .andReturn();

        long alertId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get(0).get("id").asLong();

        mockMvc.perform(patch("/alerts/" + alertId + "/read")
                        .header("Authorization", authHeader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));
    }

    @Test
    void dismiss_removesFromList() throws Exception {
        long catId = createCategory("Dining");
        createBudget(catId, currentMonth, 1000.00);
        postExpense(850.00, "Dining", currentDate());

        MvcResult result = mockMvc.perform(get("/alerts")
                        .header("Authorization", authHeader)
                        .param("month", currentMonth))
                .andExpect(status().isOk())
                .andReturn();

        long alertId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get(0).get("id").asLong();

        mockMvc.perform(patch("/alerts/" + alertId + "/dismiss")
                        .header("Authorization", authHeader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dismissed").value(true));

        mockMvc.perform(get("/alerts")
                        .header("Authorization", authHeader)
                        .param("month", currentMonth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private long createCategory(String name) {
        return categoryRepository.findByUserAndNameIgnoreCase(testUser, name)
                .orElseGet(() -> categoryRepository.save(Category.builder().name(name).user(testUser).build()))
                .getId();
    }

    private void createBudget(long categoryId, String month, double amountLimit) throws Exception {
        mockMvc.perform(post("/budgets")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\": %d, \"month\": \"%s\", \"amountLimit\": %.2f}"
                                .formatted(categoryId, month, amountLimit)))
                .andExpect(status().isCreated());
    }

    private void postExpense(double amount, String category, String date) throws Exception {
        mockMvc.perform(post("/expenses")
                        .header("Authorization", authHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": %.2f, \"category\": \"%s\", \"date\": \"%s\", \"description\": \"test\"}"
                                .formatted(amount, category, date)))
                .andExpect(status().isCreated());
    }

    // Mid-month, midday on purpose: the YEAR()/MONTH() aggregation runs on the stored timestamp.
    // A day-boundary value could shift into an adjacent month if a JVM-zone <-> DB-zone conversion
    // is ever (re)introduced, so day 15 at noon keeps the month stable under any timezone setup.
    private String currentDate() {
        return LocalDateTime.now().withDayOfMonth(15).withHour(12).withMinute(0).withSecond(0).withNano(0)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private String prevDate() {
        return LocalDateTime.now().minusMonths(1).withDayOfMonth(15).withHour(12).withMinute(0).withSecond(0).withNano(0)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
