package com.citydi.logexplorer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.beans.factory.annotation.Value;

/**
 * کاوشگر لاگ — فقط-خواندنی، schema-agnostic.
 *
 * نکته: عمداً هیچ starter مربوط به Spring Data MongoDB اضافه نشده است.
 * دلیلش در MongoClientConfig توضیح داده شده: ابزاری که می‌تواند بنویسد
 * نباید اصلاً در دسترس باشد.
 */
@SpringBootApplication
public class LogExplorerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LogExplorerApplication.class, args);
    }

    @Configuration
    static class WebConfig implements WebMvcConfigurer {

        private final String allowedOrigins;

        WebConfig(@Value("${logexplorer.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
                  String allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }

        @Override
        public void addCorsMappings(CorsRegistry registry) {
            registry.addMapping("/api/**")
                    .allowedOrigins(allowedOrigins.split(","))
                    .allowedMethods("GET", "POST", "OPTIONS")
                    .allowedHeaders("*")
                    .maxAge(3600);
        }
    }
}
