package com.teng.app.gastosai;

import com.teng.app.gastosai.dto.CategoryRequest;
import com.teng.app.gastosai.dto.CategoryResponse;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.Expense;
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

    @Test
    void create_happyPath_savesAndReturnsResponse() {
        when(categoryRepository.existsByNameIgnoreCase("Food")).thenReturn(false);
        Category saved = Category.builder().id(1L).name("Food").icon("utensils").build();
        when(categoryRepository.save(any(Category.class))).thenReturn(saved);

        CategoryResponse response = categoryService.create(new CategoryRequest("Food", "utensils"));

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.name()).isEqualTo("Food");
        assertThat(response.icon()).isEqualTo("utensils");
    }

    @Test
    void create_throwsIllegalArgument_whenNameAlreadyExists() {
        when(categoryRepository.existsByNameIgnoreCase("Food")).thenReturn(true);

        assertThatThrownBy(() -> categoryService.create(new CategoryRequest("Food", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Food");
    }

    @Test
    void getOrCreateByName_returnsExisting_whenFound() {
        Category existing = Category.builder().id(5L).name("Meal Plan").build();
        when(categoryRepository.findByNameIgnoreCase("Meal Plan")).thenReturn(Optional.of(existing));

        Category result = categoryService.getOrCreateByName("Meal Plan");

        assertThat(result.getId()).isEqualTo(5L);
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void getOrCreateByName_savesNew_whenNotFound() {
        when(categoryRepository.findByNameIgnoreCase("NewCat")).thenReturn(Optional.empty());
        Category saved = Category.builder().id(10L).name("NewCat").build();
        when(categoryRepository.save(any(Category.class))).thenReturn(saved);

        Category result = categoryService.getOrCreateByName("NewCat");

        assertThat(result.getId()).isEqualTo(10L);
        verify(categoryRepository).save(argThat(c -> "NewCat".equals(c.getName())));
    }

    @Test
    void delete_reassignExpensesToUncategorized_whenCategoryHasExpenses() {
        Category toDelete = Category.builder().id(2L).name("Food").build();
        when(categoryRepository.findById(2L)).thenReturn(Optional.of(toDelete));

        Expense expense = Expense.builder()
                .id(1L)
                .amount(new BigDecimal("100.00"))
                .category(toDelete)
                .description("Lunch")
                .build();
        when(expenseRepository.findByCategory_Id(2L)).thenReturn(List.of(expense));

        Category uncategorized = Category.builder().id(99L).name("Uncategorized").build();
        when(categoryRepository.findByNameIgnoreCase("Uncategorized")).thenReturn(Optional.of(uncategorized));

        categoryService.delete(2L);

        assertThat(expense.getCategory().getId()).isEqualTo(99L);
        verify(expenseRepository).saveAll(List.of(expense));
        verify(categoryRepository).deleteById(2L);
    }

    @Test
    void delete_deletesDirectly_whenCategoryHasNoExpenses() {
        Category toDelete = Category.builder().id(3L).name("Snacks").build();
        when(categoryRepository.findById(3L)).thenReturn(Optional.of(toDelete));
        when(expenseRepository.findByCategory_Id(3L)).thenReturn(List.of());

        categoryService.delete(3L);

        verify(expenseRepository, never()).saveAll(any());
        verify(categoryRepository).deleteById(3L);
    }

    @Test
    void delete_defaultCategory_throws() {
        Category def = Category.builder().id(4L).name("Transportation").build();
        when(categoryRepository.findById(4L)).thenReturn(Optional.of(def));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> categoryService.delete(4L));
        verify(categoryRepository, never()).deleteById(any());
    }
}
