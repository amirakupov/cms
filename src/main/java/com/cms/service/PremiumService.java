package com.cms.service;

import com.cms.dto.DoctorRequestDto;
import com.cms.dto.DoctorsResponseDto;
import com.cms.dto.ServiceRequestDto;
import com.cms.dto.ServiceResponseDto;
import com.cms.entity.DoctorsEntity;
import com.cms.entity.ServiceEntity;
import com.cms.repo.DoctorRepository;
import com.cms.repo.ServiceRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PremiumService {

    private final ServiceRepository serviceRepository;
    private final DoctorRepository doctorRepository;

    public PremiumService(ServiceRepository serviceRepository, DoctorRepository doctorRepository) {
        this.serviceRepository = serviceRepository;
        this.doctorRepository = doctorRepository;
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
    public DoctorsResponseDto getOneDoctor(Integer id){
        return doctorRepository.findById(id).map(DoctorsResponseDto::from).orElseThrow(()-> new RuntimeException("Doctor not found"));
    }

    public DoctorsResponseDto createNewDoctor(DoctorRequestDto requestDto) {
        DoctorsEntity entity = doctorRepository.save(DoctorRequestDto.toEntity(requestDto));
        return DoctorsResponseDto.from(entity);
    }
}
