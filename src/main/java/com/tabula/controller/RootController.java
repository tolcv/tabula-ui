package com.tabula.controller;

import com.tabula.config.ApplicationConfiguration;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Controller for serving the SPA root page and settings.
 */
@Controller
public class RootController {

    private final ApplicationConfiguration config;

    public RootController(ApplicationConfiguration config) {
        this.config = config;
    }

    /**
     * Serve the single-page app from index.html
     */
    @GetMapping("/")
    public String index() {
        return "forward:/index.html";
    }

    /**
     * GET /api/settings - Return application settings used by the frontend.
     */
    @GetMapping("/api/settings")
    @ResponseBody
    public Map<String, Object> getSettings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("api_version", "1.3.0");
        settings.put("disable_version_check", config.isDisableVersionCheck());
        settings.put("disable_notifications", config.isDisableNotifications());
        return settings;
    }
}
