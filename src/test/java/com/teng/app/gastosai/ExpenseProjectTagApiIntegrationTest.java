package com.teng.app.gastosai;

import com.jayway.jsonpath.JsonPath;
import com.teng.app.gastosai.config.JwtUtil;
import com.teng.app.gastosai.entity.User;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TEN-178 end to end: an expense carries a project or client tag, or none; expenses filter and
 * total by it; and renaming it orphans nothing.
 *
 * <p>Against the migrated Postgres schema rather than mocks, because two of the three claims are
 * claims about the schema — that the tag is a row an expense points at, and that a total groups by
 * that row. A mocked repository would agree with whatever the service asked it, including a
 * name-copied-per-expense design that fails the rename test the moment it reaches a database.
 */
@SpringBootTest
class ExpenseProjectTagApiIntegrationTest extends PostgresBackedTest {

	@Autowired
	WebApplicationContext webApplicationContext;

	@Autowired
	UserRepository userRepository;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	JwtUtil jwtUtil;

	MockMvc mockMvc;
	String authHeader;
	String otherAuthHeader;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
				.apply(springSecurity())
				.build();

		User user = userRepository.save(User.builder()
				.name("Freelancer")
				.email("freelancer@test.com")
				.password(passwordEncoder.encode("password"))
				.build());

		// A second, unrelated freelancer. Tags are per user, and that is a claim worth a witness.
		User other = userRepository.save(User.builder()
				.name("Other Freelancer")
				.email("other@test.com")
				.password(passwordEncoder.encode("password"))
				.build());

		authHeader = "Bearer " + jwtUtil.generate(user.getEmail());
		otherAuthHeader = "Bearer " + jwtUtil.generate(other.getEmail());
	}

	private String createExpense(String body) throws Exception {
		return createExpense(body, authHeader);
	}

	private String createExpense(String body, String asUser) throws Exception {
		return mockMvc.perform(post("/expenses")
						.header("Authorization", asUser)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getContentAsString();
	}

	@Test
	void expenseCarriesATagOrNone() throws Exception {
		String tagged = createExpense("""
				{"amount": 1200.00, "category": "Software", "date": "2026-05-02T09:00:00",
				 "description": "Figma seat", "project": "Acme Corp"}
				""");
		assertThat(JsonPath.<String>read(tagged, "$.project")).isEqualTo("Acme Corp");
		assertThat(JsonPath.<Integer>read(tagged, "$.projectId")).isNotNull();

		String untagged = createExpense("""
				{"amount": 180.00, "category": "Food", "date": "2026-05-02T12:00:00",
				 "description": "Lunch"}
				""");
		assertThat(JsonPath.<String>read(untagged, "$.project")).isNull();
		assertThat(JsonPath.<Integer>read(untagged, "$.projectId")).isNull();
	}

	@Test
	void sameTagIsReusedWhateverTheCasing() throws Exception {
		String first = createExpense("""
				{"amount": 100.00, "date": "2026-05-03T09:00:00", "description": "Domain",
				 "project": "Acme Corp"}
				""");
		String second = createExpense("""
				{"amount": 200.00, "date": "2026-05-04T09:00:00", "description": "Hosting",
				 "project": "acme corp"}
				""");

		assertThat(JsonPath.<Integer>read(second, "$.projectId"))
				.isEqualTo(JsonPath.<Integer>read(first, "$.projectId"));

		mockMvc.perform(get("/expenses/projects").header("Authorization", authHeader))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].name").value("Acme Corp"));
	}

	@Test
	void expensesFilterAndTotalByTag() throws Exception {
		String acme = createExpense("""
				{"amount": 1000.00, "date": "2026-05-05T09:00:00", "description": "Acme sprint 1",
				 "project": "Acme Corp"}
				""");
		int acmeId = JsonPath.read(acme, "$.projectId");
		createExpense("""
				{"amount": 500.00, "date": "2026-05-06T09:00:00", "description": "Acme sprint 2",
				 "project": "Acme Corp"}
				""");
		createExpense("""
				{"amount": 300.00, "date": "2026-05-07T09:00:00", "description": "Beta logo",
				 "project": "Beta Studio"}
				""");
		createExpense("""
				{"amount": 90.00, "date": "2026-05-08T09:00:00", "description": "Groceries"}
				""");

		mockMvc.perform(get("/expenses")
						.header("Authorization", authHeader)
						.param("projectId", String.valueOf(acmeId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].project").value("Acme Corp"))
				.andExpect(jsonPath("$[1].project").value("Acme Corp"));

		mockMvc.perform(get("/expenses/page")
						.header("Authorization", authHeader)
						.param("projectId", String.valueOf(acmeId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(2));

		// Totals are per tag, and the untagged expense is not a row here.
		mockMvc.perform(get("/expenses/report/project").header("Authorization", authHeader))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].project").value("Acme Corp"))
				.andExpect(jsonPath("$[0].total").value(1500.00))
				.andExpect(jsonPath("$[1].project").value("Beta Studio"))
				.andExpect(jsonPath("$[1].total").value(300.00));

		// The same total, narrowed to the month the work was billed in.
		mockMvc.perform(get("/expenses/report/project")
						.header("Authorization", authHeader)
						.param("month", "2026-05"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].total").value(1500.00));
		mockMvc.perform(get("/expenses/report/project")
						.header("Authorization", authHeader)
						.param("month", "2026-04"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void renamingATagDoesNotOrphanItsExpenses() throws Exception {
		String created = createExpense("""
				{"amount": 750.00, "date": "2026-05-09T09:00:00", "description": "Acme retainer",
				 "project": "Acme Corp"}
				""");
		int projectId = JsonPath.read(created, "$.projectId");

		mockMvc.perform(put("/expenses/projects/" + projectId)
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name": "Acme Incorporated"}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(projectId))
				.andExpect(jsonPath("$.name").value("Acme Incorporated"));

		// Same expense, same tag id, new name — and still reachable by the filter it was tagged with.
		mockMvc.perform(get("/expenses")
						.header("Authorization", authHeader)
						.param("projectId", String.valueOf(projectId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].description").value("Acme retainer"))
				.andExpect(jsonPath("$[0].projectId").value(projectId))
				.andExpect(jsonPath("$[0].project").value("Acme Incorporated"));

		mockMvc.perform(get("/expenses/report/project").header("Authorization", authHeader))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].projectId").value(projectId))
				.andExpect(jsonPath("$[0].project").value("Acme Incorporated"))
				.andExpect(jsonPath("$[0].total").value(750.00));
	}

	@Test
	void renamingOntoAnExistingTagIsRefused() throws Exception {
		String acme = createExpense("""
				{"amount": 100.00, "date": "2026-05-10T09:00:00", "description": "Acme",
				 "project": "Acme Corp"}
				""");
		createExpense("""
				{"amount": 100.00, "date": "2026-05-11T09:00:00", "description": "Beta",
				 "project": "Beta Studio"}
				""");
		int acmeId = JsonPath.read(acme, "$.projectId");

		mockMvc.perform(put("/expenses/projects/" + acmeId)
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name": "beta studio"}
								"""))
				.andExpect(status().isConflict());

		// Refused, not half-applied.
		mockMvc.perform(get("/expenses/projects").header("Authorization", authHeader))
				.andExpect(jsonPath("$.length()").value(2));
	}

	/**
	 * One user's tag is unreachable from another's session — not merely absent from their list.
	 *
	 * <p>Every new query is scoped by user, but "scoped by inspection" is the claim that stops
	 * being true the first time someone adds a path. Naming another user's `projectId` must return
	 * nothing rather than their expenses, and renaming their tag must 404 rather than succeed.
	 */
	@Test
	void oneUsersTagIsUnreachableFromAnothersSession() throws Exception {
		String mine = createExpense("""
				{"amount": 1000.00, "date": "2026-05-13T09:00:00", "description": "Acme retainer",
				 "project": "Acme Corp"}
				""");
		int myProjectId = JsonPath.read(mine, "$.projectId");

		// The other user's identically named tag is their own row, not a shared one.
		String theirs = createExpense("""
				{"amount": 40.00, "date": "2026-05-13T10:00:00", "description": "Their coffee",
				 "project": "Acme Corp"}
				""", otherAuthHeader);
		assertThat(JsonPath.<Integer>read(theirs, "$.projectId")).isNotEqualTo(myProjectId);

		mockMvc.perform(get("/expenses/projects").header("Authorization", otherAuthHeader))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(JsonPath.<Integer>read(theirs, "$.projectId")));

		// Naming my tag id from their session leaks nothing — the user predicate still applies.
		mockMvc.perform(get("/expenses")
						.header("Authorization", otherAuthHeader)
						.param("projectId", String.valueOf(myProjectId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
		mockMvc.perform(get("/expenses/page")
						.header("Authorization", otherAuthHeader)
						.param("projectId", String.valueOf(myProjectId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(0));

		// And they cannot rename it.
		mockMvc.perform(put("/expenses/projects/" + myProjectId)
						.header("Authorization", otherAuthHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name": "Hijacked"}
								"""))
				.andExpect(status().isNotFound());

		mockMvc.perform(get("/expenses/projects").header("Authorization", authHeader))
				.andExpect(jsonPath("$[0].id").value(myProjectId))
				.andExpect(jsonPath("$[0].name").value("Acme Corp"));

		// Their totals are theirs alone.
		mockMvc.perform(get("/expenses/report/project").header("Authorization", otherAuthHeader))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].total").value(40.00));
	}

	@Test
	void updateCanTagAndUntagAnExpense() throws Exception {
		String created = createExpense("""
				{"amount": 400.00, "date": "2026-05-12T09:00:00", "description": "Stock photos"}
				""");
		int expenseId = JsonPath.read(created, "$.id");

		mockMvc.perform(put("/expenses/" + expenseId)
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"amount": 400.00, "date": "2026-05-12T09:00:00",
								 "description": "Stock photos", "project": "Acme Corp"}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.project").value("Acme Corp"));

		// A PUT states the whole expense, so an absent tag removes the one it carried.
		mockMvc.perform(put("/expenses/" + expenseId)
						.header("Authorization", authHeader)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"amount": 400.00, "date": "2026-05-12T09:00:00",
								 "description": "Stock photos"}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.project").isEmpty())
				.andExpect(jsonPath("$.projectId").isEmpty());

		mockMvc.perform(get("/expenses/report/project").header("Authorization", authHeader))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}
}
