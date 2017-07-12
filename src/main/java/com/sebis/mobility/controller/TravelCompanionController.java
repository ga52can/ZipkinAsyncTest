package com.sebis.mobility.controller;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.discovery.converters.Auto;
import com.sebis.mobility.model.City;
import com.sebis.mobility.model.Route;
import com.sebis.mobility.model.RouteResults;
import com.sebis.mobility.model.Travel;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.cloud.sleuth.SpanNamer;
import org.springframework.cloud.sleuth.TraceKeys;
import org.springframework.cloud.sleuth.Tracer;
import org.springframework.cloud.sleuth.instrument.async.TraceableExecutorService;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.constraints.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Future;

@RefreshScope
@RestController
public class TravelCompanionController {

    @Value("${name}")
    private String serviceName;

    @Value("${maps-url}")
    private String mapsUrl;
    @Value(("${drive-now-url}"))
    private String driveNowUrl;
    @Value(("${deutsche-bahn-url}"))
    private String deutscheBahnUrl;

    private final Tracer tracer;
    private final TraceKeys traceKeys;
    private final SpanNamer spanNamer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private AsyncRestService asyncRestService;

    @Autowired
    private LongProcess process;

    @Autowired
    TravelCompanionController(Tracer tracer, TraceKeys traceKeys, SpanNamer spanNamer) {

        this.tracer = tracer;
        this.traceKeys = traceKeys;
        this.spanNamer = spanNamer;
    }

    private final static String DRIVE_NOW = "drive-now";
    private final static String DEUTSCHE_BAHN = "deutsche-bahn";
    private static Logger logger = Logger.getLogger(TravelCompanionController.class.getName());


    @RequestMapping(value = {"/routes", "/travelcompanion-mobility-service/routes"}, method = RequestMethod.GET)
    @ResponseBody
    public ModelAndView showCities(HttpServletRequest request) {
        ModelAndView model = new ModelAndView("route");
        List<City> cities =
                jdbcTemplate.query(
                        "SELECT city_id, city_name, latitude, longitude FROM cities",
                        (resultSet, i) -> {
                            int id = resultSet.getInt(1);
                            String cityName = resultSet.getString(2);
                            double latitude = resultSet.getDouble(3);
                            double longitude = resultSet.getDouble(4);
                            return new City(id, cityName, latitude, longitude);
                        });
        model.addObject("cities", cities);
        model.addObject("travel", new Travel());
        return model;
    }

    @Async
    public List<Route> getRoutes(final String baseUrl, Travel travel) {
        ResponseEntity<List<Route>> routes =
                restTemplate.exchange(
                        baseUrl + "/getroutes?origin={origin}&destination={destination}",
                        HttpMethod.GET,
                        null,
                        new ParameterizedTypeReference<List<Route>>(){},
                        travel.getOrigin(),
                        travel.getDestination()
                );
        List<Route> results = routes.getStatusCode() == HttpStatus.OK ? routes.getBody() : new ArrayList<>();
        return results;
    }

    @PostMapping(value = {"/getroutes", "/travelcompanion-mobility-service/getroutes"}, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    @CrossOrigin
    public RouteResults getRoutes(@ModelAttribute Travel travel, HttpServletResponse response) throws IOException, InterruptedException{
        RouteResults routeResults = new RouteResults();

        ResponseEntity<String> mapsResponse =
                restTemplate.getForEntity(
                        mapsUrl + "/distance?origin={origin}&destination={destination}",
                        String.class, travel.getOrigin(), travel.getDestination());
        if (mapsResponse.getStatusCode() == HttpStatus.OK) {
            ObjectMapper mapper = new ObjectMapper();
            try {
                HashMap<String, Double> map = mapper.readValue(mapsResponse.getBody(), HashMap.class);
                routeResults.setDistance(map.get("result"));
            } catch (JsonGenerationException e) {
                e.printStackTrace();
            }

        }

        //logger.info("TraceID for " + tracer.getCurrentSpan().getName() + ": " + tracer.getCurrentSpan().getTraceId());

        CompletableFuture<List<Route>> driveNowProcess = CompletableFuture.supplyAsync(() -> {
            ResponseEntity<List<Route>> routes =
                    restTemplate.exchange(
                            driveNowUrl + "/getroutes?origin={origin}&destination={destination}",
                            HttpMethod.GET,
                            null,
                            new ParameterizedTypeReference<List<Route>>() {
                            },
                            travel.getOrigin(),
                            travel.getDestination()
                    );
            return routes.getStatusCode() == HttpStatus.OK ? routes.getBody():new ArrayList<>();
        }, new TraceableExecutorService(Executors.newFixedThreadPool(5),
                tracer, traceKeys, spanNamer, "driveNowRoutes"));

        //logger.info("TraceID for " + tracer.getCurrentSpan().getName() + ": " + tracer.getCurrentSpan().getTraceId());
        CompletableFuture<List<Route>> dbProcess = CompletableFuture.supplyAsync(() -> {
            ResponseEntity<List<Route>> routes =
                    restTemplate.exchange(
                            deutscheBahnUrl + "/getroutes?origin={origin}&destination={destination}",
                            HttpMethod.GET,
                            null,
                            new ParameterizedTypeReference<List<Route>>() {
                            },
                            travel.getOrigin(),
                            travel.getDestination()
                    );
            return routes.getStatusCode() == HttpStatus.OK ? routes.getBody():new ArrayList<>();
        }, new TraceableExecutorService(Executors.newFixedThreadPool(5),
                tracer, traceKeys, spanNamer, "dbRoutes"));

        long start = System.currentTimeMillis();
        Future<List<Route>> dbProcessFuture = process.getRoutes(deutscheBahnUrl, travel);
        while (true) {
            if (dbProcess.isDone()) {
                logger.info("DB completed at: " + (System.currentTimeMillis() - start));
                break;
            }
            Thread.sleep(1);
        }

        try {
            routeResults.addRoutes(driveNowProcess.get());
            routeResults.addRoutes(dbProcess.get());
        } catch (InterruptedException | ExecutionException e) {
            logger.error("Error getting routes from partner services");
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
        return routeResults;
    }

}