import java.util.*;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.remoting.protocol.ResponseCode;
import org.apache.rocketmq.remoting.protocol.header.QueryConsumerOffsetRequestHeader;
import org.apache.rocketmq.remoting.protocol.header.UpdateConsumerOffsetRequestHeader;
import org.apache.rocketmq.remoting.protocol.route.TopicRouteData;
import org.apache.rocketmq.remoting.protocol.subscription.SubscriptionGroupConfig;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;

/**
 * Runs before application consumers. Initializes absent offsets only; never resets an existing one.
 */
public final class InitializeConsumerOffsets {
  static long read(DefaultMQAdminExt admin, String address, String group, String topic, int queue)
      throws Exception {
    QueryConsumerOffsetRequestHeader header = new QueryConsumerOffsetRequestHeader();
    header.setConsumerGroup(group);
    header.setTopic(topic);
    header.setQueueId(queue);
    header.setSetZeroIfNotFound(false);
    try {
      return admin
          .getDefaultMQAdminExtImpl()
          .getMqClientInstance()
          .getMQClientAPIImpl()
          .queryConsumerOffset(address, header, 5000);
    } catch (MQBrokerException missing) {
      if (missing.getResponseCode() == ResponseCode.QUERY_NOT_FOUND) return -1;
      throw missing;
    }
  }

  static void write(
      DefaultMQAdminExt admin, String address, String group, String topic, int queue, long offset)
      throws Exception {
    UpdateConsumerOffsetRequestHeader header = new UpdateConsumerOffsetRequestHeader();
    header.setConsumerGroup(group);
    header.setTopic(topic);
    header.setQueueId(queue);
    header.setCommitOffset(offset);
    admin
        .getDefaultMQAdminExtImpl()
        .getMqClientInstance()
        .getMQClientAPIImpl()
        .updateConsumerOffset(address, header, 5000);
  }

  static int[] initialize(DefaultMQAdminExt admin, String group, String topic) throws Exception {
    int initialized = 0, preserved = 0;
    TopicRouteData route = admin.examineTopicRouteInfo(topic);
    for (BrokerQueues broker : brokers(route)) {
      for (int queue = 0; queue < broker.queues; queue++) {
        if (read(admin, broker.address, group, topic, queue) >= 0) {
          preserved++;
          continue;
        }
        long minimum = admin.minOffset(new MessageQueue(topic, broker.name, queue));
        write(admin, broker.address, group, topic, queue, minimum);
        initialized++;
      }
    }
    System.out.println(group + ": initialized=" + initialized + " preserved=" + preserved);
    return new int[] {initialized, preserved};
  }

  static final class BrokerQueues {
    final String name, address;
    final int queues;

    BrokerQueues(String name, String address, int queues) {
      this.name = name;
      this.address = address;
      this.queues = queues;
    }
  }

  static List<BrokerQueues> brokers(TopicRouteData route) {
    List<BrokerQueues> result = new ArrayList<>();
    for (org.apache.rocketmq.remoting.protocol.route.QueueData queue : route.getQueueDatas()) {
      for (org.apache.rocketmq.remoting.protocol.route.BrokerData broker : route.getBrokerDatas()) {
        if (broker.getBrokerName().equals(queue.getBrokerName())) {
          String master = broker.getBrokerAddrs().get(0L);
          if (master == null)
            throw new IllegalStateException("Local broker master address missing");
          result.add(new BrokerQueues(broker.getBrokerName(), master, queue.getReadQueueNums()));
        }
      }
    }
    if (result.isEmpty()) throw new IllegalStateException("Topic has no local queues");
    return result;
  }

  static void verify(DefaultMQAdminExt admin) throws Exception {
    // Isolated technical verification group; it has no business consumer or notifications.
    String group = "cinema-offset-bootstrap-probe-" + UUID.randomUUID().toString().substring(0, 8);
    String topic = "cinema-movie-events";
    for (BrokerQueues broker : brokers(admin.examineTopicRouteInfo(topic))) {
      SubscriptionGroupConfig config = new SubscriptionGroupConfig();
      config.setGroupName(group);
      admin.createAndUpdateSubscriptionGroupConfig(broker.address, config);
    }
    if (initialize(admin, group, topic)[0] == 0)
      throw new AssertionError("Probe must start without progress");
    Map<String, Long> saved = new HashMap<>();
    for (BrokerQueues broker : brokers(admin.examineTopicRouteInfo(topic))) {
      for (int q = 0; q < broker.queues; q++) {
        long minimum = admin.minOffset(new MessageQueue(topic, broker.name, q));
        if (read(admin, broker.address, group, topic, q) != minimum)
          throw new AssertionError("Cold offset must start at retained minimum");
        long next = admin.maxOffset(new MessageQueue(topic, broker.name, q));
        write(admin, broker.address, group, topic, q, next);
        saved.put(broker.name + ":" + q, next);
      }
    }
    if (initialize(admin, group, topic)[0] != 0)
      throw new AssertionError("Existing offsets were overwritten");
    for (BrokerQueues broker : brokers(admin.examineTopicRouteInfo(topic))) {
      for (int q = 0; q < broker.queues; q++) {
        if (read(admin, broker.address, group, topic, q) != saved.get(broker.name + ":" + q))
          throw new AssertionError("Offset moved backwards");
      }
    }
    System.out.println(
        "PASS isolated cold-offset initialization and existing-progress preservation");
  }

  public static void main(String[] args) throws Exception {
    if (args.length > 1 || args.length == 1 && !"--verify".equals(args[0]))
      throw new IllegalArgumentException("Unsupported operation");
    DefaultMQAdminExt admin = new DefaultMQAdminExt();
    admin.setNamesrvAddr(System.getenv("NAMESRV_ADDR"));
    admin.setInstanceName("cinema-offset-initializer");
    try {
      admin.start();
      if (args.length == 1) {
        verify(admin);
        return;
      }
      initialize(admin, "cinema-notify-orders", "cinema-order-events");
      initialize(admin, "cinema-trade-timeouts", "cinema-order-timeouts");
      initialize(admin, "cinema-search-movies", "cinema-movie-events");
      initialize(admin, "cinema-benefit-orders", "cinema-order-events");
    } finally {
      admin.shutdown();
    }
  }
}
