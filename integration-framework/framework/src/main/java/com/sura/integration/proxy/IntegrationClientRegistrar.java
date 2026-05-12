package com.sura.integration.proxy;

import com.sura.integration.annotation.EnableIntegrationClients;
import com.sura.integration.annotation.IntegrationClient;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

import java.util.Set;

public class IntegrationClientRegistrar implements ImportBeanDefinitionRegistrar {

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata,
                                        BeanDefinitionRegistry registry) {
        String[] basePackages = resolveBasePackages(importingClassMetadata);
        ClassPathScanningCandidateComponentProvider scanner = buildScanner();

        for (String pkg : basePackages) {
            Set<BeanDefinition> candidates = scanner.findCandidateComponents(pkg);
            for (BeanDefinition candidate : candidates) {
                try {
                    Class<?> iface = Class.forName(candidate.getBeanClassName());
                    BeanDefinitionBuilder builder =
                            BeanDefinitionBuilder.genericBeanDefinition(IntegrationClientFactoryBean.class);
                    builder.addConstructorArgValue(iface);
                    registry.registerBeanDefinition(candidate.getBeanClassName(), builder.getBeanDefinition());
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException(
                            "Could not load @IntegrationClient interface: " + candidate.getBeanClassName(), e);
                }
            }
        }
    }

    private String[] resolveBasePackages(AnnotationMetadata metadata) {
        AnnotationAttributes attrs = AnnotationAttributes.fromMap(
                metadata.getAnnotationAttributes(EnableIntegrationClients.class.getName()));

        if (attrs == null) {
            return new String[]{ ClassUtils.getPackageName(metadata.getClassName()) };
        }

        String[] explicit = attrs.getStringArray("basePackages");
        if (explicit.length > 0) {
            return explicit;
        }
        return new String[]{ ClassUtils.getPackageName(metadata.getClassName()) };
    }

    private ClassPathScanningCandidateComponentProvider buildScanner() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                        return beanDefinition.getMetadata().isInterface();
                    }
                };
        scanner.addIncludeFilter(new AnnotationTypeFilter(IntegrationClient.class));
        return scanner;
    }
}
