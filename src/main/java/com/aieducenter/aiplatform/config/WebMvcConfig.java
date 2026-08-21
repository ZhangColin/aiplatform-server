package com.aieducenter.aiplatform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.aieducenter.aiplatform.business.identity.endpoints.interceptor.ApiAuthInterceptor;

/**
 * Web MVC 全局配置（片0，A2 增补鉴权拦截）。
 *
 * <p>ADR-0001 以 /swagger-ui/index 为 swagger UI 正本地址；该无后缀路径不会命中
 * springdoc 的静态资源（index.html），此处重定向补齐。404 语义恢复见
 * {@code com.aieducenter.aiplatform.web.NotFoundExceptionHandler}。</p>
 *
 * <p>A2 起全 {@code /api/**} 拦截（含 SSE 双通道），无会话 401；白名单路径
 * （/auth/**、/v3/api-docs/**、/swagger-ui/**、actuator）不在 /api 下，天然放行。
 * 拦截器无状态直接 new（窄测试上下文扫本包时无需 identity BC 的 bean）。</p>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/swagger-ui/index", "/swagger-ui/index.html");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ApiAuthInterceptor())
                .addPathPatterns("/api/**");
    }
}
