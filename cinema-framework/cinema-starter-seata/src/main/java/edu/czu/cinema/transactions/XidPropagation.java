package edu.czu.cinema.transactions;

import feign.RequestInterceptor;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.apache.seata.core.context.RootContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;

/** XIDs are accepted only on authenticated internal RPC paths, never from browser requests. */
@Configuration
@ConditionalOnProperty(name = "seata.enabled", havingValue = "true")
public class XidPropagation {
  @Bean
  RequestInterceptor transactionHeader() {
    return t -> {
      if (RootContext.getXID() != null) t.header(RootContext.KEY_XID, RootContext.getXID());
    };
  }

  @Bean
  OncePerRequestFilter transactionContextFilter() {
    return new OncePerRequestFilter() {
      @Override
      protected void doFilterInternal(
          HttpServletRequest req, HttpServletResponse res, FilterChain chain)
          throws ServletException, IOException {
        String xid =
            req.getRequestURI().startsWith("/internal/")
                ? req.getHeader(RootContext.KEY_XID)
                : null;
        boolean bound =
            xid != null
                && xid.length() <= 128
                && xid.matches("[a-zA-Z0-9_.:\u002d]+")
                && RootContext.getXID() == null;
        if (bound) RootContext.bind(xid);
        try {
          chain.doFilter(req, res);
        } finally {
          if (bound) RootContext.unbind();
        }
      }
    };
  }
}
