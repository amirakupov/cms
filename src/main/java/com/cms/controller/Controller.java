package com.cms.controller;

import com.cms.dto.ServiceRequestDto;
import com.cms.dto.ServiceResponseDto;
import com.cms.entity.ServiceEntity;
import com.cms.service.PremiumService;
import org.springframework.web.bind.annotation.*;

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
    @GetMapping("/service")
    public ServiceResponseDto getService(@RequestParam int id){
        return premiumService.getOneService(id);
    }
    @PostMapping("/service")
    public ServiceResponseDto createService(@RequestBody ServiceRequestDto newService){
        return premiumService.createNewService(newService);
    }
}
