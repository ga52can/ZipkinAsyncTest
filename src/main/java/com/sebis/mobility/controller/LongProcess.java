package com.sebis.mobility.controller;

import com.sebis.mobility.model.Route;
import com.sebis.mobility.model.Travel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.sleuth.Span;
import org.springframework.cloud.sleuth.Tracer;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

/**
 * Created by kleehausm on 02.07.2017.
 */
@Service
public class LongProcess{

    @Autowired
    private RestTemplate restTemplate;

    @Value(("${drive-now-url}"))
    private String driveNowUrl;

    @Async
    public Future<List<Route>> getRoutes(final String baseUrl, Travel travel) throws InterruptedException{
        if(baseUrl.equals(this.driveNowUrl)) {
            Thread.sleep(5000);
        }

        //try {
            ResponseEntity<List<Route>> routes =
                    restTemplate.exchange(
                            baseUrl + "/getroutes?origin={origin}&destination={destination}",
                            HttpMethod.GET,
                            null,
                            new ParameterizedTypeReference<List<Route>>() {
                            },
                            travel.getOrigin(),
                            travel.getDestination()
                    );
            List<Route> results = routes.getStatusCode() == HttpStatus.OK ? routes.getBody() : new ArrayList<>();
            return new AsyncResult<>(results);
        //}finally{
        //    tracer.close(newSpan);
        //}
    }

}
