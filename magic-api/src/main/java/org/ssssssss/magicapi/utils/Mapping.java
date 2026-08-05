package org.ssssssss.magicapi.utils;

import org.apache.commons.lang3.StringUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.handler.AbstractHandlerMethodMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.stream.Stream;

public class Mapping {

	private final AbstractHandlerMethodMapping<RequestMappingInfo> methodMapping;

	private final String base;

	private final RequestMappingInfo.BuilderConfiguration config;

	private Mapping(AbstractHandlerMethodMapping<RequestMappingInfo> methodMapping, RequestMappingInfo.BuilderConfiguration config, String base) {
		this.methodMapping = methodMapping;
		this.config = config;
		this.base = StringUtils.defaultIfBlank(base, "");
	}

	public static Mapping create(RequestMappingHandlerMapping mapping) {
		return create(mapping, null);
	}

	public static Mapping create(RequestMappingHandlerMapping mapping, String base) {
		return new Mapping(mapping, resolveBuilderConfiguration(mapping), base);
	}

	public RequestMappingInfo.Builder paths(String ... paths){
		RequestMappingInfo.Builder builder = RequestMappingInfo.paths(paths);
		return this.config == null ? builder : builder.options(this.config);
	}

	/**
	 * Spring 5.3 exposes the builder configuration, while older Spring lines
	 * require the configuration to be assembled from the mapping object. Keep
	 * both paths reflective so Boot 4 does not link removed MVC methods.
	 */
	private static RequestMappingInfo.BuilderConfiguration resolveBuilderConfiguration(RequestMappingHandlerMapping mapping) {
		RequestMappingInfo.BuilderConfiguration configuration = invoke(mapping, "getBuilderConfiguration");
		if (configuration != null) {
			return configuration;
		}
		configuration = new RequestMappingInfo.BuilderConfiguration();
		Boolean trailingSlashMatch = invoke(mapping, "useTrailingSlashMatch");
		invoke(configuration, "setTrailingSlashMatch", trailingSlashMatch);
		Object contentNegotiationManager = invoke(mapping, "getContentNegotiationManager");
		invoke(configuration, "setContentNegotiationManager", contentNegotiationManager);
		Object patternParser = invoke(mapping, "getPatternParser");
		if (patternParser != null) {
			invoke(configuration, "setPatternParser", patternParser);
		} else {
			Object pathMatcher = invoke(mapping, "getPathMatcher");
			invoke(configuration, "setPathMatcher", pathMatcher);
		}
		return configuration;
	}

	@SuppressWarnings("unchecked")
	private static <T> T invoke(Object target, String methodName, Object... arguments) {
		try {
			Class<?>[] parameterTypes = Stream.of(arguments)
					.map(argument -> argument == null ? Object.class : argument.getClass())
					.toArray(Class<?>[]::new);
			Method method = findMethod(target.getClass(), methodName, parameterTypes);
			if (method == null) {
				return null;
			}
			return (T) method.invoke(target, arguments);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException("调用 Spring MVC 兼容方法失败: " + methodName, e);
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException) {
				throw (RuntimeException) cause;
			}
			throw new IllegalStateException("调用 Spring MVC 兼容方法失败: " + methodName, cause);
		}
	}

	private static Method findMethod(Class<?> type, String methodName, Class<?>[] parameterTypes) {
		for (Method method : type.getMethods()) {
			if (!method.getName().equals(methodName) || method.getParameterCount() != parameterTypes.length) {
				continue;
			}
			boolean compatible = true;
			Class<?>[] actualTypes = method.getParameterTypes();
			for (int i = 0; i < actualTypes.length; i++) {
				if (parameterTypes[i] != Object.class && !wrap(actualTypes[i]).isAssignableFrom(wrap(parameterTypes[i]))) {
					compatible = false;
					break;
				}
			}
			if (compatible) {
				return method;
			}
		}
		return null;
	}

	private static Class<?> wrap(Class<?> type) {
		if (!type.isPrimitive()) {
			return type;
		}
		if (type == boolean.class) return Boolean.class;
		if (type == byte.class) return Byte.class;
		if (type == short.class) return Short.class;
		if (type == int.class) return Integer.class;
		if (type == long.class) return Long.class;
		if (type == float.class) return Float.class;
		if (type == double.class) return Double.class;
		if (type == char.class) return Character.class;
		return type;
	}

	public Mapping register(RequestMappingInfo requestMappingInfo, Object handler, Method method) {
		this.methodMapping.registerMapping(requestMappingInfo, handler, method);
		return this;
	}

	public RequestMappingInfo register(String requestMethod, String path, Object handler, Method method) {
		RequestMappingInfo info = paths(path).methods(RequestMethod.valueOf(requestMethod.toUpperCase())).build();
		register(info, handler, method);
		return info;
	}

	public Map<RequestMappingInfo, HandlerMethod> getHandlerMethods() {
		return this.methodMapping.getHandlerMethods();
	}

	public Mapping unregister(RequestMappingInfo info) {
		this.methodMapping.unregisterMapping(info);
		return this;
	}

	public Mapping registerController(Object target) {
		Method[] methods = target.getClass().getDeclaredMethods();
		for (Method method : methods) {
			RequestMapping requestMapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
			if (requestMapping != null) {
				String[] paths = Stream.of(requestMapping.value()).map(value -> PathUtils.replaceSlash(base + value)).toArray(String[]::new);
				this.register(paths(paths).methods(requestMapping.method()).build(), target, method);
			}
		}
		return this;
	}
}
