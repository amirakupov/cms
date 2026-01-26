package com.cms.controller;

import com.cms.dto.ServiceResponseDto;
import com.cms.entity.ServiceEntity;
import com.cms.service.PremiumService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/cms")
public class Controller {
    private final PremiumService premiumService;

    public Controller(PremiumService premiumService) {
        this.premiumService = premiumService;
    }

    @GetMapping("/services")
    public List<ServiceResponseDto> getAllServices(){
        return premiumService.listServices();
    }
}
