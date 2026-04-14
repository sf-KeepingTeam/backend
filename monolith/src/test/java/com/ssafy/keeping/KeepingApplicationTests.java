package com.ssafy.keeping;

import com.ssafy.keeping.support.MySqlTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class KeepingApplicationTests extends MySqlTestContainerConfig {

	@Test
	void contextLoads() {
	}

}
