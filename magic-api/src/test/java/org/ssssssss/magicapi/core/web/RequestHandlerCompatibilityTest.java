package org.ssssssss.magicapi.core.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestHandlerCompatibilityTest {

	@Test
	void responseEntityHeaderNamesSupportMultiValueHeadersOnSpringSeven() {
		HttpHeaders headers = new HttpHeaders();
		headers.add("Access-Control-Expose-Headers", "X-Trace-Id");
		headers.add("Access-Control-Expose-Headers", "X-Request-Id");
		ResponseEntity<String> response = new ResponseEntity<>("ok", headers, HttpStatus.OK);

		Set<String> names = RequestHandler.getResponseHeaderNames(response);

		assertEquals(1, names.size());
		assertTrue(names.contains("Access-Control-Expose-Headers"));
	}
}
