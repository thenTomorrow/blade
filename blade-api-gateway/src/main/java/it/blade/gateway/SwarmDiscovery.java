package it.blade.gateway;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Fornisce al bilanciatore gli IP dei singoli task Swarm, non il VIP del servizio. */
class SwarmDiscovery {
    private static final Logger log = LoggerFactory.getLogger(SwarmDiscovery.class);

    /** Configura la risoluzione DNS per il servizio associato a questo client di bilanciamento. */
    @Bean
    ServiceInstanceListSupplier serviceInstances(Environment environment) {
        String serviceId = environment.getRequiredProperty(LoadBalancerClientFactory.PROPERTY_NAME);
        String host = environment.getRequiredProperty("blade.discovery.dns-prefix") + serviceId;
        int port = environment.getRequiredProperty("blade.discovery.port", Integer.class);
        return new ServiceInstanceListSupplier() {
            /** Identifica il servizio logico a cui appartengono le istanze risolte. */
            @Override
            public String getServiceId() {
                return serviceId;
            }

            /**
             * Risolve gli IP dei task fuori dall'event loop; se il nome non esiste,
             * registra un avviso e restituisce una lista vuota al bilanciatore.
             */
            @Override
            public Flux<List<ServiceInstance>> get() {
                return Mono.<List<ServiceInstance>>fromCallable(() -> Arrays.stream(InetAddress.getAllByName(host))
                        .map(InetAddress::getHostAddress)
                        .sorted()
                        .<ServiceInstance>map(ip -> new DefaultServiceInstance(serviceId + "-" + ip,
                                serviceId, ip, port, false)).toList())
                        .subscribeOn(Schedulers.boundedElastic())
                        .onErrorResume(UnknownHostException.class, error -> {
                            log.warn("No DNS records for Swarm service {}", host);
                            return Mono.just(List.of());
                        }).flux();
            }
        };
    }
}
