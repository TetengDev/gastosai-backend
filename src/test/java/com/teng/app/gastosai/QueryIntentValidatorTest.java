package com.teng.app.gastosai;

import com.teng.app.gastosai.ai.query.DateRange;
import com.teng.app.gastosai.ai.query.Metric;
import com.teng.app.gastosai.ai.query.QueryIntent;
import com.teng.app.gastosai.ai.query.QueryIntentValidator;
import com.teng.app.gastosai.ai.query.SortDirection;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class QueryIntentValidatorTest {

    private final QueryIntentValidator validator = new QueryIntentValidator();

    @Test
    void parsesValidIntent() {
        Optional<QueryIntent> intent = validator.parse(
                "{\"metric\":\"SUM_BY_CATEGORY\",\"dateRange\":\"LAST_MONTH\",\"category\":\"Food\",\"sort\":\"ASC\",\"limit\":5}");

        assertThat(intent).isPresent();
        QueryIntent q = intent.get();
        assertThat(q.metric()).isEqualTo(Metric.SUM_BY_CATEGORY);
        assertThat(q.dateRange()).isEqualTo(DateRange.LAST_MONTH);
        assertThat(q.category()).isEqualTo("Food");
        assertThat(q.sort()).isEqualTo(SortDirection.ASC);
        assertThat(q.limit()).isEqualTo(5);
    }

    @Test
    void acceptsLowercaseEnumValues() {
        assertThat(validator.parse("{\"metric\":\"total\",\"dateRange\":\"current_month\"}"))
                .map(QueryIntent::metric).contains(Metric.TOTAL);
    }

    @Test
    void rejectsUnknownMetric() {
        assertThat(validator.parse("{\"metric\":\"DROP_TABLE\",\"dateRange\":\"ALL\"}")).isEmpty();
    }

    @Test
    void rejectsMissingMetric() {
        assertThat(validator.parse("{\"dateRange\":\"ALL\"}")).isEmpty();
    }

    @Test
    void defaultsUnknownDateRangeAndSort() {
        QueryIntent q = validator.parse("{\"metric\":\"TOTAL\",\"dateRange\":\"SINCE_FOREVER\",\"sort\":\"sideways\"}").orElseThrow();
        assertThat(q.dateRange()).isEqualTo(DateRange.CURRENT_MONTH);
        assertThat(q.sort()).isEqualTo(SortDirection.DESC);
    }

    @Test
    void clampsLimitToMaximum() {
        assertThat(validator.parse("{\"metric\":\"SUM_BY_DAY\",\"dateRange\":\"ALL\",\"limit\":99999}"))
                .map(QueryIntent::limit).contains(QueryIntentValidator.MAX_LIMIT);
    }

    @Test
    void resetsNonPositiveLimitToDefault() {
        assertThat(validator.parse("{\"metric\":\"SUM_BY_DAY\",\"dateRange\":\"ALL\",\"limit\":-5}"))
                .map(QueryIntent::limit).contains(QueryIntentValidator.DEFAULT_LIMIT);
    }

    @Test
    void blankCategoryBecomesNull() {
        assertThat(validator.parse("{\"metric\":\"TOTAL\",\"dateRange\":\"ALL\",\"category\":\"   \"}")
                .orElseThrow().category()).isNull();
    }

    @Test
    void overlongCategoryBecomesNull() {
        String longName = "x".repeat(QueryIntentValidator.MAX_CATEGORY_LENGTH + 1);
        assertThat(validator.parse("{\"metric\":\"TOTAL\",\"dateRange\":\"ALL\",\"category\":\"" + longName + "\"}")
                .orElseThrow().category()).isNull();
    }

    @Test
    void rejectsNullBlankAndMalformedJson() {
        assertThat(validator.parse(null)).isEmpty();
        assertThat(validator.parse("")).isEmpty();
        assertThat(validator.parse("not json")).isEmpty();
    }
}
