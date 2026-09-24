package com.haylee.textloop;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(PostgreSQLTestConfiguration.class)
class TextloopApplicationTests {

	@Test
	void contextLoads() {
	}

}
