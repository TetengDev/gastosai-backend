package com.teng.app.gastosai;

import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.support.PostgresBackedTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TEN-312 — {@code ExpenseRequest.source} is an {@link com.teng.app.gastosai.entity.ExpenseSource}
 * again, so Jackson binds it and the refusals move from a hand-rolled parse to the type system.
 *
 * <p>Written end-to-end because the claim is about binding: a unit test that hands the record an
 * already-typed source proves nothing about what happens to {@code "source": "TELEPATHY"} on the
 * wire, which is the whole reason the field was a String for two issues.
 *
 * <p>{@link ExpenseSourceIntegrationTest} already asserts the statuses these routes answer. What is
 * asserted here is the part that changed: which layer refuses, and what it says.
 */
@SpringBootTest
class ExpenseRequestTest extends PostgresBackedTest {

	@Autowired
	WebApplicationContext webApplicationContext;

	@Autowired
	UserRepository userRepository;

	@Autowired
	ExpenseRepository expenseRepository;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	JwtUtil jwtUtil;

	MockMvc mockMvc;
	User user;
	String authHeader;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
				.apply(springSecurity())
				.build();

		user = userRepository.save(User.builder()
				.name("Request User")
				.email("expense-request-test@test.com")
				.password(passwordEncoder.encode("password"))
				.build());
		authHeader = "Bearer " + jwtUtil.generate(user.getEmail());
	}

	/**
	 * The reason TEN-175's workaround existed, and the reason it can go: the value never binds, and
	 * the message the client gets names the field and the values it could have sent.
	 */
	@Test
	void anUnknownSourceIsRefusedByTheBodyHandler() throws Exception {
		mockMvc.perform(post("/expenses")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"amount": 10.00, "description": "Nonsense", "source": "TELEPATHY"}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value(
						"Invalid value for field 'source'. Allowed values: "
								+ "MANUAL, QUICK_ADD, RECEIPT_SCAN, RECURRING, IMPORT."));

		assertThat(expenseRepository.findByUserAndDescriptionContainingIgnoreCase(user, "Nonsense"))
				.isEmpty();
	}

	/**
	 * The refusal the type system cannot make. {@code IMPORT} is a real source, so it binds — the
	 * service still has to say that it is not the client's to declare.
	 */
	@Test
	void aSourceTheServerDecidesIsRefusedByTheService() throws Exception {
		mockMvc.perform(post("/expenses")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"amount": 10.00, "description": "Smuggled", "source": "IMPORT"}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail")
						.value("source must be MANUAL or RECEIPT_SCAN — got 'IMPORT'."));

		assertThat(expenseRepository.findByUserAndDescriptionContainingIgnoreCase(user, "Smuggled"))
				.isEmpty();
	}

	@Test
	void aDeclarableSourceStillBinds() throws Exception {
		mockMvc.perform(post("/expenses")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"amount": 10.00, "description": "Scanned", "source": "RECEIPT_SCAN"}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.source").value("RECEIPT_SCAN"));
	}

	/*
	 * The two v2 cases that stood here asserted the bridge TEN-335 removed: v2 bound its source as
	 * a String and converted on the way to v1, so an unknown name was refused by a parse in the DTO
	 * rather than by the body handler, and the message differed from v1's. v2's field is an
	 * ExpenseSource now and refuses the same way this one does, so the cases live beside the record
	 * that changed — ExpenseRequestV2Test.
	 */
}
