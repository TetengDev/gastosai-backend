package com.teng.app.gastosai;

import com.teng.app.gastosai.dto.ChatMessageDto;
import com.teng.app.gastosai.dto.ChatResponse;
import com.teng.app.gastosai.dto.ConversationSummaryDto;
import com.teng.app.gastosai.entity.Conversation;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.exception.ResourceNotFoundException;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.service.ConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ConversationServiceTest {

	@Autowired ConversationService service;
	@Autowired UserRepository userRepository;

	private User alice;
	private User bob;

	@BeforeEach
	void setUp() {
		userRepository.deleteAll();
		alice = userRepository.save(User.builder().name("Alice").email("alice@test.com").password("x").build());
		bob = userRepository.save(User.builder().name("Bob").email("bob@test.com").password("x").build());
	}

	@Test
	void getOrCreate_nullId_createsNewOwnedConversation() {
		Conversation c = service.getOrCreate(alice, null);
		assertThat(c.getId()).isNotNull();
		assertThat(c.getUser().getId()).isEqualTo(alice.getId());
	}

	@Test
	void getOrCreate_otherUsersId_createsNewInsteadOfReturningTheirs() {
		Conversation aliceConv = service.getOrCreate(alice, null);
		Conversation forBob = service.getOrCreate(bob, aliceConv.getId());
		assertThat(forBob.getId()).isNotEqualTo(aliceConv.getId());
		assertThat(forBob.getUser().getId()).isEqualTo(bob.getId());
	}

	@Test
	void recordTurn_persistsBothMessages_andSetsTitleFromFirstUserMessage() {
		Conversation c = service.getOrCreate(alice, null);
		service.recordTurn(c, "How much did I spend on food?", new ChatResponse("text", "You spent 500.", null));

		List<ChatMessageDto> msgs = service.messages(c.getId(), alice);
		assertThat(msgs).hasSize(2);
		assertThat(msgs.get(0).role()).isEqualTo("USER");
		assertThat(msgs.get(0).content()).isEqualTo("How much did I spend on food?");
		assertThat(msgs.get(1).role()).isEqualTo("ASSISTANT");
		assertThat(msgs.get(1).content()).isEqualTo("You spent 500.");
		assertThat(msgs.get(1).responseType()).isEqualTo("text");

		assertThat(service.list(alice).get(0).title()).isEqualTo("How much did I spend on food?");
	}

	@Test
	void list_isScopedToUser() {
		service.getOrCreate(alice, null);
		assertThat(service.list(alice)).hasSize(1);
		assertThat(service.list(bob)).isEmpty();
	}

	@Test
	void messages_otherUser_throwsNotFound() {
		Conversation c = service.getOrCreate(alice, null);
		assertThatThrownBy(() -> service.messages(c.getId(), bob))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	void delete_removesConversationAndMessages() {
		Conversation c = service.getOrCreate(alice, null);
		service.recordTurn(c, "hi", new ChatResponse("text", "hello", null));
		service.delete(c.getId(), alice);
		assertThat(service.list(alice)).isEmpty();
	}

	@Test
	void delete_otherUser_throwsNotFound() {
		Conversation c = service.getOrCreate(alice, null);
		assertThatThrownBy(() -> service.delete(c.getId(), bob))
				.isInstanceOf(ResourceNotFoundException.class);
	}
}
