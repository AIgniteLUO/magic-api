package org.ssssssss.magicapi.core.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketSessionManagerTest {

	@BeforeEach
	@AfterEach
	void resetManager() {
		WebSocketSessionManager.shutdown();
	}

	@Test
	void settingNotifyServiceDoesNotStartSchedulers() {
		WebSocketSessionManager.setMagicNotifyService(notify -> {
		});

		assertFalse(WebSocketSessionManager.isSchedulerRunning());
	}

	@Test
	void loggingStartsSchedulersAndShutdownStopsThem() {
		WebSocketSessionManager.sendLogs("client", "message");

		assertTrue(WebSocketSessionManager.isSchedulerRunning());
		WebSocketSessionManager.shutdown();
		assertFalse(WebSocketSessionManager.isSchedulerRunning());
	}
}
