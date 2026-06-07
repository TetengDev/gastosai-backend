package com.teng.app.gastosai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class ExpenseApiIT {

	@Autowired
	WebApplicationContext webApplicationContext;

	MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
	}

	@Test
	void reportPathsAreNotTreatedAsExpenseId() throws Exception {
		mockMvc.perform(get("/expenses/report/monthly"))
				.andExpect(status().isOk());
		mockMvc.perform(get("/expenses/report/category"))
				.andExpect(status().isOk());
	}

	@Test
	void createAndListExpense() throws Exception {
		mockMvc.perform(post("/expenses")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"amount": 250.50, "category": "Food", "date": "2026-04-01", "note": "Lunch"}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.category").value("Food"));

		mockMvc.perform(get("/expenses"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].category").value("Food"));
	}
}
