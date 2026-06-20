package com.teng.app.gastosai;

import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.CategoryRepository;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.service.CategorySeedService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class CategoryIsolationIntegrationTest {

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired UserRepository userRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired ExpenseRepository expenseRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtUtil jwtUtil;
    @Autowired CategorySeedService categorySeedService;

    MockMvc mockMvc;
    User userA;
    User userB;
    String authA;
    String authB;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        expenseRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();

        userA = userRepository.save(User.builder()
                .name("Alice")
                .email("alice@test.com")
                .password(passwordEncoder.encode("pw"))
                .build());

        userB = userRepository.save(User.builder()
                .name("Bob")
                .email("bob@test.com")
                .password(passwordEncoder.encode("pw"))
                .build());

        categorySeedService.seedPredefinedForUser(userA);
        categorySeedService.seedPredefinedForUser(userB);

        authA = "Bearer " + jwtUtil.generate(userA.getEmail());
        authB = "Bearer " + jwtUtil.generate(userB.getEmail());
    }

    @Test
    void getCategories_returnsOnlyOwnCategories() throws Exception {
        mockMvc.perform(get("/categories").header("Authorization", authA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(
                        categoryRepository.findAllByUser(userA).size()));

        mockMvc.perform(get("/categories").header("Authorization", authB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(
                        categoryRepository.findAllByUser(userB).size()));
    }

    @Test
    void deleteOtherUserCategory_returns404() throws Exception {
        Category userBCategory = categoryRepository.findAllByUser(userB).get(0);

        mockMvc.perform(delete("/categories/" + userBCategory.getId())
                        .header("Authorization", authA))
                .andExpect(status().isNotFound());
    }

    @Test
    void getOtherUserCategoryById_returns404() throws Exception {
        Category userBCategory = categoryRepository.findAllByUser(userB).get(0);

        mockMvc.perform(get("/categories/" + userBCategory.getId())
                        .header("Authorization", authA))
                .andExpect(status().isNotFound());
    }

    @Test
    void userA_hasOwnCategories_userB_hasOwnIndependentCategories() throws Exception {
        long countA = categoryRepository.findAllByUser(userA).size();
        long countB = categoryRepository.findAllByUser(userB).size();

        mockMvc.perform(get("/categories").header("Authorization", authA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(countA));

        mockMvc.perform(get("/categories").header("Authorization", authB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(countB));
    }
}
