package com.jinroon.jobe.domain.exploration.ai;

import com.jinroon.jobe.domain.user.repository.UserRepository;
import com.jinroon.jobe.domain.user.enums.UserEnums.UserStatus;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ExplorationJobOwnerService {
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    @Transactional
    public String ownedKey(long user) {
        var row=users.findByIdForUpdate(user).orElseThrow(ExplorationJobClient.Unavailable::new);
        if(row.getStatus()!=UserStatus.active) throw new ExplorationJobClient.Unavailable();
        jdbc.update("INSERT IGNORE INTO exploration_ai_owner(user_id,owner_key) VALUES (?,?)",user,UUID.randomUUID().toString());
        return jdbc.queryForObject("SELECT owner_key FROM exploration_ai_owner WHERE user_id=?",String.class,user);
    }
}
