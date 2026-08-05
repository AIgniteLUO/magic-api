package org.ssssssss.magicapi.spring.boot.starter;

import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.ApplicationContext;
import org.ssssssss.magicapi.utils.IoUtils;
import org.ssssssss.script.exception.MagicScriptException;
import org.ssssssss.script.parsing.ast.statement.ClassConverter;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.Function;

/**
 * JSON自动配置
 *
 * @author mxd
 */
@Configuration
@AutoConfigureBefore(MagicAPIAutoConfiguration.class)
public class MagicJsonAutoConfiguration {

	private static void register(Function<String, Object> processString, Function<byte[], Object> processBytes, Function<Object, String> stringify) {
		register(processString, processBytes, IoUtils::bytes, stringify);
	}

	private static void register(Function<String, Object> processString, Function<byte[], Object> processBytes, Function<InputStream, Object> processInputStream, Function<Object, String> stringify) {
		ClassConverter.register("json", (value, params) -> {
			if (value == null) {
				return params != null && params.length > 0 ? params[0] : null;
			} else if (value instanceof CharSequence) {
				return processString.apply(value.toString());
			} else if (value instanceof byte[]) {
				return processBytes.apply((byte[]) value);
			} else if (value instanceof InputStream) {
				return processInputStream.apply((InputStream) value);
			}
			throw new MagicScriptException(String.format("不支持的类型:%s", value.getClass()));
		});
		ClassConverter.register("stringify", (value, params) -> {
			if (value == null) {
				return params != null && params.length > 0 ? params[0] : null;
			}
			return stringify.apply(value);
		});
	}

	@ConditionalOnClass({ObjectMapper.class})
	@ConditionalOnBean({ObjectMapper.class})
	@ConditionalOnMissingClass("tools.jackson.databind.ObjectMapper")
	@Configuration
	static class MagicJacksonAutoConfiguration {


		MagicJacksonAutoConfiguration(ObjectMapper objectMapper) {
			register(str -> {
				try {
					return objectMapper.readValue(str, Object.class);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}, bytes -> {
				try {
					return objectMapper.readValue(bytes, Object.class);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}, is -> {
				try {
					return objectMapper.readValue(is, Object.class);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}, object -> {
				try {
					return objectMapper.writeValueAsString(object);
				} catch (JsonProcessingException e) {
					throw new RuntimeException(e);
				}
			});
		}
	}

	/**
	 * Spring Boot 4 uses Jackson 3 for the application MVC stack. Keep the
	 * script converter independent from Jackson 3 at compile time so the same
	 * starter can still be used on Boot 2/3 applications.
	 */
	@ConditionalOnClass(name = "tools.jackson.databind.ObjectMapper")
	@Configuration
	static class MagicJackson3AutoConfiguration {

		MagicJackson3AutoConfiguration(ApplicationContext applicationContext) {
			Jackson3Adapter adapter = new Jackson3Adapter(applicationContext);
			register(adapter::read, adapter::read, adapter::read, adapter::write);
		}
	}

	@ConditionalOnMissingBean({ObjectMapper.class})
	@ConditionalOnClass(JSON.class)
	@ConditionalOnMissingClass("tools.jackson.databind.ObjectMapper")
	@Configuration
	static class MagicFastJsonAutoConfiguration {

		MagicFastJsonAutoConfiguration() {
			register(JSON::parse, JSON::parse, JSON::toJSONString);
		}
	}

	private static final class Jackson3Adapter {

		private final Object mapper;
		private final Method readString;
		private final Method readBytes;
		private final Method readInputStream;
		private final Method writeString;

		private Jackson3Adapter(ApplicationContext applicationContext) {
			try {
				Class<?> mapperType = Class.forName("tools.jackson.databind.ObjectMapper");
				Map<String, ?> mappers = applicationContext.getBeansOfType(mapperType);
				if (mappers.isEmpty()) {
					throw new IllegalStateException("未找到 Spring Boot 4 Jackson 3 ObjectMapper Bean");
				}
				this.mapper = mappers.values().iterator().next();
				this.readString = mapperType.getMethod("readValue", String.class, Class.class);
				this.readBytes = mapperType.getMethod("readValue", byte[].class, Class.class);
				this.readInputStream = mapperType.getMethod("readValue", InputStream.class, Class.class);
				this.writeString = mapperType.getMethod("writeValueAsString", Object.class);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException("初始化 Spring Boot 4 Jackson 3 适配失败", e);
			}
		}

		private Object read(String json) {
			return invoke(readString, json, Object.class);
		}

		private Object read(byte[] bytes) {
			return invoke(readBytes, bytes, Object.class);
		}

		private Object read(InputStream inputStream) {
			return invoke(readInputStream, inputStream, Object.class);
		}

		private String write(Object value) {
			return (String) invoke(writeString, value);
		}

		private Object invoke(Method method, Object... arguments) {
			try {
				return method.invoke(mapper, arguments);
			} catch (IllegalAccessException e) {
				throw new IllegalStateException("调用 Jackson 3 方法失败", e);
			} catch (InvocationTargetException e) {
				Throwable cause = e.getCause();
				if (cause instanceof RuntimeException) {
					throw (RuntimeException) cause;
				}
				throw new IllegalStateException("Jackson 3 JSON 处理失败", cause);
			}
		}
	}
}
