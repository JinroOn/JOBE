package com.jinroon.jobe.domain.exploration.api;
import com.jinroon.jobe.domain.exploration.ai.*;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import com.jinroon.jobe.global.security.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Preparation queue only. No generated consultation/plan is promised by QUEUED. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/exploration/v1/contexts/{context}/jobs/{purpose}/{mutation}")
public class ExplorationJobController {
    private final ExplorationJobService service;
    private final ExplorationJson json;
    @PostMapping public Object enqueue(@AuthenticationPrincipal CustomUserDetails user,@PathVariable long context,@PathVariable String purpose,@PathVariable String mutation,HttpServletRequest request){return command(user,context,purpose,mutation,"enqueue",request);}
    @GetMapping public Object read(@AuthenticationPrincipal CustomUserDetails user,@PathVariable long context,@PathVariable String purpose,@PathVariable String mutation,HttpServletRequest request){return command(user,context,purpose,mutation,"read",request);}
    @DeleteMapping public Object cancel(@AuthenticationPrincipal CustomUserDetails user,@PathVariable long context,@PathVariable String purpose,@PathVariable String mutation,HttpServletRequest request){return command(user,context,purpose,mutation,"cancel",request);}
    private Object command(CustomUserDetails user,long context,String purpose,String mutation,String operation,HttpServletRequest request){
        if(!request.getParameterMap().isEmpty() || !purpose.matches("CONSULTATION|PLAN") || !mutation.matches("[A-Za-z0-9_-]{1,80}"))
            throw new ExplorationApiException(400,"INVALID_REQUEST","Invalid exploration job request");
        try{return json.responseValue(service.command(user.getUserId(),context,purpose,mutation,operation));}
        catch(ExplorationJobClient.Conflict e){throw new ExplorationApiException(409,"EXPLORATION_JOB_CONFLICT","Exploration job is unavailable or conflicts");}
        catch(ExplorationJobClient.Unavailable e){throw new ExplorationApiException(503,"EXPLORATION_JOB_UNAVAILABLE","Exploration job service is unavailable");}
    }
}
