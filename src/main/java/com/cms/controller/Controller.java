package com.cms.controller;

import com.cms.service.PremiumService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/cms")
public class Controller {
    @GetMapping("/services")
    public PremiumService getAllServices(PremiumService premiumService){
        return premiumService.list();
    }
}
