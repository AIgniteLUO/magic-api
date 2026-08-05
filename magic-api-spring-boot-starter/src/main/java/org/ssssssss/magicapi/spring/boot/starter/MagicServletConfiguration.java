package org.ssssssss.magicapi.spring.boot.starter;

import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportSelector;
import org.springframework.core.type.AnnotationMetadata;

@Configuration
@AutoConfigureBefore(MagicAPIAutoConfiguration.class)
public class MagicServletConfiguration {

	public MagicServletConfiguration() {
	}

	static class JakartaConfigurationImportSelector implements ImportSelector {

		@Override
		public String[] selectImports(AnnotationMetadata importingClassMetadata) {
			return new String[]{"org.ssssssss.magicapi.servlet.jakarta.MagicJakartaServletConfiguration"};
		}
	}

	static class JavaEEConfigurationImportSelector implements ImportSelector {

		@Override
		public String[] selectImports(AnnotationMetadata importingClassMetadata) {
			return new String[]{"org.ssssssss.magicapi.servlet.javaee.MagicJavaEEServletConfiguration"};
		}
	}

	@Configuration
	@ConditionalOnClass(name = "jakarta.servlet.http.HttpServletRequest")
	@Import(JakartaConfigurationImportSelector.class)
	static class JakartaEEConfiguration {


	}

	@Configuration
	@ConditionalOnClass(name = "javax.servlet.http.HttpServletRequest")
	@Import(JavaEEConfigurationImportSelector.class)
	static class JavaEEConfiguration{

	}
}
