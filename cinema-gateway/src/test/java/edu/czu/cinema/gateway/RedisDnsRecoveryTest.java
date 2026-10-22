package edu.czu.cinema.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.resource.DefaultClientResources;
import io.lettuce.core.resource.Transports;
import io.netty.resolver.dns.SingletonDnsServerAddressStreamProvider;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class RedisDnsRecoveryTest {
    @Test
    void reconnectCanDiscoverChangedIpDespiteLongDnsRecordTtl() throws Exception {
        try (var dns = new ChangingDnsServer()) {
            var resolverGroup =
                    RedisBackpressure.createResolver(
                            1, new SingletonDnsServerAddressStreamProvider(dns.address()));
            var builder =
                    DefaultClientResources.builder()
                            .ioThreadPoolSize(2)
                            .computationThreadPoolSize(2);
            new RedisBackpressure().redisDnsResources(resolverGroup).customize(builder);
            var resources = builder.build();
            try {
                var loop =
                        resources
                                .eventLoopGroupProvider()
                                .allocate(Transports.eventLoopGroupClass());
                assertThat(resources.addressResolverGroup()).isSameAs(resolverGroup);
                var resolver = resolverGroup.getResolver(loop.next());
                var host = InetSocketAddress.createUnresolved("redis.cinema.test", 6379);
                assertThat(
                                resolver.resolve(host)
                                        .get(2, TimeUnit.SECONDS)
                                        .getAddress()
                                        .getHostAddress())
                        .isEqualTo("127.0.0.1");
                dns.lastOctet.set(2);
                String actual = "";
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
                while (System.nanoTime() < deadline) {
                    actual =
                            resolver.resolve(host)
                                    .get(2, TimeUnit.SECONDS)
                                    .getAddress()
                                    .getHostAddress();
                    if (actual.equals("127.0.0.2")) break;
                    Thread.sleep(50);
                }
                assertThat(actual)
                        .as("the resolver must refresh a cached Redis address")
                        .isEqualTo("127.0.0.2");
            } finally {
                resources.shutdown(0, 2, TimeUnit.SECONDS).get(3, TimeUnit.SECONDS);
                resolverGroup.close();
            }
        }
    }

    /** Answers real UDP DNS queries with a mutable A record and a 600 second TTL. */
    private static class ChangingDnsServer implements AutoCloseable {
        final AtomicInteger lastOctet = new AtomicInteger(1);
        final DatagramSocket socket;
        final Thread worker;

        ChangingDnsServer() throws Exception {
            socket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
            worker = new Thread(this::serve, "test-redis-dns");
            worker.setDaemon(true);
            worker.start();
        }

        InetSocketAddress address() {
            return new InetSocketAddress("127.0.0.1", socket.getLocalPort());
        }

        private void serve() {
            while (!socket.isClosed()) {
                try {
                    var packet = new DatagramPacket(new byte[1024], 1024);
                    socket.receive(packet);
                    byte[] query = packet.getData();
                    int end = 12;
                    while (query[end] != 0) end += (query[end] & 255) + 1;
                    end += 5;
                    int type = (query[end - 4] & 255) * 256 + (query[end - 3] & 255);
                    var bytes = new ByteArrayOutputStream();
                    var out = new DataOutputStream(bytes);
                    out.write(query, 0, 2);
                    out.writeShort(0x8180);
                    out.writeShort(1);
                    out.writeShort(type == 1 ? 1 : 0);
                    out.writeShort(0);
                    out.writeShort(0);
                    out.write(query, 12, end - 12);
                    if (type == 1) {
                        out.writeShort(0xc00c);
                        out.writeShort(1);
                        out.writeShort(1);
                        out.writeInt(600);
                        out.writeShort(4);
                        out.write(new byte[] {127, 0, 0, (byte) lastOctet.get()});
                    }
                    byte[] reply = bytes.toByteArray();
                    socket.send(new DatagramPacket(reply, reply.length, packet.getSocketAddress()));
                } catch (IOException e) {
                    if (!socket.isClosed()) throw new IllegalStateException(e);
                }
            }
        }

        @Override
        public void close() throws InterruptedException {
            socket.close();
            worker.join(1000);
        }
    }
}
