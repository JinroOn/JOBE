package com.jinroon.jobe.domain.major.service;

import static com.jinroon.jobe.global.common.entity.EntityLookup.get;

import com.jinroon.jobe.global.common.entity.EntityFormMapper;
import com.jinroon.jobe.global.exception.error.ErrorCode;
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
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

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
        return get(majorRepository, majorId, ErrorCode.MAJOR_NOT_FOUND);
    }

    @Transactional
    public Major createMajor(Map<String, Object> values) {
        if (!java.util.Set.of("name", "category", "difficulty", "description", "careerPaths").containsAll(values.keySet())
                || !(values.get("name") instanceof String name) || name.isBlank()
                || !(values.get("category") instanceof String category) || category.isBlank()) {
            throw new com.jinroon.jobe.global.exception.CustomException(ErrorCode.INVALID_INPUT);
        }
        if (values.get("difficulty") == null) values.put("difficulty", "mid");
        if (!java.util.Set.of("low", "mid", "high").contains(values.get("difficulty"))) {
            throw new com.jinroon.jobe.global.exception.CustomException(ErrorCode.INVALID_INPUT);
        }
        return majorRepository.save(EntityFormMapper.create(Major.class, values));
    }

    @Transactional
    public Major updateMajor(Long majorId, Map<String, Object> values) {
        Major major = getMajor(majorId);
        if (!java.util.Set.of("description", "careerPaths").containsAll(values.keySet())) {
            throw new com.jinroon.jobe.global.exception.CustomException(ErrorCode.INVALID_INPUT);
        }
        major.updateDescription((String) values.get("description"), (String) values.get("careerPaths"));
        return major;
    }

    @Transactional
    public void deleteMajor(Long majorId) {
        if (jdbc.queryForList("SELECT id FROM majors WHERE id=? FOR UPDATE", majorId).isEmpty()) {
            throw new com.jinroon.jobe.global.exception.CustomException(ErrorCode.MAJOR_NOT_FOUND);
        }
        for (String table : new String[]{"user_favorites", "result_major_scores", "exploration_major_mapping"}) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE major_id=?", Long.class, majorId) > 0) {
                throw new com.jinroon.jobe.global.exception.CustomException(ErrorCode.MAJOR_IN_USE);
            }
        }
        majorRepository.delete(getMajor(majorId));
    }
}
