package com.jinroon.jobe.domain.major.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MajorCatalogBindingService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    @Value("${major.dataset.majors-path:datasets/majors}")
    private String majorsPath;

    public record Binding(String mappingVersion, String majorCode, Long majorId, String sourceFile) {}

    @Transactional(readOnly=true)
    public JsonNode candidate(String version, String code) {
        return profile(row(version, code, false));
    }

    @Transactional
    public Binding bind(String version, String code, Long majorId) {
        Map<String,Object> row=row(version,code,true);
        if (java.util.Set.of("ACTIVE","ARCHIVED").contains(row.get("content_status")))
            throw new CustomException(ErrorCode.CATALOG_IMMUTABLE);
        if (row.get("major_id") != null && ((Number)row.get("major_id")).longValue()!=majorId)
            throw new CustomException(ErrorCode.MAJOR_BINDING_CONFLICT);
        var names=jdbc.queryForList("SELECT name FROM majors WHERE id=? FOR UPDATE",majorId);
        if (names.isEmpty()) throw new CustomException(ErrorCode.MAJOR_NOT_FOUND);
        JsonNode profile=profile(row);
        String name=profile.path("major_name").asText();
        String source=profile.path("source_file").asText();
        if (!code.matches("MAJOR_ROW_[0-9]+") || !name.equals(names.get(0).get("name"))) mismatch();
        String file=Path.of(source).getFileName().toString();
        String prefix="major-row-"+code.substring("MAJOR_ROW_".length())+"-";
        if (!file.startsWith(prefix) || !file.endsWith(".service.json")) mismatch();
        Path base=Path.of(majorsPath).toAbsolutePath().normalize();
        Path path=base.resolve(file).normalize();
        if (!path.startsWith(base) || !Files.isRegularFile(path)) mismatch();
        try {
            JsonNode service=mapper.readTree(Files.readString(path));
            if (!name.equals(service.path("majorName").asText())) mismatch();
        } catch (IOException e) { mismatch(); }
        Long catalogId=((Number)row.get("catalog_id")).longValue();
        Long id=((Number)row.get("id")).longValue();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM exploration_major_mapping WHERE catalog_id=? AND major_id=? AND id<>?",
                Long.class,catalogId,majorId,id)>0) throw new CustomException(ErrorCode.MAJOR_BINDING_CONFLICT);
        try {
            jdbc.update("UPDATE exploration_major_mapping SET major_id=? WHERE id=?",majorId,id);
        } catch (DataIntegrityViolationException e) { throw new CustomException(ErrorCode.MAJOR_BINDING_CONFLICT); }
        return new Binding(version,code,majorId,source);
    }

    private Map<String,Object> row(String version,String code,boolean lock) {
        var rows=jdbc.queryForList("SELECT m.id,m.catalog_id,m.major_id,m.mapping_json,c.content_status "
                + "FROM exploration_major_mapping m JOIN exploration_catalog c ON m.catalog_id=c.id "
                + "WHERE c.catalog_type='MAJOR_MAPPING' AND c.version=? AND m.major_code=?"+(lock?" FOR UPDATE":""),version,code);
        if (rows.isEmpty()) throw new CustomException(ErrorCode.NOT_FOUND);
        return rows.get(0);
    }
    private JsonNode profile(Map<String,Object> row) {
        try { return mapper.readTree((String)row.get("mapping_json")); }
        catch (IOException e) { throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR); }
    }
    private void mismatch() { throw new CustomException(ErrorCode.MAJOR_BINDING_MISMATCH); }
}
