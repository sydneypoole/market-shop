package com.marketshop.bootstrap.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpringBeanConstructorContractTest {

    @Test
    void multiConstructorComponentsDeclareExactlyOneInjectionConstructor() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));

        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        var beanDefinitions = scanner.findCandidateComponents("com.marketshop");
        assertThat(beanDefinitions).as("Market Shop Spring components must be discoverable").isNotEmpty();
        List<String> violations = new ArrayList<>();
        for (var beanDefinition : beanDefinitions) {
            Class<?> beanType = classLoader.loadClass(beanDefinition.getBeanClassName());
            var constructors = beanType.getDeclaredConstructors();
            if (constructors.length < 2) {
                continue;
            }
            long injectionConstructors = Arrays.stream(constructors)
                    .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                    .count();
            if (injectionConstructors != 1) {
                violations.add(beanType.getName() + " has " + constructors.length
                        + " constructors but " + injectionConstructors + " @Autowired constructors");
            }
        }

        assertThat(violations)
                .as("Every multi-constructor Spring component must select exactly one injection constructor")
                .isEmpty();
    }
}
