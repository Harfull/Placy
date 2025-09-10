package net.kyver.placy.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class PageController {

    @Autowired
    private Environment environment;

    @GetMapping(value = "/", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public String home() {
        return "{\"status\":\"active\"}";
    }

    @RequestMapping(value = "/error", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public String error() {
        return "{\"error\":\"not_found\",\"message\":\"Endpoint not found\",\"status\":404}";
    }
}
