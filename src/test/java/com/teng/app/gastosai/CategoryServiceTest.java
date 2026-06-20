package com.teng.app.gastosai;

import com.teng.app.gastosai.dto.CategoryRequest;
import com.teng.app.gastosai.dto.CategoryResponse;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.Expense;
import com.teng.app.gastosai.entity.Role;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.CategoryRepository;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.service.CategoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    CategoryRepository categoryRepository;

    @Mock
    ExpenseRepository expenseRepository;

    @InjectMocks
    CategoryService categoryService;

    private User testUser() {
        return User.builder().id(1L).email("u@test.com").name("Test").password("pw").role(Role.USER).build();
    }

    @Test
    void create_happyPath_savesAndReturnsResponse() {
        User user = testUser();
        when(categoryRepository.existsByUserAndNameIgnoreCase(user, "Food")).thenReturn(false);
        Category saved = Category.builder().id(1L).name("Food").icon("utensils").user(user).build();
        when(categoryRepository.save(any(Category.class))).thenReturn(saved);

        CategoryResponse response = categoryService.create(new CategoryRequest("Food", "utensils"), user);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.name()).isEqualTo("Food");
        assertThat(response.icon()).isEqualTo("utensils");
    }

    @Test
    void create_throwsIllegalArgument_whenNameAlreadyExists() {
        User user = testUser();
        when(categoryRepository.existsByUserAndNameIgnoreCase(user, "Food")).thenReturn(true);

        assertThatThrownBy(() -> categoryService.create(new CategoryRequest("Food", null), user))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Food");
    }

    @Test
    void getOrCreateByName_returnsExisting_whenFound() {
        User user = testUser();
        Category existing = Category.builder().id(5L).name("Meal Plan").user(user).build();
        when(categoryRepository.findByUserAndNameIgnoreCase(user, "Meal Plan")).thenReturn(Optional.of(existing));

        Category result = categoryService.getOrCreateByName("Meal Plan", user);

        assertThat(result.getId()).isEqualTo(5L);
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void getOrCreateByName_savesNew_whenNotFound() {
        User user = testUser();
        when(categoryRepository.findByUserAndNameIgnoreCase(user, "NewCat")).thenReturn(Optional.empty());
        Category saved = Category.builder().id(10L).name("NewCat").user(user).build();
        when(categoryRepository.save(any(Category.class))).thenReturn(saved);

        Category result = categoryService.getOrCreateByName("NewCat", user);

        assertThat(result.getId()).isEqualTo(10L);
        verify(categoryRepository).save(argThat(c -> "NewCat".equals(c.getName())));
    }

    @Test
    void delete_reassignExpensesToUncategorized_whenCategoryHasExpenses() {
        User user = testUser();
        Category toDelete = Category.builder().id(2L).name("Food").user(user).build();
        when(categoryRepository.findByIdAndUser(2L, user)).thenReturn(Optional.of(toDelete));

        Expense expense = Expense.builder()
                .id(1L)
                .amount(new BigDecimal("100.00"))
                .category(toDelete)
                .description("Lunch")
                .build();
        when(expenseRepository.findByCategory_Id(2L)).thenReturn(List.of(expense));

        Category uncategorized = Category.builder().id(99L).name("Uncategorized").user(user).build();
        when(categoryRepository.findByUserAndNameIgnoreCase(user, "Uncategorized")).thenReturn(Optional.of(uncategorized));

        categoryService.delete(2L, user);

        assertThat(expense.getCategory().getId()).isEqualTo(99L);
        verify(expenseRepository).saveAll(List.of(expense));
        verify(categoryRepository).deleteById(2L);
    }

    @Test
    void delete_deletesDirectly_whenCategoryHasNoExpenses() {
        User user = testUser();
        Category toDelete = Category.builder().id(3L).name("Snacks").user(user).build();
        when(categoryRepository.findByIdAndUser(3L, user)).thenReturn(Optional.of(toDelete));
        when(expenseRepository.findByCategory_Id(3L)).thenReturn(List.of());

        categoryService.delete(3L, user);

        verify(expenseRepository, never()).saveAll(any());
        verify(categoryRepository).deleteById(3L);
    }

    @Test
    void delete_defaultCategory_throws() {
        User user = testUser();
        Category def = Category.builder().id(4L).name("Uncategorized").user(user).build();
        when(categoryRepository.findByIdAndUser(4L, user)).thenReturn(Optional.of(def));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> categoryService.delete(4L, user));
        verify(categoryRepository, never()).deleteById(any());
    }
}
