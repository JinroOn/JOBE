package com.jinroon.jobe.domain.exploration.sharing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import com.jinroon.jobe.domain.exploration.entity.*;
import com.jinroon.jobe.domain.exploration.enums.ExplorationEnums.ExplorationSessionStatus;
import com.jinroon.jobe.domain.exploration.repository.*;
import com.jinroon.jobe.domain.exploration.service.*;
import com.jinroon.jobe.domain.user.repository.UserRepository;
import com.jinroon.jobe.domain.user.service.UserWriteGuard;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ExplorationShareService {
    private final UserWriteGuard users;
    private final UserRepository userRepository;
    private final ExplorationSessionRepository sessions;
    private final ExplorationResultRepository results;
    private final ExplorationIdempotencyRepository requests;
    private final ExplorationResultStore resultStore;
    private final ExplorationRuntimePolicy runtime;
    private final ExplorationSharePolicy policy;
    private final SharedResultProjector projector;
    private final ShareTokenCodec tokens;
    private final ShareTime time;
    private final ExplorationJson json;
    private final JdbcTemplate jdbc;
    public record Reply(int status,JsonNode body){}
    private record Context(ExplorationResult result,ExplorationSession session,JsonNode snapshot){}

    @Transactional(readOnly=true)
    public JsonNode get(long user,long resultId){
        Context context=owned(user,resultId,false);var state=state(resultId,false);
        ObjectNode out=metadata(resultId,state==null?0:number(state,"revision"));out.put("available",policy.available(user,context.session().getRunMode()));
        if(state!=null){var links=jdbc.queryForList("SELECT * FROM exploration_share_link WHERE state_id=? ORDER BY issued_revision DESC LIMIT 1",number(state,"id"));if(!links.isEmpty()){
            var link=links.get(0);boolean enabled=link.get("revoked_at")==null && instant(link,"expires_at").isAfter(time.now()) && tokens.matches((byte[])link.get("token_nonce"),(byte[])link.get("token_hash"));
            out.put("enabled",enabled&&out.path("available").asBoolean());out.put("expiresAt",instant(link,"expires_at").toString());
            if(out.path("enabled").asBoolean())attachToken(out,link);
        }}return out;
    }

    @Transactional
    public Reply issue(long user,long resultId,String key,long expected,boolean includeNickname){
        validateKey(key);users.requireActive(user);Context context=owned(user,resultId,true);policy.require(user,context.session().getRunMode());
        var request=reserve(user,"SHARE_ISSUE",resultId,key,json.hash(Map.of("expectedShareRevision",expected,"includeNickname",includeNickname)));
        if(request.getResponseStatus()!=null)return replayIssue(request);
        var state=state(resultId,true);check(state==null?0:number(state,"revision"),expected);
        if(state==null){jdbc.update("INSERT INTO exploration_share_state(result_id,revision) VALUES (?,0)",resultId);state=state(resultId,true);}
        long stateId=number(state,"id"),revision=number(state,"revision")+1;Instant now=time.now(),expires=now.plus(Duration.ofDays(30));
        String nickname=includeNickname?userRepository.findById(user).orElseThrow(ExplorationApiException::integrity).getNickname():null;
        JsonNode summary=projector.project(context.snapshot(),nickname);if(!projector.allowed(summary))throw ExplorationApiException.integrity();
        byte[] nonce=tokens.nonce();String token=tokens.token(nonce);
        jdbc.update("UPDATE exploration_share_link SET revoked_at=? WHERE state_id=? AND revoked_at IS NULL",utc(now),stateId);
        jdbc.update("INSERT INTO exploration_share_link(state_id,issued_revision,token_nonce,token_hash,summary_json,summary_hash,result_hash,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?)",
                stateId,revision,nonce,tokens.hash(token),json.write(summary),json.hex(summary),json.hex(context.snapshot()),utc(now),utc(expires));
        jdbc.update("UPDATE exploration_share_state SET revision=? WHERE id=?",revision,stateId);
        long linkId=jdbc.queryForObject("SELECT id FROM exploration_share_link WHERE state_id=? AND issued_revision=?",Long.class,stateId,revision);
        ObjectNode stored=metadata(resultId,revision);stored.put("enabled",true);stored.put("available",true);stored.put("issuedAt",now.toString());stored.put("expiresAt",expires.toString());stored.put("linkId",linkId);
        request.storeSuccess(201,json.write(stored));return replayIssue(request);
    }

    @Transactional
    public Reply revoke(long user,long resultId,String key,long expected){
        validateKey(key);users.requireActive(user);owned(user,resultId,true); // Revocation also works when sharing is disabled.
        var request=reserve(user,"SHARE_REVOKE",resultId,key,json.hash(Map.of("expectedShareRevision",expected)));
        if(request.getResponseStatus()!=null)return new Reply(request.getResponseStatus(),json.read(request.getResponseJson()));
        var state=state(resultId,true);long revision=state==null?0:number(state,"revision");check(revision,expected);
        if(state!=null){int changed=jdbc.update("UPDATE exploration_share_link SET revoked_at=? WHERE state_id=? AND revoked_at IS NULL",utc(time.now()),number(state,"id"));if(changed>0){revision++;jdbc.update("UPDATE exploration_share_state SET revision=? WHERE id=?",revision,number(state,"id"));}}
        ObjectNode response=metadata(resultId,revision);request.storeSuccess(200,json.write(response));return new Reply(200,response);
    }

    @Transactional(readOnly=true)
    public JsonNode read(String token,String remoteAddress){
        if(!policy.localReader(remoteAddress)||!tokens.validFormat(token))throw missing();
        var links=jdbc.queryForList("SELECT l.*,s.result_id FROM exploration_share_link l JOIN exploration_share_state s ON s.id=l.state_id WHERE l.token_hash=?",tokens.hash(token));
        if(links.size()!=1)throw missing();var link=links.get(0);
        if(link.get("revoked_at")!=null||!instant(link,"expires_at").isAfter(time.now())||!tokens.matches((byte[])link.get("token_nonce"),(byte[])link.get("token_hash")))throw missing();
        var result=results.findById(number(link,"result_id")).orElseThrow(ExplorationShareService::missing);
        var session=sessions.findById(result.getSessionId()).orElseThrow(ExplorationShareService::missing);
        if(session.getStatus()!=ExplorationSessionStatus.completed||!policy.available(session.getUserId(),session.getRunMode())
                ||userRepository.findById(session.getUserId()).filter(u->u.isActive()).isEmpty())throw missing();
        JsonNode source=resultStore.verifiedSnapshot(result),summary=json.read(link.get("summary_json").toString());
        if(!json.hex(source).equals(link.get("result_hash"))||!json.hex(summary).equals(link.get("summary_hash"))||!projector.allowed(summary)
                ||!json.hex(projector.project(source,summary.path("nickname").textValue(),summary.path("summaryVersion").asText())).equals(json.hex(summary)))throw ExplorationApiException.integrity();
        ObjectNode response=summary.deepCopy();response.put("expiresAt",instant(link,"expires_at").toString());return response;
    }

    private Context owned(long user,long resultId,boolean lock){
        var result=results.findById(resultId).orElseThrow(ExplorationShareService::missing);
        var session=(lock?sessions.findByIdForUpdate(result.getSessionId()):sessions.findById(result.getSessionId())).filter(s->s.isOwnedBy(user)).orElseThrow(ExplorationShareService::missing);
        runtime.authorizeExisting(user,session.getRunMode());if(session.getStatus()!=ExplorationSessionStatus.completed)throw ExplorationApiException.integrity();
        return new Context(result,session,resultStore.verifiedSnapshot(result));
    }
    private Map<String,Object> state(long resultId,boolean lock){var rows=jdbc.queryForList("SELECT * FROM exploration_share_state WHERE result_id=?"+(lock?" FOR UPDATE":""),resultId);return rows.isEmpty()?null:rows.get(0);}
    private ObjectNode metadata(long resultId,long revision){var out=json.object();out.put("resultId",resultId);out.put("shareRevision",revision);out.put("enabled",false);out.put("runMode","DEV");out.put("testOnly",true);return out;}
    private ExplorationIdempotency reserve(long user,String operation,long resultId,String key,byte[] hash){
        var old=requests.findByUserIdAndOperationAndResourceIdAndIdempotencyKey(user,operation,resultId,key);
        if(old.isPresent()){if(!old.get().hasSameRequest(hash))throw new ExplorationApiException(409,"IDEMPOTENCY_CONFLICT","같은 멱등 키의 요청 내용이 다릅니다.");if(old.get().getResponseStatus()==null)throw ExplorationApiException.integrity();return old.get();}
        return requests.save(ExplorationIdempotency.reserve(user,operation,resultId,key,hash));
    }
    private Reply replayIssue(ExplorationIdempotency request){
        ObjectNode response=(ObjectNode)json.read(request.getResponseJson());long id=response.path("linkId").asLong();response.remove("linkId");
        var links=jdbc.queryForList("SELECT * FROM exploration_share_link WHERE id=?",id);if(links.size()!=1)throw ExplorationApiException.integrity();attachToken(response,links.get(0));return new Reply(request.getResponseStatus(),response);
    }
    private void attachToken(ObjectNode response,Map<String,Object> link){
        if(!tokens.matches((byte[])link.get("token_nonce"),(byte[])link.get("token_hash")))throw new ExplorationApiException(409,"SHARING_KEY_CHANGED","서버 키가 변경되어 새 멱등 키로 공유 링크를 발급해야 합니다.");
        String token=tokens.token((byte[])link.get("token_nonce"));response.put("shareToken",token);response.put("publicPath","/api/exploration/v1/shared-results/"+token);
    }
    private static LocalDateTime utc(Instant value){return LocalDateTime.ofInstant(value,ZoneOffset.UTC);}
    private static Instant instant(Map<String,Object> row,String key){Object value=row.get(key);return (value instanceof LocalDateTime t?t:((java.sql.Timestamp)value).toLocalDateTime()).toInstant(ZoneOffset.UTC);}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static void validateKey(String key){if(key==null||!key.matches("[!-~]{1,128}"))throw new ExplorationApiException(400,"INVALID_REQUEST","멱등 키 형식을 확인해 주세요.");}
    private static void check(long actual,long expected){if(actual!=expected)throw new ExplorationApiException(409,"STALE_REVISION","최신 공유 상태를 조회한 뒤 다시 요청해 주세요.");}
    private static ExplorationApiException missing(){return new ExplorationApiException(404,"RESOURCE_NOT_FOUND","공유 결과를 찾을 수 없습니다.");}
}
