package net.kyver.placy.controller;

import net.kyver.placy.util.PlaceholderTransformService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Map;

@Controller
@RequestMapping("/")
public class PageController {

    private final PlaceholderTransformService transformService;

    @Autowired
    public PageController(PlaceholderTransformService transformService) {
        this.transformService = transformService;
    }

    @GetMapping
    public String index(Model model) {
        Map<String, Object> metrics = transformService.getPerformanceMetrics();
        model.addAttribute("metrics", metrics);

        model.addAttribute("supportedExtensions", transformService.getSupportedExtensions());
        model.addAttribute("supportedMimeTypes", transformService.getSupportedMimeTypes());

        model.addAttribute("version", "2.0.0");
        model.addAttribute("processorCount", Runtime.getRuntime().availableProcessors());
        model.addAttribute("maxMemoryMB", Runtime.getRuntime().maxMemory() / (1024 * 1024));

        return "index";
    }

    @GetMapping("/health")
    public String health(Model model) {
        model.addAttribute("status", "UP");
        model.addAttribute("version", "2.0.0");
        model.addAttribute("timestamp", System.currentTimeMillis());

        Map<String, Object> metrics = transformService.getPerformanceMetrics();
        model.addAttribute("metrics", metrics);

        return "health";
    }

    @GetMapping("/docs")
    public String documentation(Model model) {
        model.addAttribute("version", "2.0.0");
        model.addAttribute("supportedExtensions", transformService.getSupportedExtensions());

        return "docs";
    }
}
