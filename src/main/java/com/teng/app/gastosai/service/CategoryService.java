package com.teng.app.gastosai.service;

import com.teng.app.gastosai.dto.CategoryRequest;
import com.teng.app.gastosai.dto.CategoryResponse;
import com.teng.app.gastosai.entity.Category;
import com.teng.app.gastosai.entity.Expense;
import com.teng.app.gastosai.exception.ResourceNotFoundException;
import com.teng.app.gastosai.repository.CategoryRepository;
import com.teng.app.gastosai.repository.ExpenseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CategoryService {

	private static final String DEFAULT_CATEGORY = "Uncategorized";

	private final CategoryRepository categoryRepository;
	private final ExpenseRepository expenseRepository;

	@Transactional
	public CategoryResponse create(CategoryRequest request) {
		String trimmed = request.name().trim();
		if (categoryRepository.existsByName(trimmed)) {
			throw new IllegalArgumentException("Category already exists: " + request.name());
		}
		Category saved = categoryRepository.save(Category.builder()
				.name(trimmed)
				.icon(request.icon() != null ? request.icon().trim() : null)
				.build());
		return toResponse(saved);
	}

	@Transactional(readOnly = true)
	public List<CategoryResponse> findAll() {
		return categoryRepository.findAll().stream()
				.map(this::toResponse)
				.toList();
	}

	@Transactional(readOnly = true)
	public CategoryResponse findById(Long id) {
		return categoryRepository.findById(id)
				.map(this::toResponse)
				.orElseThrow(() -> new ResourceNotFoundException("Category not found: " + id));
	}

	@Transactional
	public CategoryResponse update(Long id, CategoryRequest request) {
		Category existing = categoryRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Category not found: " + id));

		String trimmed = request.name().trim();
		Category conflicting = categoryRepository.findByName(trimmed).orElse(null);
		if (conflicting != null && !conflicting.getId().equals(existing.getId())) {
			throw new IllegalArgumentException("Category already exists: " + request.name());
		}

		existing.setName(trimmed);
		existing.setIcon(request.icon() != null ? request.icon().trim() : null);
		Category saved = categoryRepository.save(existing);
		return toResponse(saved);
	}

	@Transactional
	public void delete(Long id) {
		Category toDelete = categoryRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Category not found: " + id));

		List<Expense> affected = expenseRepository.findByCategory_Id(toDelete.getId());
		if (!affected.isEmpty()) {
			Category fallback = getOrCreateByName(DEFAULT_CATEGORY);
			affected.forEach(e -> e.setCategory(fallback));
			expenseRepository.saveAll(affected);
		}

		categoryRepository.deleteById(id);
	}

	@Transactional
	public void deleteAllExceptDefault() {
		List<Category> toDelete = categoryRepository.findAll().stream()
				.filter(c -> !c.getName().equals(DEFAULT_CATEGORY))
				.toList();
		if (toDelete.isEmpty()) return;

		Category fallback = getOrCreateByName(DEFAULT_CATEGORY);
		for (Category cat : toDelete) {
			List<Expense> affected = expenseRepository.findByCategory_Id(cat.getId());
			if (!affected.isEmpty()) {
				affected.forEach(e -> e.setCategory(fallback));
				expenseRepository.saveAll(affected);
			}
		}
		categoryRepository.deleteAll(toDelete);
	}

	@Transactional
	public Category getOrCreateByName(String categoryName) {
		String trimmed = categoryName.trim();
		return categoryRepository.findByName(trimmed)
				.orElseGet(() -> categoryRepository.save(Category.builder().name(trimmed).build()));
	}

	private CategoryResponse toResponse(Category c) {
		return new CategoryResponse(c.getId(), c.getName(), c.getIcon());
	}
}
