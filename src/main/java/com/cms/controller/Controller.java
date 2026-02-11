package com.cms.controller;

import com.cms.dto.*;
import com.cms.service.AuthService;
import com.cms.service.PremiumService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseCookie;
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
    //Todo: change to void
    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest loginRequest, HttpServletResponse servletResponse){
        String token = authService.loginAndGetToken(loginRequest);
        ResponseCookie cookie = ResponseCookie.from("access_token", token)
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(60 * 60)
                .build();

        servletResponse.addHeader("Set-Cookie", cookie.toString());
        return LoginResponse.builder().token(token).build();
    }
    @PostMapping("/logout")
    public void logout(HttpServletResponse servletResponse){
        ResponseCookie cookie = ResponseCookie.from("access_token", "")
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
        servletResponse.addHeader("Set-Cookie", cookie.toString());
    }
    @GetMapping("/doctors")
    public List<DoctorsResponseDto> getAllDoctors(){
        return premiumService.listDoctors();
    }

    @GetMapping("/doctor/{id}")
    public DoctorsResponseDto getDoctor(@PathVariable Integer id){
        return premiumService.getOneDoctor(id);
    }

    @PostMapping("/doctor")
    public DoctorsResponseDto createDoctor(@RequestBody DoctorRequestDto requestDto){
        return premiumService.createNewDoctor(requestDto);
    }

    @PatchMapping("/doctors/{id}")
    public DoctorsResponseDto updateDoctor(@PathVariable Integer id,
                                            @RequestBody DoctorRequestDto requestDto){
        return premiumService.patchDoctor(id, requestDto);
    }

    @GetMapping("/services")
    public List<ServiceResponseDto> getAllServices(){
        return premiumService.listServices();
    }

    @GetMapping("/service/{id}")
    public ServiceResponseDto getService(@PathVariable Integer id){
        return premiumService.getOneService(id);
    }
    @PostMapping("/service")
    public ServiceResponseDto createService(@RequestBody ServiceRequestDto newService){
        return premiumService.createNewService(newService);
    }
    @PatchMapping("/service/{id}")
    public ServiceResponseDto patchService(@PathVariable Integer id,
                                           @RequestBody ServiceRequestDto requestDto)
    {
        return premiumService.patchService(id, requestDto);
    }
}
