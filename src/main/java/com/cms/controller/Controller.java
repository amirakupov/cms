package com.cms.controller;

import com.cms.dto.*;
import com.cms.service.AuthService;
import com.cms.service.PremiumService;
import org.springframework.http.HttpStatus;
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

    // ── Pages ──

    @GetMapping("/pages")
    public List<PageResponseDto> getPublishedPages() {
        return premiumService.listPublishedPages();
    }

    @GetMapping("/pages/all")
    public List<PageResponseDto> getAllPages() {
        return premiumService.listPages();
    }

    @GetMapping("/page/{slug}")
    public PageResponseDto getPage(@PathVariable String slug) {
        return premiumService.getPageBySlug(slug);
    }

    @PostMapping("/page")
    public PageResponseDto createPage(@RequestBody PageRequestDto requestDto) {
        return premiumService.createPage(requestDto);
    }

    @PatchMapping("/page/{id}")
    public PageResponseDto patchPage(@PathVariable Integer id,
                                     @RequestBody PageRequestDto requestDto) {
        return premiumService.patchPage(id, requestDto);
    }

    @DeleteMapping("/page/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePage(@PathVariable Integer id) {
        premiumService.deletePage(id);
    }

    // ── Blog Posts ──

    @GetMapping("/blog")
    public List<BlogPostResponseDto> getPublishedBlogPosts() {
        return premiumService.listPublishedBlogPosts();
    }

    @GetMapping("/blog/all")
    public List<BlogPostResponseDto> getAllBlogPosts() {
        return premiumService.listAllBlogPosts();
    }

    @GetMapping("/blog/{slug}")
    public BlogPostResponseDto getBlogPost(@PathVariable String slug) {
        return premiumService.getBlogPostBySlug(slug);
    }

    @PostMapping("/blog")
    public BlogPostResponseDto createBlogPost(@RequestBody BlogPostRequestDto requestDto) {
        return premiumService.createBlogPost(requestDto);
    }

    @PatchMapping("/blog/{id}")
    public BlogPostResponseDto patchBlogPost(@PathVariable Integer id,
                                             @RequestBody BlogPostRequestDto requestDto) {
        return premiumService.patchBlogPost(id, requestDto);
    }

    @DeleteMapping("/blog/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBlogPost(@PathVariable Integer id) {
        premiumService.deleteBlogPost(id);
    }
}
