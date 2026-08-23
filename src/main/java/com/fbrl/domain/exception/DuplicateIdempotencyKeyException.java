package com.fbrl.domain.exception;

public class DuplicateIdempotencyKeyException extends RuntimeException {
  public DuplicateIdempotencyKeyException(String message) {
    super(message);
  }
}
