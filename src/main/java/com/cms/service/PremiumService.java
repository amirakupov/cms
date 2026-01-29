package com.cms.service;

import com.cms.dto.ServiceRequestDto;
import com.cms.dto.ServiceResponseDto;
import com.cms.entity.ServiceEntity;
import com.cms.repo.ServiceRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class PremiumService {

    private final ServiceRepository serviceRepository;

    public PremiumService(ServiceRepository serviceRepository) {
        this.serviceRepository = serviceRepository;
    }

    public List<ServiceResponseDto> listServices(){
        return serviceRepository.findAll().stream().map(ServiceResponseDto::from).toList();
    }

    public ServiceResponseDto getOneService(int id){
        return serviceRepository.findById(id).map(ServiceResponseDto::from).orElseThrow(()-> new RuntimeException("Service not found"));
    }

    public ServiceResponseDto createNewService(ServiceRequestDto newService) {
        ServiceEntity saved = serviceRepository.save(ServiceRequestDto.toEntity(newService));
        return ServiceResponseDto.from(saved);
    }
}
