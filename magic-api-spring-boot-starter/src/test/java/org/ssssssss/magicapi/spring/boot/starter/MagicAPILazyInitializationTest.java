package org.ssssssss.magicapi.spring.boot.starter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.ssssssss.magicapi.core.model.ApiInfo;
import org.ssssssss.magicapi.core.model.Group;
import org.ssssssss.magicapi.core.service.impl.ApiInfoMagicResourceStorage;
import org.ssssssss.magicapi.utils.JsonUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全局懒加载（spring.main.lazy-initialization=true）下，magic-api 的配置 Bean
 * 必须先于启动加载就绪；否则接口注册拿不到 handler，启动加载失败并冻结工作区。
 */
@Timeout(60)
@SpringBootTest(classes = MagicAPILazyInitializationTest.TestApplication.class,
		webEnvironment = SpringBootTest.WebEnvironment.MOCK,
		properties = {
				"spring.main.lazy-initialization=true",
				"magic-api.prefix=/"
		})
class MagicAPILazyInitializationTest {

	@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
	static class TestApplication {
	}

	@DynamicPropertySource
	static void workspace(DynamicPropertyRegistry registry) throws IOException {
		Path workspace = Files.createTempDirectory("magic-api-lazy-init");
		Path groupDir = workspace.resolve("api").resolve("LazyGroup");
		Files.createDirectories(groupDir);
		Group group = new Group("lazy-group", "LazyGroup");
		group.setType("api");
		group.setParentId("0");
		group.setPath("/lazy-group");
		Files.write(groupDir.resolve("group.json"), JsonUtils.toJsonString(group).getBytes(StandardCharsets.UTF_8));
		ApiInfo api = new ApiInfo();
		api.setId("lazy-api");
		api.setGroupId("lazy-group");
		api.setName("LazyApi");
		api.setPath("/lazy");
		api.setScript("return 1;");
		Files.write(groupDir.resolve("LazyApi.ms"), new ApiInfoMagicResourceStorage("/").write(api));
		registry.add("magic-api.resource.location", workspace::toString);
	}

	@Autowired
	private RequestMappingHandlerMapping handlerMapping;

	@Test
	void registersApiWhenLazyInitializationEnabled() {
		Set<String> mappings = handlerMapping.getHandlerMethods().keySet().stream()
				.map(RequestMappingInfo::toString)
				.collect(Collectors.toSet());
		assertTrue(mappings.stream().anyMatch(mapping -> mapping.contains("/lazy")),
				"启动加载必须注册 magic-api 接口，实际注册：" + mappings);
	}
}
