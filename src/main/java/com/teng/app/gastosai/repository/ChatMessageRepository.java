package com.teng.app.gastosai.repository;

import com.teng.app.gastosai.entity.ChatMessage;
import com.teng.app.gastosai.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

	List<ChatMessage> findByConversationOrderByCreatedAtAsc(Conversation conversation);

	void deleteByConversation(Conversation conversation);
}
