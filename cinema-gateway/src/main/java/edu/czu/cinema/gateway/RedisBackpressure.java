package edu.czu.cinema.gateway;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.resource.Transports;
import io.netty.channel.socket.SocketChannel;
import io.netty.resolver.dns.DefaultDnsCnameCache;
import io.netty.resolver.dns.DnsAddressResolverGroup;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.DnsServerAddressStreamProvider;
import io.netty.resolver.dns.DnsServerAddressStreamProviders;

import org.springframework.boot.autoconfigure.data.redis.ClientResourcesBuilderCustomizer;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisBackpressure {
    @Bean(destroyMethod = "close")
    public DnsAddressResolverGroup redisAddressResolver() {
        return createResolver(5, DnsServerAddressStreamProviders.platformDefault());
    }

    @Bean
    public ClientResourcesBuilderCustomizer redisDnsResources(DnsAddressResolverGroup resolver) {
        return builder -> builder.addressResolverGroup(resolver);
    }

    static DnsAddressResolverGroup createResolver(
            int maxTtl, DnsServerAddressStreamProvider servers) {
        // Container and HA endpoints can move before their advertised DNS TTL expires.
        return new DnsAddressResolverGroup(
                new DnsNameResolverBuilder()
                        .channelType(Transports.datagramChannelClass())
                        .socketChannelType(
                                Transports.socketChannelClass().asSubclass(SocketChannel.class))
                        .ttl(0, maxTtl)
                        .negativeTtl(1)
                        .cnameCache(new DefaultDnsCnameCache(0, maxTtl))
                        .nameServerProvider(servers));
    }

    @Bean
    public LettuceClientConfigurationBuilderCustomizer finiteRedisQueue() {
        return builder ->
                builder.clientOptions(
                        ClientOptions.builder()
                                .requestQueueSize(2048)
                                .disconnectedBehavior(
                                        ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                                .build());
    }
}
