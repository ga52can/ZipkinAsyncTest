package com.sebis.mobility.controller;

import com.sebis.mobility.model.Route;
import com.sebis.mobility.model.Travel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.ShellProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
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
import java.util.concurrent.CompletableFuture;

/**
 * Created by kleehausm on 05.07.2017.
 */
@Service
public class AsyncRestService {

    private static final Logger logger = LoggerFactory.getLogger(AsyncRestService.class);

    private final RestTemplate restTemplate;

    public AsyncRestService(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder.build();
    }

    @Async
    public CompletableFuture getRoutes(final String baseUrl, Travel travel){
        logger.info("Looking up " + baseUrl);
        List<Route> results = new ArrayList<>();

        long start = System.currentTimeMillis();
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
        results = routes.getStatusCode() == HttpStatus.OK ? routes.getBody():new ArrayList<>();

        logger.info(baseUrl + " completed at: " + (System.currentTimeMillis() - start));

        return CompletableFuture.completedFuture(results);

    }

}
