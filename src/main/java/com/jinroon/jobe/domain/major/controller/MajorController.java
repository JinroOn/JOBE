package com.jinroon.jobe.domain.major.controller;

import com.jinroon.jobe.domain.major.controller.api.MajorApi;
import com.jinroon.jobe.domain.major.dto.request.MajorRequest;
import com.jinroon.jobe.domain.major.entity.Major;
import com.jinroon.jobe.domain.major.service.MajorService;
import com.jinroon.jobe.global.common.dto.RequestMapMapper;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/majors")
public class MajorController implements MajorApi {

    private final MajorService majorService;

    @Override
    @GetMapping
    public List<Major> findMajors(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword
    ) {
        return majorService.findMajors(category, keyword);
    }

    @Override
    @GetMapping("/{majorId}")
    public Major getMajor(@PathVariable Long majorId) {
        return majorService.getMajor(majorId);
    }

    @Override
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Major createMajor(@Valid @RequestBody MajorRequest request) {
        return majorService.createMajor(RequestMapMapper.toMap(request));
    }

    @Override
    @PatchMapping("/{majorId}")
    public Major updateMajor(@PathVariable Long majorId, @Valid @RequestBody MajorRequest request) {
        return majorService.updateMajor(majorId, RequestMapMapper.toMap(request));
    }

    @Override
    @DeleteMapping("/{majorId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMajor(@PathVariable Long majorId) {
        majorService.deleteMajor(majorId);
    }
}
