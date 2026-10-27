package edu.czu.cinema.domain.schedule;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.alibaba.csp.sentinel.slots.block.flow.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.czu.cinema.common.exception.CapacityException;
import edu.czu.cinema.governance.*;
import java.util.List;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SeatGovernanceTest {
 private final SeatRuleCodec codec=new SeatRuleCodec(new ObjectMapper());
 @AfterEach void clear(){FlowRuleManager.loadRules(List.of());}
 @Test void serializesOnlyTheSupportedReadResourceAndRoundTripsItsThreshold(){
  String content=codec.encode(30);assertThat(codec.qps(content)).isEqualTo(30);
  assertThat(codec.decode(content).getFirst().getResource()).isEqualTo("trade:seat-query");
  assertThat(codec.decode("[]")).isEmpty();assertThat(codec.decode(null)).isEmpty();
 }
 @Test void rejectsUnknownResourcesStrategiesDecimalCountsAndExtraFields(){
  String valid=codec.encode(30);
  for(String bad:List.of(valid.replace("trade:seat-query","payment"),valid.replace("30.0","0.5"),valid.replace("30.0","1001.0"),valid.replace("\"grade\":1","\"grade\":0"),valid.replace("\"strategy\":0","\"strategy\":1"),valid.replace("\"controlBehavior\":0","\"unexpected\":0"),"not-json"))
   assertThatThrownBy(()->codec.decode(bad)).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void limiterUsesRealSentinelSlotsAndNeverCallsTheQueryOnBlockedRequests()throws Throwable{
  var guard=new SeatQueryAdmission();var call=mock(ProceedingJoinPoint.class);when(call.proceed()).thenReturn("seat-map");
  assertThat(guard.query(call)).isEqualTo("seat-map");
  FlowRuleManager.loadRules(codec.decode(codec.encode(1)));
  int blocked=0;for(int i=0;i<6;i++){try{guard.query(call);}catch(CapacityException expected){blocked++;}}
  assertThat(blocked).isGreaterThan(0);verify(call,times(7-blocked)).proceed();
 }
 @Test void thresholdEncodingIsBoundedBeforePublication(){assertThatThrownBy(()->codec.encode(0)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->codec.encode(1001)).isInstanceOf(IllegalArgumentException.class);}
}
