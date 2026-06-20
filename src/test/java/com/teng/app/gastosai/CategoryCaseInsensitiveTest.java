package com.teng.app.gastosai;

import com.teng.app.gastosai.dto.CategoryRequest;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.Role;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.service.CategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class CategoryCaseInsensitiveTest {

    @Autowired
    CategoryService categoryService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    User testUser;

    @BeforeEach
    void setUp() {
        testUser = userRepository.save(User.builder()
                .name("Case Test User")
                .email("casetest@test.com")
                .password(passwordEncoder.encode("pw"))
                .role(Role.USER)
                .build());
    }

    @Test
    void getOrCreateByName_returnsSameCategory_forDifferentCasings() {
        Category first = categoryService.getOrCreateByName("TestFood", testUser);
        Category lower = categoryService.getOrCreateByName("testfood", testUser);
        Category upper = categoryService.getOrCreateByName("TESTFOOD", testUser);

        assertThat(lower.getId()).isEqualTo(first.getId());
        assertThat(upper.getId()).isEqualTo(first.getId());
    }

    @Test
    void create_rejectsCategory_whenSameNameExistsDifferentCase() {
        categoryService.getOrCreateByName("TestDuplicate", testUser);

        assertThatThrownBy(() ->
                categoryService.create(new CategoryRequest("testduplicate", null), testUser)
        ).isInstanceOf(IllegalArgumentException.class);
    }
}
