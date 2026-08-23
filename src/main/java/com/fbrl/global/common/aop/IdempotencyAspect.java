package com.fbrl.global.common.aop;

import com.fbrl.application.port.out.IdempotencyRepositoryPort;
import com.fbrl.domain.exception.DuplicateIdempotencyKeyException;
import com.fbrl.global.common.annotation.CheckIdempotency;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Aspect
@Component
public class IdempotencyAspect {
  private static final String IDEMPOTENCY_HEADER = "X-Idempotency-Key";
  private static final String KEY_PREFIX = "IDEMPOTENCY:";

  private final IdempotencyRepositoryPort idempotencyRepositoryPort;

  public IdempotencyAspect(IdempotencyRepositoryPort idempotencyRepositoryPort) {
    this.idempotencyRepositoryPort = idempotencyRepositoryPort;
  }

  @Around("@annotation(checkIdempotency)")
  public Object check(ProceedingJoinPoint joinPoint, CheckIdempotency checkIdempotency)
      throws Throwable {
    ServletRequestAttributes attributes =
        (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

    if (attributes == null) {
      return joinPoint.proceed();
    }

    HttpServletRequest request = attributes.getRequest();
    String idempotencyKey = request.getHeader(IDEMPOTENCY_HEADER);

    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new IllegalArgumentException("X-Idempotency-Key 헤더가 누락되었습니다.");
    }

    String redisKey = KEY_PREFIX + idempotencyKey;
    Duration ttl = Duration.of(checkIdempotency.ttl(), checkIdempotency.timeUnit().toChronoUnit());
    boolean isFirstRequest = idempotencyRepositoryPort.setIfAbsent(redisKey, "PROCESSING", ttl);

    if (!isFirstRequest) {
      throw new DuplicateIdempotencyKeyException(
          "이미 처리되었거나 처리 중인 요청입니다. (X-Idempotency-Key: " + idempotencyKey + ")");
    }
    try {
      Object result = joinPoint.proceed();
      idempotencyRepositoryPort.setValue(redisKey, "COMPLETED", ttl);
      return result;
    } catch (Throwable e) {
      throw e;
    }
  }
}
