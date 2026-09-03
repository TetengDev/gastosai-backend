package com.teng.app.gastosai;

import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.dto.ExpenseRequest;
import com.teng.app.gastosai.dto.v2.ExpenseRequestV2;
import com.teng.app.gastosai.entity.ExpenseSource;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.repository.ExpenseRepository;
import com.teng.app.gastosai.repository.UserRepository;
import com.teng.app.gastosai.support.PostgresBackedTest;
import io.swagger.v3.oas.annotations.media.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TEN-335 — {@code ExpenseRequestV2.source} is an {@link ExpenseSource}, so v2 refuses an unknown
 * name the way v1 does: inside Jackson, answered by the body handler, with no parse of its own.
 *
 * <p>End-to-end for the same reason {@link ExpenseRequestTest} is: the claim is about binding, and
 * handing the record an already-typed source proves nothing about {@code "source": "TELEPATHY"} on
 * the wire. The last test is the one that is not about a request at all — it is what stops the
 * published v2 schema from drifting away from {@link ExpenseSource} the next time a constant is
 * added.
 */
@SpringBootTest
class ExpenseRequestV2Test extends PostgresBackedTest {

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
				.name("V2 Request User")
				.email("expense-request-v2-test@test.com")
				.password(passwordEncoder.encode("password"))
				.build());
		authHeader = "Bearer " + jwtUtil.generate(user.getEmail());
	}

	/**
	 * The residual TEN-335 closes. The status is the 400 v2 has always answered; what changed is
	 * which layer produces it — the body handler, from the enum, rather than a catch in the DTO.
	 */
	@Test
	void anUnknownSourceIsRefusedByTheBodyHandler() throws Exception {
		mockMvc.perform(post("/api/v2/expenses")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"amount": 1000, "description": "v2 nonsense", "source": "TELEPATHY"}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value(
						"Invalid value for field 'source'. Allowed values: "
								+ "MANUAL, QUICK_ADD, RECEIPT_SCAN, RECURRING, IMPORT."));

		assertThat(expenseRepository.findByUserAndDescriptionContainingIgnoreCase(user, "v2 nonsense"))
				.isEmpty();
	}

	/** A real source that is not the client's to declare binds, so the service still refuses it. */
	@Test
	void aSourceTheServerDecidesIsRefusedByTheService() throws Exception {
		mockMvc.perform(post("/api/v2/expenses")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"amount": 1000, "description": "v2 smuggled", "source": "IMPORT"}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail")
						.value("source must be MANUAL or RECEIPT_SCAN — got 'IMPORT'."));

		assertThat(expenseRepository.findByUserAndDescriptionContainingIgnoreCase(user, "v2 smuggled"))
				.isEmpty();
	}

	@Test
	void aDeclarableSourceStillBinds() throws Exception {
		mockMvc.perform(post("/api/v2/expenses")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"amount": 1000, "description": "v2 scanned", "source": "RECEIPT_SCAN"}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.source").value("RECEIPT_SCAN"));
	}

	/** Omitting it still means MANUAL, and {@code toV1()} still carries the rest of the request. */
	@Test
	void anOmittedSourceIsManual() throws Exception {
		mockMvc.perform(post("/api/v2/expenses")
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"amount": 1250, "description": "v2 plain"}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.amount").value(1250))
				.andExpect(jsonPath("$.source").value("MANUAL"));
	}

	/**
	 * Both surfaces publish the narrowed list from the same two constants, and those constants are
	 * the client-declarable half of {@link ExpenseSource}. A sixth constant marked declarable, or a
	 * flag flipped on an existing one, fails here rather than being published as a lie.
	 */
	@Test
	void bothSchemasPublishTheClientDeclarableSources() {
		List<String> declarable = Arrays.stream(ExpenseSource.values())
				.filter(ExpenseSource::isClientDeclarable)
				.map(ExpenseSource::name)
				.toList();

		assertThat(List.of(ExpenseRequest.DECLARABLE_MANUAL, ExpenseRequest.DECLARABLE_RECEIPT_SCAN))
				.containsExactlyInAnyOrderElementsOf(declarable);
		assertThat(publishedSourceValues(ExpenseRequest.class))
				.containsExactlyInAnyOrderElementsOf(declarable);
		assertThat(publishedSourceValues(ExpenseRequestV2.class))
				.containsExactlyInAnyOrderElementsOf(declarable);
	}

	/**
	 * Read off the accessor rather than the {@link RecordComponent}: {@code @Schema} does not list
	 * {@code RECORD_COMPONENT} among its targets, so it propagates to the field, the constructor
	 * parameter and the accessor, and asking the component itself gives back nothing.
	 */
	private static List<String> publishedSourceValues(Class<?> record) {
		RecordComponent component = Arrays.stream(record.getRecordComponents())
				.filter(it -> it.getName().equals("source"))
				.findFirst()
				.orElseThrow(() -> new AssertionError(record.getSimpleName() + " has no source component"));
		assertThat(component.getType()).isEqualTo(ExpenseSource.class);

		Schema schema = component.getAccessor().getAnnotation(Schema.class);
		assertThat(schema).as("%s.source carries @Schema", record.getSimpleName()).isNotNull();
		return List.of(schema.allowableValues());
	}
}
