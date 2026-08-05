package org.ssssssss.magicapi.task.starter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;
import org.ssssssss.magicapi.task.service.TaskMagicDynamicRegistry;

import static org.assertj.core.api.Assertions.assertThat;

class MagicAPITaskConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(MagicAPITaskConfiguration.class);

	@Test
	void disabledTaskPluginDoesNotCreateScheduler() {
		contextRunner.withPropertyValues("magic-api.task.enable=false").run(context -> {
			assertThat(context).hasSingleBean(TaskMagicDynamicRegistry.class);
			assertThat(context).doesNotHaveBean(TaskScheduler.class);
		});
	}

	@Test
	void enabledTaskPluginCreatesSpringManagedScheduler() {
		contextRunner.withPropertyValues("magic-api.task.enable=true").run(context -> {
			assertThat(context).hasSingleBean(TaskMagicDynamicRegistry.class);
			assertThat(context).hasSingleBean(TaskScheduler.class);
			assertThat(context.getBean(TaskScheduler.class)).isInstanceOf(org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class);
		});
	}
}
