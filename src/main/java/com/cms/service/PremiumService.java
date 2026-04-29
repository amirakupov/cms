package com.cms.service;

import com.cms.dto.*;
import com.cms.entity.*;
import com.cms.repo.BlogPostRepository;
import com.cms.repo.DoctorRepository;
import com.cms.repo.PageRepository;
import com.cms.repo.ServiceRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class PremiumService {

    private final ServiceRepository serviceRepository;
    private final DoctorRepository doctorRepository;
    private final PageRepository pageRepository;
    private final BlogPostRepository blogPostRepository;

    public PremiumService(ServiceRepository serviceRepository, DoctorRepository doctorRepository,
                          PageRepository pageRepository, BlogPostRepository blogPostRepository) {
        this.serviceRepository = serviceRepository;
        this.doctorRepository = doctorRepository;
        this.pageRepository = pageRepository;
        this.blogPostRepository = blogPostRepository;
    }

    public List<ServiceResponseDto> listServices(){
        return serviceRepository.findAll().stream().map(ServiceResponseDto::from).toList();
    }

    public ServiceResponseDto getOneService(Integer id){
        return serviceRepository.findById(id).map(ServiceResponseDto::from).orElseThrow(()-> new RuntimeException("Service not found"));
    }

    public ServiceResponseDto createNewService(ServiceRequestDto newService) {
        ServiceEntity saved = serviceRepository.save(ServiceRequestDto.toEntity(newService));
        return ServiceResponseDto.from(saved);
    }

    public List<DoctorsResponseDto> listDoctors() {
        return doctorRepository.findAll().stream().map(DoctorsResponseDto::from).toList();
    }

    public List<DoctorsResponseDto> listDoctorsBySpeciality(String speciality){
        return doctorRepository.findAll().stream()
                .filter(s -> s.getSpecialty().equalsIgnoreCase(speciality))
                .map(doctor -> DoctorsResponseDto.from(doctor))
                .collect(Collectors.toList());
    }

    public List<ServiceResponseDto> listServicesSorted(String sortBy, String direction){
        Comparator<ServiceEntity> comparator = switch (sortBy) {
            case "price" -> Comparator.comparing(ServiceEntity::getPrice);
            case "name" -> Comparator.comparing(ServiceEntity::getServiceName);
            default -> Comparator.comparing(ServiceEntity::getId);
        };
        if (direction.equalsIgnoreCase("desc")){
            comparator = comparator.reversed();
        }
        return serviceRepository.findAll().stream()
                .sorted(comparator)
                .map(ServiceResponseDto::from)
                .toList();
    }

    public List<ServiceResponseDto> findCheapService(Long maxPrice, int limit){
        return serviceRepository.findAll().stream()
                .filter(s -> s.getPrice() <= maxPrice)
                .sorted(Comparator.comparing(ServiceEntity::getPrice))
                .limit(limit)
                .map(ServiceResponseDto::from)
                .toList();
    }

    public List<ServiceResponseDto> searchServices(String query){
        return serviceRepository.findAll().stream()
                .filter(s -> s.getServiceName().toLowerCase().contains(query))
                .map(ServiceResponseDto::from)
                .toList();
    }

    public Map<String, List<DoctorsResponseDto>> groupDoctorsBySpecialty(){
        return doctorRepository.findAll().stream()
                .filter(s -> !s.getSpecialty().equals(""))
                .collect(Collectors.groupingBy(DoctorsEntity::getSpecialty,
                        Collectors.mapping(DoctorsResponseDto::from, Collectors.toList())));
    }

    public DoctorsResponseDto getOneDoctor(Integer id){
        return doctorRepository.findById(id).map(DoctorsResponseDto::from).orElseThrow(()-> new ResponseStatusException(NOT_FOUND, "Doctor not found"));
    }

    public DoctorsResponseDto createNewDoctor(DoctorRequestDto requestDto) {
        DoctorsEntity entity = doctorRepository.save(DoctorRequestDto.toEntity(requestDto));
        return DoctorsResponseDto.from(entity);
    }

    public DoctorsResponseDto patchDoctor(Integer id, DoctorRequestDto requestDto) {
        DoctorsEntity entity = doctorRepository.findById(id).orElseThrow(()-> new ResponseStatusException(NOT_FOUND, "Doctor not found"));
        if (requestDto.imgSrc() != null) entity.setImgSrc(requestDto.imgSrc());
        if (requestDto.name() != null) entity.setName(requestDto.name());
        if (requestDto.specialty() != null) entity.setSpecialty(requestDto.specialty());
        if (requestDto.bio() != null) entity.setBio(requestDto.bio());

        return DoctorsResponseDto.from(doctorRepository.save(entity));
    }

    public ServiceResponseDto patchService(Integer id, ServiceRequestDto requestDto) {
        ServiceEntity entity = serviceRepository.findById(id).orElseThrow(()-> new ResponseStatusException(NOT_FOUND, "Service not found"));
        if (requestDto.slug() != null) entity.setSlug(requestDto.slug());
        if (requestDto.serviceName() != null) entity.setServiceName(requestDto.serviceName());
        if (requestDto.price() != null) entity.setPrice(requestDto.price());
        if (requestDto.description() != null) entity.setDescription(requestDto.description());
        if (requestDto.longDescription() != null) entity.setLongDescription(requestDto.longDescription());
        if (requestDto.imageSrc() != null) entity.setImageSrc(requestDto.imageSrc());

        return ServiceResponseDto.from(serviceRepository.save(entity));
    }

    // ── Pages ──

    public List<PageResponseDto> listPages() {
        return pageRepository.findAll().stream().map(PageResponseDto::from).toList();
    }

    public List<PageResponseDto> listPublishedPages() {
        return pageRepository.findByStatus(PageStatus.PUBLISHED).stream().map(PageResponseDto::from).toList();
    }

    public PageResponseDto getPageBySlug(String slug) {
        return pageRepository.findBySlug(slug).map(PageResponseDto::from)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Page not found"));
    }

    public PageResponseDto createPage(PageRequestDto requestDto) {
        PageEntity saved = pageRepository.save(PageRequestDto.toEntity(requestDto));
        return PageResponseDto.from(saved);
    }

    public PageResponseDto patchPage(Integer id, PageRequestDto requestDto) {
        PageEntity entity = pageRepository.findById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Page not found"));
        if (requestDto.slug() != null) entity.setSlug(requestDto.slug());
        if (requestDto.title() != null) entity.setTitle(requestDto.title());
        if (requestDto.body() != null) entity.setBody(requestDto.body());
        if (requestDto.metaDescription() != null) entity.setMetaDescription(requestDto.metaDescription());
        if (requestDto.status() != null) entity.setStatus(PageStatus.valueOf(requestDto.status()));
        return PageResponseDto.from(pageRepository.save(entity));
    }

    public void deletePage(Integer id) {
        if (!pageRepository.existsById(id)) throw new ResponseStatusException(NOT_FOUND, "Page not found");
        pageRepository.deleteById(id);
    }

    // ── Blog Posts ──

    public List<BlogPostResponseDto> listPublishedBlogPosts() {
        return blogPostRepository.findByStatus(PageStatus.PUBLISHED).stream().map(BlogPostResponseDto::from).toList();
    }

    public List<BlogPostResponseDto> listAllBlogPosts() {
        return blogPostRepository.findAll().stream().map(BlogPostResponseDto::from).toList();
    }

    public BlogPostResponseDto getBlogPostBySlug(String slug) {
        return blogPostRepository.findBySlug(slug).map(BlogPostResponseDto::from)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Blog post not found"));
    }

    public BlogPostResponseDto createBlogPost(BlogPostRequestDto requestDto) {
        BlogPostEntity saved = blogPostRepository.save(BlogPostRequestDto.toEntity(requestDto));
        return BlogPostResponseDto.from(saved);
    }

    public BlogPostResponseDto patchBlogPost(Integer id, BlogPostRequestDto requestDto) {
        BlogPostEntity entity = blogPostRepository.findById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Blog post not found"));
        if (requestDto.slug() != null) entity.setSlug(requestDto.slug());
        if (requestDto.title() != null) entity.setTitle(requestDto.title());
        if (requestDto.body() != null) entity.setBody(requestDto.body());
        if (requestDto.metaDescription() != null) entity.setMetaDescription(requestDto.metaDescription());
        if (requestDto.keywords() != null) entity.setKeywords(requestDto.keywords());
        if (requestDto.status() != null) entity.setStatus(PageStatus.valueOf(requestDto.status()));
        return BlogPostResponseDto.from(blogPostRepository.save(entity));
    }

    public void deleteBlogPost(Integer id) {
        if (!blogPostRepository.existsById(id)) throw new ResponseStatusException(NOT_FOUND, "Blog post not found");
        blogPostRepository.deleteById(id);
    }
}
