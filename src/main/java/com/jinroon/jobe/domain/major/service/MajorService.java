package com.jinroon.jobe.domain.major.service;

import static com.jinroon.jobe.global.common.entity.EntityLookup.get;

import com.jinroon.jobe.global.common.entity.EntityFormMapper;
import com.jinroon.jobe.domain.major.entity.Major;
import com.jinroon.jobe.domain.major.repository.MajorRepository;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MajorService {

    private final MajorRepository majorRepository;

    public List<Major> findMajors(String category, String keyword) {
        if (category != null && !category.isBlank()) {
            return majorRepository.findByCategory(category);
        }
        if (keyword != null && !keyword.isBlank()) {
            return majorRepository.findByNameContaining(keyword);
        }
        return majorRepository.findAll();
    }

    public Major getMajor(Long majorId) {
        return get(majorRepository, majorId, "Major");
    }

    @Transactional
    public Major createMajor(Map<String, Object> values) {
        return majorRepository.save(EntityFormMapper.create(Major.class, values));
    }

    @Transactional
    public Major updateMajor(Long majorId, Map<String, Object> values) {
        Major major = getMajor(majorId);
        EntityFormMapper.apply(major, values);
        return major;
    }

    @Transactional
    public void deleteMajor(Long majorId) {
        majorRepository.delete(getMajor(majorId));
    }
}
