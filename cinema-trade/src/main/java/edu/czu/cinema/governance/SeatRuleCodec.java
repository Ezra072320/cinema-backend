package edu.czu.cinema.governance;

import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.fasterxml.jackson.databind.*;
import java.util.*;

/**
 * This configuration document owns only the seat-map read resource, never order/payment admission.
 */
public class SeatRuleCodec {
  public static final String RESOURCE = "trade:seat-query",
      DATA_ID = "cinema-trade-seat-flow.json",
      GROUP = "CINEMA_GOVERNANCE";
  private final ObjectMapper json;

  public SeatRuleCodec(ObjectMapper json) {
    this.json = json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  }

  public record Definition(
      String resource,
      int grade,
      double count,
      String limitApp,
      int strategy,
      int controlBehavior) {}

  public List<FlowRule> decode(String content) {
    if (content == null || content.isBlank()) return List.of();
    try {
      var defs = json.readValue(content, Definition[].class);
      if (defs.length > 1) throw new IllegalArgumentException();
      var rules = new ArrayList<FlowRule>();
      for (var d : defs) {
        if (!RESOURCE.equals(d.resource())
            || d.grade() != 1
            || !Double.isFinite(d.count())
            || d.count() < 1
            || d.count() > 1000
            || d.count() != Math.floor(d.count())
            || !"default".equals(d.limitApp())
            || d.strategy() != 0
            || d.controlBehavior() != 0) throw new IllegalArgumentException();
        var rule = new FlowRule(RESOURCE).setGrade(1).setCount(d.count());
        rule.setLimitApp("default");
        rules.add(rule);
      }
      return rules;
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid persisted seat-query flow rule");
    }
  }

  public String encode(int qps) {
    if (qps < 1 || qps > 1000) throw new IllegalArgumentException("QPS must be 1..1000");
    try {
      return json.writeValueAsString(List.of(new Definition(RESOURCE, 1, qps, "default", 0, 0)));
    } catch (Exception e) {
      throw new IllegalStateException("Cannot encode flow rule", e);
    }
  }

  public Integer qps(String content) {
    var rules = decode(content);
    return rules.isEmpty() ? null : (int) rules.getFirst().getCount();
  }
}
