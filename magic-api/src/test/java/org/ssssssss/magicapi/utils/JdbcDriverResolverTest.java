package org.ssssssss.magicapi.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JdbcDriverResolverTest {

	@Test
	void resolvesKnownDriverWithoutBootFourDatabaseDriver() {
		assertEquals("com.mysql.cj.jdbc.Driver", JdbcDriverResolver.resolve("jdbc:mysql://localhost/demo"));
		assertEquals("org.postgresql.Driver", JdbcDriverResolver.resolve("jdbc:postgresql://localhost/demo"));
	}

	@Test
	void returnsNullForEmptyOrUnknownUrl() {
		assertNull(JdbcDriverResolver.resolve(null));
		assertNull(JdbcDriverResolver.resolve("jdbc:unknown://localhost/demo"));
	}
}
