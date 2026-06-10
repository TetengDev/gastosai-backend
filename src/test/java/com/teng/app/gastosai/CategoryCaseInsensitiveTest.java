package com.teng.app.gastosai;

import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.service.CategoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class CategoryCaseInsensitiveTest {

    @Autowired
    CategoryService categoryService;

    @Test
    void getOrCreateByName_returnsSameCategory_forDifferentCasings() {
        Category first = categoryService.getOrCreateByName("TestFood");
        Category lower = categoryService.getOrCreateByName("testfood");
        Category upper = categoryService.getOrCreateByName("TESTFOOD");

        assertThat(lower.getId()).isEqualTo(first.getId());
        assertThat(upper.getId()).isEqualTo(first.getId());
    }

    @Test
    void create_rejectsCategory_whenSameNameExistsDifferentCase() {
        categoryService.getOrCreateByName("TestDuplicate");

        assertThatThrownBy(() ->
                categoryService.create(new com.teng.app.gastosai.dto.CategoryRequest("testduplicate", null))
        ).isInstanceOf(IllegalArgumentException.class);
    }
}
