package com.teng.app.gastosai;

import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.dto.ChatResponse;
import com.teng.app.gastosai.entity.Conversation;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.service.ConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class ConversationApiIntegrationTest {

	@Autowired WebApplicationContext webApplicationContext;
	@Autowired UserRepository userRepository;
	@Autowired PasswordEncoder passwordEncoder;
	@Autowired JwtUtil jwtUtil;
	@Autowired ConversationService conversationService;

	MockMvc mockMvc;
	User owner;
	User other;
	String ownerAuth;
	String otherAuth;
	Long convId;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
		userRepository.deleteAll();
		owner = userRepository.save(User.builder().name("Owner").email("owner@test.com")
				.password(passwordEncoder.encode("password")).build());
		other = userRepository.save(User.builder().name("Other").email("other@test.com")
				.password(passwordEncoder.encode("password")).build());
		ownerAuth = "Bearer " + jwtUtil.generate(owner.getEmail());
		otherAuth = "Bearer " + jwtUtil.generate(other.getEmail());

		Conversation c = conversationService.getOrCreate(owner, null);
		conversationService.recordTurn(c, "How much did I spend?", new ChatResponse("text", "You spent 500.", null));
		convId = c.getId();
	}

	@Test
	void list_returnsOwnConversations() throws Exception {
		mockMvc.perform(get("/chat/conversations").header("Authorization", ownerAuth))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].title").value("How much did I spend?"));
	}

	@Test
	void messages_returnsTurnInOrder() throws Exception {
		mockMvc.perform(get("/chat/conversations/" + convId).header("Authorization", ownerAuth))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].role").value("USER"))
				.andExpect(jsonPath("$[1].role").value("ASSISTANT"));
	}

	@Test
	void otherUser_cannotSeeOwnersConversations() throws Exception {
		mockMvc.perform(get("/chat/conversations").header("Authorization", otherAuth))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
		mockMvc.perform(get("/chat/conversations/" + convId).header("Authorization", otherAuth))
				.andExpect(status().isNotFound());
	}

	@Test
	void delete_removesConversation() throws Exception {
		mockMvc.perform(delete("/chat/conversations/" + convId).header("Authorization", ownerAuth))
				.andExpect(status().isNoContent());
		mockMvc.perform(get("/chat/conversations").header("Authorization", ownerAuth))
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void unauthenticated_isRejected() throws Exception {
		mockMvc.perform(get("/chat/conversations"))
				.andExpect(status().is4xxClientError());
	}
}
