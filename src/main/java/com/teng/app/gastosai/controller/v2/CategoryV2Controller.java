package com.teng.app.gastosai.controller.v2;

import com.teng.app.gastosai.controller.CategoryController;
import com.teng.app.gastosai.dto.CategoryRequest;
import com.teng.app.gastosai.dto.CategoryResponse;
import com.teng.app.gastosai.entity.User;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** {@link CategoryController} on the v2 path; a category carries no money. */
@RestController
@RequestMapping("/api/v2/categories")
@RequiredArgsConstructor
public class CategoryV2Controller {

	private final CategoryController delegate;

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(operationId = "v2CreateCategory")
	public CategoryResponse create(@Valid @RequestBody CategoryRequest request,
			@AuthenticationPrincipal User user) {
		return delegate.create(request, user);
	}

	@GetMapping
	@Operation(operationId = "v2ListCategories")
	public List<CategoryResponse> list(@AuthenticationPrincipal User user) {
		return delegate.list(user);
	}

	@GetMapping("/{id}")
	@Operation(operationId = "v2GetCategory")
	public CategoryResponse get(@PathVariable Long id, @AuthenticationPrincipal User user) {
		return delegate.get(id, user);
	}

	@PutMapping("/{id}")
	@Operation(operationId = "v2UpdateCategory")
	public CategoryResponse update(@PathVariable Long id, @Valid @RequestBody CategoryRequest request,
			@AuthenticationPrincipal User user) {
		return delegate.update(id, request, user);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "v2DeleteCategory")
	public void delete(@PathVariable Long id, @AuthenticationPrincipal User user) {
		delegate.delete(id, user);
	}

	@DeleteMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "v2DeleteAllCategories")
	public void deleteAll(@AuthenticationPrincipal User user) {
		delegate.deleteAll(user);
	}
}
