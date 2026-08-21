package com.aieducenter.aiplatform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 全局配置（片0）。
 *
 * <p>ADR-0001 以 /swagger-ui/index 为 swagger UI 正本地址；该无后缀路径不会命中
 * springdoc 的静态资源（index.html），此处重定向补齐。404 语义恢复见
 * {@code com.aieducenter.aiplatform.web.NotFoundExceptionHandler}。</p>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/swagger-ui/index", "/swagger-ui/index.html");
    }
}
