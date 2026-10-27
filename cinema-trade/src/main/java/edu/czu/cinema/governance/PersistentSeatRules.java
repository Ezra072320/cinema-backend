package edu.czu.cinema.governance;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.csp.sentinel.datasource.nacos.NacosDataSource;
import com.alibaba.csp.sentinel.slots.block.flow.*;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.czu.cinema.common.exception.BusinessException;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "cinema.governance.enabled", havingValue = "true")
public class PersistentSeatRules implements AutoCloseable {
  private final NacosConfigManager manager;
  private final SeatRuleCodec codec;
  private final NacosDataSource<List<FlowRule>> source;

  public record View(
      boolean enabled, Integer configuredQps, Double activeQps, boolean synchronizedRule) {}

  public PersistentSeatRules(NacosConfigManager manager, ObjectMapper json) {
    this.manager = manager;
    this.codec = new SeatRuleCodec(json);
    var p = manager.getNacosConfigProperties();
    var properties = new Properties();
    properties.setProperty(PropertyKeyConst.SERVER_ADDR, p.getServerAddr());
    if (p.getUsername() != null) properties.setProperty(PropertyKeyConst.USERNAME, p.getUsername());
    if (p.getPassword() != null) properties.setProperty(PropertyKeyConst.PASSWORD, p.getPassword());
    if (p.getNamespace() != null)
      properties.setProperty(PropertyKeyConst.NAMESPACE, p.getNamespace());
    source =
        new NacosDataSource<>(
            properties, SeatRuleCodec.GROUP, SeatRuleCodec.DATA_ID, codec::decode);
    FlowRuleManager.register2Property(source.getProperty());
  }

  public View state() {
    try {
      Integer configured =
          codec.qps(
              manager
                  .getConfigService()
                  .getConfig(SeatRuleCodec.DATA_ID, SeatRuleCodec.GROUP, 2000));
      Double active =
          FlowRuleManager.getRules().stream()
              .filter(r -> SeatRuleCodec.RESOURCE.equals(r.getResource()))
              .map(FlowRule::getCount)
              .findFirst()
              .orElse(null);
      return new View(
          true,
          configured,
          active,
          configured == null
              ? active == null
              : active != null && Double.compare(configured.doubleValue(), active) == 0);
    } catch (Exception e) {
      throw new BusinessException("规则配置暂不可用，请稍后重试");
    }
  }

  public View publish(int qps) {
    try {
      if (!manager
          .getConfigService()
          .publishConfig(SeatRuleCodec.DATA_ID, SeatRuleCodec.GROUP, codec.encode(qps), "json"))
        throw new IllegalStateException();
      return state();
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      throw new BusinessException("规则保存失败，请稍后重试");
    }
  }

  @Override
  public void close() throws Exception {
    source.close();
  }
}
