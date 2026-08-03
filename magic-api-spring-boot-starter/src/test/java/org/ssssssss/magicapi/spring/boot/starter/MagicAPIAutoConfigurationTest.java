package org.ssssssss.magicapi.spring.boot.starter;

import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class MagicAPIAutoConfigurationTest {

	@Test
	void shouldUseConvertersConfiguredBySpringMvc() {
		HttpMessageConverter<?> mvcConverter = new MappingJackson2HttpMessageConverter();
		RequestMappingHandlerAdapter handlerAdapter = new RequestMappingHandlerAdapter();
		handlerAdapter.setMessageConverters(Collections.singletonList(mvcConverter));

		List<HttpMessageConverter<?>> converters = MagicAPIAutoConfiguration.resolveHttpMessageConverters(
				handlerAdapter,
				Collections.singletonList(new StringHttpMessageConverter())
		);

		assertEquals(1, converters.size());
		assertSame(mvcConverter, converters.get(0));
		assertNotSame(handlerAdapter.getMessageConverters(), converters);
	}

	@Test
	void shouldFallbackToConverterBeansWhenHandlerAdapterIsUnavailable() {
		List<HttpMessageConverter<?>> fallback = Arrays.asList(
				new StringHttpMessageConverter(),
				new MappingJackson2HttpMessageConverter()
		);

		List<HttpMessageConverter<?>> converters = MagicAPIAutoConfiguration.resolveHttpMessageConverters(null, fallback);

		assertEquals(fallback, converters);
		assertNotSame(fallback, converters);
	}
}
