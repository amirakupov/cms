package com.cms.controller;

import com.cms.dto.*;
import com.cms.service.AuthService;
import com.cms.service.PremiumService;
import org.springframework.beans.factory.annotation.Value;
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

    /** Must match jwt.expirationMs, otherwise the cookie dies while the token is still valid. */
    private final long tokenTtlSeconds;

    /** Off only for plain-HTTP local development; production must serve over HTTPS. */
    private final boolean secureCookie;

    public Controller(PremiumService premiumService,
                      AuthService authService,
                      @Value("${jwt.expirationMs:86400000}") long expirationMs,
                      @Value("${app.cookie.secure:true}") boolean secureCookie) {
        this.premiumService = premiumService;
        this.authService = authService;
        this.tokenTtlSeconds = expirationMs / 1000;
        this.secureCookie = secureCookie;
    }

    //Todo: change to void
    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest loginRequest, HttpServletResponse servletResponse){
        String token = authService.loginAndGetToken(loginRequest);
        servletResponse.addHeader("Set-Cookie", authCookie(token, tokenTtlSeconds).toString());
        return LoginResponse.builder().token(token).build();
    }

    @PostMapping("/logout")
    public void logout(HttpServletResponse servletResponse){
        servletResponse.addHeader("Set-Cookie", authCookie("", 0).toString());
    }

    private ResponseCookie authCookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from("access_token", value)
                .httpOnly(true)
                .secure(secureCookie)
                // Strict, not Lax: the cookie authenticates state-changing calls and CSRF is off.
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAgeSeconds)
                .build();
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
