package edu.czu.cinema.remote;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.czu.cinema.common.api.ResultCode;
import edu.czu.cinema.common.exception.BusinessException;

import feign.*;
import feign.codec.ErrorDecoder;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration
public class RemoteConfiguration {
    @Bean
    public RequestInterceptor internalHeaders(@Value("${cinema.internal.key}") String key) {
        if (key.length() < 32)
            throw new IllegalStateException(
                    "INTERNAL_SERVICE_KEY must contain at least 32 characters");
        return template -> {
            template.header("X-Service-Key", key);
            if (OperationContext.get() != null)
                template.header("X-Operation-Id", OperationContext.get());
            if (MDC.get("traceId") != null) template.header("X-Trace-Id", MDC.get("traceId"));
        };
    }

    @Bean
    public Retryer noRemoteWriteRetries() {
        return Retryer.NEVER_RETRY;
    }

    @Bean
    public ErrorDecoder remoteErrors(ObjectMapper json) {
        return (method, response) -> {
            try {
                if (response.body() != null && response.status() < 500) {
                    var body = json.readTree(response.body().asInputStream());
                    return new BusinessException(
                            body.path("code").asInt(ResultCode.CONFLICT.getCode()),
                            body.path("msg").asText("业务信息已变化"));
                }
            } catch (Exception ignored) {
            }
            return new BusinessException(ResultCode.REMOTE_ERROR, "业务服务暂不可用，请稍后重试");
        };
    }
}
