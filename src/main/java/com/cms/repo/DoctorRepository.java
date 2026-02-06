package com.cms.repo;

import com.cms.entity.DoctorsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DoctorRepository extends JpaRepository<DoctorsEntity, Integer> {
}
