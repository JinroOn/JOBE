package com.jinroon.jobe.domain.exploration.activity;

import com.fasterxml.jackson.databind.JsonNode;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import java.nio.file.*;
import java.net.URI;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit management import. No approval records or automatic status promotion. */
@Service
@RequiredArgsConstructor
public class ActivityCatalogLoader {
    private final JdbcTemplate jdbc;
    private final ExplorationJson json;
    public static final Set<String> PREFERENCES=Set.of("ACTIVITY-REPORT-01","ACTIVITY-PROJECT-01","ACTIVITY-READ-01","ACTIVITY-EXPERIMENT-01","ACTIVITY-PRESENT-01","ACTIVITY-CONTENT-01");
    public record Report(String version,int activities,boolean inserted) {}

    public JsonNode validate(Path path) {
        try {JsonNode payload=json.read(Files.readString(path));validate(payload);return payload;}
        catch(java.io.IOException e){throw new IllegalArgumentException("Cannot read activity dataset",e);}
    }
    public void validate(JsonNode payload) {
        require(payload.path("schema_version").asText().equals("1.0"),"Invalid activity schema");
        code(payload,"version");code(payload,"major_mapping_version");states(payload,false);
        require(payload.path("activities").isArray() && payload.path("activities").size()>0,"Missing activities");
        Set<String> codes=new HashSet<>();
        for(JsonNode item:payload.path("activities")) {
            validateActivity(item);require(codes.add(item.path("activity_id").asText()),"Duplicate activity code");
        }
    }
    public static void validateActivity(JsonNode item) {
        code(item,"activity_id");code(item,"version");states(item,false);
        if(!item.path("major_code").isNull())code(item,"major_code");
        require(Set.of("INFORMATION","SMALL_TASK","REFLECTION_AND_NEXT_STEP").contains(item.path("kind").asText()),"Invalid activity kind");
        for(String field:List.of("title","body_markdown","worksheet_markdown","example_output","review_record_markdown","alternative"))
            require(item.path(field).isTextual()&&!item.path(field).asText().isBlank(),"Missing "+field);
        require(item.path("score_effect").asText().equals("NONE") && item.path("changes_recommendation").isBoolean()&&!item.path("changes_recommendation").asBoolean(),"Activity must not score");
        require(item.path("steps").isArray()&&item.path("steps").size()>0 && item.path("artifacts").isArray()&&item.path("artifacts").size()>0,"Missing activity content");
        require(item.path("preference_question_ids").isArray(),"Missing explicit preference mapping");
        Set<String> tags=new HashSet<>();
        for(JsonNode tag:item.path("preference_question_ids"))require(PREFERENCES.contains(tag.asText())&&tags.add(tag.asText()),"Invalid preference mapping");
        require(item.path("sources").isArray()&&item.path("sources").size()>0,"Missing sources");
        Set<String> sources=new HashSet<>();
        for(JsonNode source:item.path("sources")) {
            require(sources.add(source.path("source_id").asText())&&!source.path("source_id").asText().isBlank(),"Duplicate/missing source ID");
            require(!source.path("title").asText().isBlank()&&!source.path("publisher").asText().isBlank(),"Missing attribution");
            URI uri=URI.create(source.path("url").asText());
            require(Set.of("https","http").contains(Objects.toString(uri.getScheme(),""))&&uri.getHost()!=null&&uri.getUserInfo()==null,"Invalid source URL");
        }
    }
    private static void states(JsonNode item,boolean active) {
        require(Set.of("DRAFT","REVIEWED","ARCHIVED").contains(item.path("content_status").asText()) || active&&item.path("content_status").asText().equals("ACTIVE"),"ACTIVE requires separate activation");
        require(Set.of("PENDING","APPROVED_FOR_IMPLEMENTATION","REJECTED").contains(item.path("implementation_review_status").asText()),"Invalid implementation status");
        require(Set.of("PENDING","APPROVED","REJECTED").contains(item.path("domain_review_status").asText()),"Invalid domain status");
    }
    private static void code(JsonNode item,String field){require(item.path(field).isTextual()&&item.path(field).asText().matches("[!-~]{1,100}"),"Invalid "+field);}
    private static void require(boolean ok,String message){if(!ok)throw new IllegalArgumentException(message);}

    @Transactional
    public Report load(Path path) {return load(validate(path));}
    @Transactional
    public Report load(JsonNode payload) {
        validate(payload);
        String version=payload.path("version").asText(),hash=json.hex(payload);
        var mappings=jdbc.queryForList("SELECT metadata_json FROM exploration_catalog WHERE catalog_type='MAJOR_MAPPING' AND version=?",String.class,payload.path("major_mapping_version").asText());
        require(mappings.size()==1,"Import the matching major catalog first");
        Set<String> majors=new HashSet<>();
        jdbc.queryForList("SELECT m.major_code FROM exploration_major_mapping m JOIN exploration_catalog c ON c.id=m.catalog_id WHERE c.catalog_type='MAJOR_MAPPING' AND c.version=?",String.class,payload.path("major_mapping_version").asText()).forEach(majors::add);
        for(JsonNode item:payload.path("activities"))require(item.path("major_code").isNull()||majors.contains(item.path("major_code").asText()),"Unknown major");
        jdbc.update("INSERT INTO exploration_activity_catalog(version,major_mapping_version,content_hash,payload_json,content_status,implementation_review_status,domain_review_status) VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
                version,payload.path("major_mapping_version").asText(),hash,json.write(payload),payload.path("content_status").asText(),payload.path("implementation_review_status").asText(),payload.path("domain_review_status").asText());
        var root=jdbc.queryForMap("SELECT id,content_hash,payload_json FROM exploration_activity_catalog WHERE version=? FOR UPDATE",version);
        require(hash.equals(root.get("content_hash")),"Version/hash conflict: create a new dataset version");
        require(hash.equals(json.hex(json.read(root.get("payload_json").toString()))),"Stored activity catalog damaged");
        long id=((Number)root.get("id")).longValue();
        // Locking read sees the winner's committed children even if earlier validation opened an RR snapshot.
        var storedRows=jdbc.queryForList("SELECT * FROM exploration_activity WHERE catalog_id=? FOR UPDATE",id);
        int count=storedRows.size();
        if(count>0){
            require(count==payload.path("activities").size(),"Stored activity catalog incomplete");
            Map<String,JsonNode> expected=new HashMap<>();payload.path("activities").forEach(a->expected.put(a.path("activity_id").asText(),a));
            for(var row:storedRows) {
                JsonNode item=expected.get(row.get("activity_code").toString());
                require(item!=null && json.hex(item).equals(row.get("content_hash")) && json.hex(json.read(row.get("payload_json").toString())).equals(row.get("content_hash"))
                        && item.path("version").asText().equals(row.get("version")) && Objects.equals(item.path("major_code").textValue(),row.get("major_code")),"Stored activity damaged");
            }
            return new Report(version,count,false);
        }
        for(JsonNode item:payload.path("activities"))jdbc.update("INSERT INTO exploration_activity(catalog_id,activity_code,version,major_code,content_hash,payload_json,content_status,implementation_review_status,domain_review_status) VALUES (?,?,?,?,?,?,?,?,?)",
                id,item.path("activity_id").asText(),item.path("version").asText(),item.path("major_code").isNull()?null:item.path("major_code").asText(),json.hex(item),json.write(item),item.path("content_status").asText(),item.path("implementation_review_status").asText(),item.path("domain_review_status").asText());
        return new Report(version,payload.path("activities").size(),true);
    }
}
