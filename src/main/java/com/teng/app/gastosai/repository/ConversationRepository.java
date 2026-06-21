package com.teng.app.gastosai.repository;

import com.teng.app.gastosai.entity.Conversation;
import com.teng.app.gastosai.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

	List<Conversation> findByUserOrderByUpdatedAtDesc(User user);

	Optional<Conversation> findByIdAndUser(Long id, User user);
}
