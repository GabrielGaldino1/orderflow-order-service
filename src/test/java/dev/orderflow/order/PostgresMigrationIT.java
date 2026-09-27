package dev.orderflow.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PostgresMigrationIT {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void appliesTheOwnedDatabaseMigration() {
		Integer appliedMigrations = jdbcTemplate.queryForObject(
				"select count(*) from flyway_schema_history where success = true",
				Integer.class);

		assertThat(appliedMigrations).isEqualTo(1);
	}
}
