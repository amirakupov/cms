package com.cms.repo;

import com.cms.entity.ServiceEntity;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ServiceRepository{
 List<ServiceEntity> findAllServices(int id);
}
