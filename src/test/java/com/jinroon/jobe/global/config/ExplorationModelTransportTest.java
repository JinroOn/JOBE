package com.jinroon.jobe.global.config;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class ExplorationModelTransportTest {
    @Test void aSlowModelReplyDoesNotRequireExtendingTheLegacyTransport()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            try{
                Thread.sleep(150);exchange.sendResponseHeaders(200,2);exchange.getResponseBody().write(new byte[]{'o','k'});
            }catch(InterruptedException e){Thread.currentThread().interrupt();}
            catch(java.io.IOException ignored){/* The intentionally timed-out legacy client disconnected. */}
            finally{exchange.close();}
        });
        server.start();
        try{
            String url="http://127.0.0.1:"+server.getAddress().getPort()+"/";
            var config=new RestTemplateConfig();
            assertThatThrownBy(()->config.restTemplate(1000,40).getForObject(url,String.class)).isInstanceOf(ResourceAccessException.class);
            assertThat(config.explorationModelRestTemplate(2000,40000).getForObject(url,String.class)).isEqualTo("ok");
        }finally{server.stop(0);}
    }
    @Test void timeBudgetsCannotConsumeTheWholeSixtySecondLease(){
        var config=new RestTemplateConfig();
        for(int[] limits:new int[][]{{0,40000},{2001,40000},{2000,10000},{2000,60000}})
            assertThatThrownBy(()->config.explorationModelRestTemplate(limits[0],limits[1])).isInstanceOf(IllegalArgumentException.class);
    }
}
