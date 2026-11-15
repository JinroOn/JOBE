package com.jinroon.jobe.domain.major.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.jinroon.jobe.domain.major.service.MajorCatalogBindingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminMajorCatalogController {
    private final MajorCatalogBindingService catalog;
    public record BindingRequest(@NotBlank String mappingVersion,@NotBlank String majorCode,@NotNull @Positive Long majorId) {}

    @GetMapping("/major-candidates/{code}")
    public JsonNode candidate(@PathVariable String code,@RequestParam String mappingVersion) {
        return catalog.candidate(mappingVersion,code);
    }
    @PostMapping("/majors/bindings")
    public MajorCatalogBindingService.Binding bind(@Valid @RequestBody BindingRequest request) {
        return catalog.bind(request.mappingVersion(),request.majorCode(),request.majorId());
    }
}
