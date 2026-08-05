package org.ssssssss.magicapi.task.starter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.ssssssss.magicapi.core.config.MagicPluginConfiguration;
import org.ssssssss.magicapi.core.model.Plugin;
import org.ssssssss.magicapi.core.web.MagicControllerRegister;
import org.ssssssss.magicapi.task.service.TaskInfoMagicResourceStorage;
import org.ssssssss.magicapi.task.service.TaskMagicDynamicRegistry;
import org.ssssssss.magicapi.task.web.MagicTaskController;

@Configuration
@EnableConfigurationProperties(MagicTaskConfig.class)
public class MagicAPITaskConfiguration implements MagicPluginConfiguration {

	private final MagicTaskConfig config;

	public MagicAPITaskConfiguration(MagicTaskConfig config) {
		this.config = config;
	}

	@Bean
	@ConditionalOnMissingBean
	public TaskInfoMagicResourceStorage taskInfoMagicResourceStorage() {
		return new TaskInfoMagicResourceStorage();
	}

	@Bean
	@ConditionalOnMissingBean
	public TaskMagicDynamicRegistry taskMagicDynamicRegistry(TaskInfoMagicResourceStorage taskInfoMagicResourceStorage,
																		 ObjectProvider<TaskScheduler> taskSchedulerProvider) {
		TaskScheduler taskScheduler = config.isEnable() ? taskSchedulerProvider.getIfAvailable() : null;
		return new TaskMagicDynamicRegistry(taskInfoMagicResourceStorage, taskScheduler, config.isLog());
	}

	@Bean
	@ConditionalOnProperty(prefix = "magic-api.task", name = "enable", havingValue = "true", matchIfMissing = true)
	@ConditionalOnMissingBean(TaskScheduler.class)
	public ThreadPoolTaskScheduler magicTaskScheduler() {
		MagicTaskConfig.Shutdown shutdown = config.getShutdown();
		ThreadPoolTaskScheduler poolTaskScheduler = new ThreadPoolTaskScheduler();
		poolTaskScheduler.setPoolSize(config.getPool().getSize());
		poolTaskScheduler.setWaitForTasksToCompleteOnShutdown(shutdown.isAwaitTermination());
		if(shutdown.getAwaitTerminationPeriod() != null){
			poolTaskScheduler.setAwaitTerminationSeconds((int) shutdown.getAwaitTerminationPeriod().getSeconds());
		}
		poolTaskScheduler.setThreadNamePrefix(config.getThreadNamePrefix());
		return poolTaskScheduler;
	}

	@Override
	public Plugin plugin() {
		return new Plugin("定时任务", "MagicTask", "magic-task.1.0.0.iife.js");
	}

	@Override
	public MagicControllerRegister controllerRegister() {
		return (mapping, configuration) -> mapping.registerController(new MagicTaskController(configuration));
	}
}
