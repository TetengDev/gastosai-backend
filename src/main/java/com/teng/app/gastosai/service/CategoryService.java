package com.teng.app.gastosai.service;

import com.teng.app.gastosai.dto.CategoryRequest;
import com.teng.app.gastosai.dto.CategoryResponse;
import com.teng.app.gastosai.entity.Category;
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

	private final CategoryRepository categoryRepository;
	private final ExpenseRepository expenseRepository;

	@Transactional
	public CategoryResponse create(CategoryRequest request) {
		String trimmed = request.name().trim();
		if (categoryRepository.existsByName(trimmed)) {
			throw new IllegalArgumentException("Category already exists: " + request.name());
		}
		Category saved = categoryRepository.save(Category.builder().name(trimmed).build());
		return new CategoryResponse(saved.getId(), saved.getName());
	}

	@Transactional(readOnly = true)
	public List<CategoryResponse> findAll() {
		return categoryRepository.findAll().stream()
				.map(c -> new CategoryResponse(c.getId(), c.getName()))
				.toList();
	}

	@Transactional(readOnly = true)
	public CategoryResponse findById(Long id) {
		return categoryRepository.findById(id)
				.map(c -> new CategoryResponse(c.getId(), c.getName()))
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
		Category saved = categoryRepository.save(existing);
		return new CategoryResponse(saved.getId(), saved.getName());
	}

	@Transactional
	public void delete(Long id) {
		if (!categoryRepository.existsById(id)) {
			throw new ResourceNotFoundException("Category not found: " + id);
		}
		long used = expenseRepository.countByCategory_Id(id);
		if (used > 0) {
			throw new IllegalStateException("Cannot delete category; it is used by " + used + " expense(s)");
		}
		categoryRepository.deleteById(id);
	}

	@Transactional
	public Category getOrCreateByName(String categoryName) {
		String trimmed = categoryName.trim();
		return categoryRepository.findByName(trimmed)
				.orElseGet(() -> categoryRepository.save(Category.builder().name(trimmed).build()));
	}
}
