package com.cms.service;

import com.cms.dto.ServiceResponseDto;
import com.cms.entity.ServiceEntity;
import com.cms.repo.ServiceRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PremiumService {

    private final ServiceRepository serviceRepository;

    public PremiumService(ServiceRepository serviceRepository) {
        this.serviceRepository = serviceRepository;
    }

    public List<ServiceResponseDto> listServices(){
        return serviceRepository.findAll().stream().map(ServiceResponseDto::from).toList();
    }
}
