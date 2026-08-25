package com.teng.app.gastosai.repository;

import com.teng.app.gastosai.entity.CategoryAlias;
import com.teng.app.gastosai.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryAliasRepository extends JpaRepository<CategoryAlias, Long> {

	Optional<CategoryAlias> findByUserAndAlias(User user, String alias);

	List<CategoryAlias> findAllByUserAndCategory_Id(User user, Long categoryId);

	List<CategoryAlias> findAllByUser(User user);

	void deleteByUserAndAlias(User user, String alias);
}
