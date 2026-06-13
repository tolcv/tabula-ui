package com.tabula.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring Web MVC configuration for serving static resources.
 */
@Configuration
public class WebMvcConfiguration implements WebMvcConfigurer {

    /**
     * Configure resource handlers for static files.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/static/**")
            .addResourceLocations("classpath:/static/");
        
        registry.addResourceHandler("/index.html")
            .addResourceLocations("classpath:/templates/index.html");
        
        registry.addResourceHandler("/**")
            .addResourceLocations("classpath:/static/");
    }

}
