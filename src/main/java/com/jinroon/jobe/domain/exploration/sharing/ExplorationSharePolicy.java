package com.jinroon.jobe.domain.exploration.sharing;

import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import com.jinroon.jobe.domain.exploration.enums.ExplorationEnums.ExplorationRunMode;
import com.jinroon.jobe.domain.exploration.service.ExplorationRuntimePolicy;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** There is no public/pilot approval workflow yet. Anonymous DEV reads are direct loopback QA only. */
@Component
public class ExplorationSharePolicy {
    private final ExplorationRuntimePolicy runtime;
    private final boolean enabled;
    public ExplorationSharePolicy(ExplorationRuntimePolicy runtime,@Value("${exploration.sharing.enabled:false}")boolean enabled){this.runtime=runtime;this.enabled=enabled;}
    public boolean available(Long owner,ExplorationRunMode mode){
        if(!enabled||mode!=ExplorationRunMode.DEV)return false;
        try{runtime.authorizeExisting(owner,mode);return true;}catch(ExplorationApiException e){return false;}
    }
    public void require(Long owner,ExplorationRunMode mode){if(!available(owner,mode))throw new ExplorationApiException(503,"SHARING_UNAVAILABLE","공유가 허용된 실행 범위가 아닙니다.");}
    public boolean localReader(String remote){return enabled && Set.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(remote==null?"":remote);}
}
