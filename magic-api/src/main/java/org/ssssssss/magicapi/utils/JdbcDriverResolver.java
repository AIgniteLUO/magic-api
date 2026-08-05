package org.ssssssss.magicapi.utils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves a JDBC driver without linking Magic API to a specific Spring Boot
 * JDBC artifact. Boot 4 moved DatabaseDriver out of spring-boot-core, while
 * Boot 2/3 kept the same public type.
 */
public final class JdbcDriverResolver {

	private static final String DATABASE_DRIVER = "org.springframework.boot.jdbc.DatabaseDriver";
	private static final Map<String, String> DRIVER_CLASS_NAMES = new LinkedHashMap<>();

	static {
		DRIVER_CLASS_NAMES.put("jdbc:h2:", "org.h2.Driver");
		DRIVER_CLASS_NAMES.put("jdbc:mysql:", "com.mysql.cj.jdbc.Driver");
		DRIVER_CLASS_NAMES.put("jdbc:mariadb:", "org.mariadb.jdbc.Driver");
		DRIVER_CLASS_NAMES.put("jdbc:oracle:", "oracle.jdbc.OracleDriver");
		DRIVER_CLASS_NAMES.put("jdbc:postgresql:", "org.postgresql.Driver");
		DRIVER_CLASS_NAMES.put("jdbc:sqlserver:", "com.microsoft.sqlserver.jdbc.SQLServerDriver");
		DRIVER_CLASS_NAMES.put("jdbc:sqlite:", "org.sqlite.JDBC");
		DRIVER_CLASS_NAMES.put("jdbc:dm:", "dm.jdbc.driver.DmDriver");
		DRIVER_CLASS_NAMES.put("jdbc:kingbase:", "com.kingbase8.Driver");
	}

	private JdbcDriverResolver() {
	}

	public static String resolve(String url) {
		if (url == null || url.isEmpty()) {
			return null;
		}
		String bootDriver = resolveWithSpringBoot(url);
		if (bootDriver != null && !bootDriver.isEmpty()) {
			return bootDriver;
		}
		String normalizedUrl = url.toLowerCase();
		return DRIVER_CLASS_NAMES.entrySet().stream()
				.filter(entry -> normalizedUrl.startsWith(entry.getKey()))
				.map(Map.Entry::getValue)
				.findFirst()
				.orElse(null);
	}

	private static String resolveWithSpringBoot(String url) {
		try {
			Class<?> driverType = Class.forName(DATABASE_DRIVER);
			Method fromJdbcUrl = driverType.getMethod("fromJdbcUrl", String.class);
			Object driver = fromJdbcUrl.invoke(null, url);
			if (driver == null || driver.toString().equals("UNKNOWN")) {
				return null;
			}
			return (String) driverType.getMethod("getDriverClassName").invoke(driver);
		} catch (ClassNotFoundException e) {
			return null;
		} catch (IllegalAccessException e) {
			throw new IllegalStateException("读取 Spring Boot JDBC 驱动信息失败", e);
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException) {
				throw (RuntimeException) cause;
			}
			throw new IllegalStateException("读取 Spring Boot JDBC 驱动信息失败", cause);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("读取 Spring Boot JDBC 驱动信息失败", e);
		}
	}
}
