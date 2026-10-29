package com.example.wechatbot.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AppProperties properties;

    public WebConfig(AppProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(properties.getCors().originList().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", "X-Agent-Token", "X-Admin-Token")
                .exposedHeaders("Location")
                .allowCredentials(false)
                .maxAge(3600);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(false)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        // 空路径 = 目录本身，不能当静态资源返回，否则读取目录会抛异常（表现为 500）
                        if (!resourcePath.isBlank()) {
                            Resource requested = location.createRelative(resourcePath);
                            // 注意：打包成 JAR 后 isFile() 恒为 false，只能用「不是目录」来判断
                            if (requested.exists() && requested.isReadable() && !isDirectory(requested)) {
                                return requested;
                            }
                        }
                        if (resourcePath.startsWith("api/") || resourcePath.startsWith("actuator/")
                                || resourcePath.startsWith("assets/")) {
                            return null;
                        }
                        Resource index = new ClassPathResource("static/index.html");
                        return index.exists() ? index : null;
                    }

                    private boolean isDirectory(Resource resource) {
                        try {
                            return resource.getFile().isDirectory();
                        } catch (IOException | RuntimeException ex) {
                            // JAR 内的资源没有 File 形态，直接视为文件
                            return false;
                        }
                    }
                });
    }
}



