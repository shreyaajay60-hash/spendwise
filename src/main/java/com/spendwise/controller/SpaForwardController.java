package com.spendwise.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwards non-API client routes to index.html for Single-Page Application (SPA) routing.
 * Explicitly ignores /api/** and /h2-console/** routes.
 */
@Controller
public class SpaForwardController {

    @GetMapping(value = {
        "/{path:^(?!api|h2-console)[^\\.]*$}",
        "/{path:^(?!api|h2-console).*$}/**"
    })
    public String forwardClientRoutes() {
        return "forward:/index.html";
    }
}
