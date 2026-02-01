package com.cms.controller;

import com.cms.dto.LoginRequest;
import com.cms.dto.LoginResponse;
import com.cms.dto.ServiceRequestDto;
import com.cms.dto.ServiceResponseDto;
import com.cms.service.AuthService;
import com.cms.service.PremiumService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/cms")
public class Controller {
    private final PremiumService premiumService;

    private final AuthService authService;


    public Controller(PremiumService premiumService, AuthService authService) {
        this.premiumService = premiumService;
        this.authService = authService;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest loginRequest){
        return authService.login(loginRequest);
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
