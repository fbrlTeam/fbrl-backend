package com.fbrl.adapter.in.web;

import com.fbrl.adapter.in.web.dto.ErrorResponse;
import com.fbrl.domain.exception.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(AccountNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleAccountNotFoundException(AccountNotFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ErrorResponse.of("ACCOUNT_NOT_FOUND", e.getMessage()));
  }

  @ExceptionHandler({
    InsufficientBalanceException.class,
    InvalidMoneyException.class,
    InvalidTransferAmountException.class
  })
  public ResponseEntity<ErrorResponse> handleDomainValidationException(RuntimeException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ErrorResponse.of("INVALID_TRANSACTION", e.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleMethodArgumentNotValidException(
      MethodArgumentNotValidException e) {
    String errorMessage = e.getBindingResult().getAllErrors().get(0).getDefaultMessage();
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ErrorResponse.of("INVALID_INPUT", errorMessage));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ErrorResponse> handleIllegalArgumentException(IllegalArgumentException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ErrorResponse.of("BAD_REQUEST", e.getMessage()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleGeneralException(Exception e) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ErrorResponse.of("INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다."));
  }

  @ExceptionHandler(DuplicateAccountNumberException.class)
  public ResponseEntity<ErrorResponse> handleDuplicateAccountNumberException(
      DuplicateAccountNumberException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ErrorResponse.of("DUPLICATE_ACCOUNT_NUMBER", e.getMessage()));
  }

  @ExceptionHandler(DuplicateIdempotencyKeyException.class)
  public ResponseEntity<ErrorResponse> handleDuplicateIdempotencyKeyException(
      DuplicateIdempotencyKeyException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ErrorResponse.of("DUPLICATE_IDEMPOTENCY_KEY", e.getMessage()));
  }

  @ExceptionHandler(AccountPersistenceException.class)
  public ResponseEntity<ErrorResponse> handleAccountPersistenceException(
      AccountPersistenceException e) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ErrorResponse.of("INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다."));
  }

  @ExceptionHandler(SagaPersistenceException.class)
  public ResponseEntity<ErrorResponse> handleSagaPersistenceException(SagaPersistenceException e) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ErrorResponse.of("INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다."));
  }

  @ExceptionHandler(ConcurrentSagaModificationException.class)
  public ResponseEntity<ErrorResponse> handleConcurrentSagaModificationException(
      ConcurrentSagaModificationException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            ErrorResponse.of(
                "CONCURRENT_SAGA_MODIFICATION", "다른 요청이 동시에 처리 중입니다. 잠시 후 다시 시도해주세요."));
  }

  @ExceptionHandler(ApprovalRequestNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleApprovalRequestNotFoundException(
      ApprovalRequestNotFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ErrorResponse.of("APPROVAL_REQUEST_NOT_FOUND", e.getMessage()));
  }

  @ExceptionHandler(SelfApprovalNotAllowedException.class)
  public ResponseEntity<ErrorResponse> handleSelfApprovalNotAllowedException(
      SelfApprovalNotAllowedException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ErrorResponse.of("SELF_APPROVAL_NOT_ALLOWED", e.getMessage()));
  }

  @ExceptionHandler(RejectionReasonRequiredException.class)
  public ResponseEntity<ErrorResponse> handleRejectionReasonRequiredException(
      RejectionReasonRequiredException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ErrorResponse.of("REJECTION_REASON_REQUIRED", e.getMessage()));
  }

  @ExceptionHandler(ApprovalNotRequiredException.class)
  public ResponseEntity<ErrorResponse> handleApprovalNotRequiredException(
      ApprovalNotRequiredException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ErrorResponse.of("APPROVAL_NOT_REQUIRED", e.getMessage()));
  }

  @ExceptionHandler(ApprovalRequiredException.class)
  public ResponseEntity<ErrorResponse> handleApprovalRequiredException(
      ApprovalRequiredException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ErrorResponse.of("APPROVAL_REQUIRED", e.getMessage()));
  }

  @ExceptionHandler(InvalidApprovalTransitionException.class)
  public ResponseEntity<ErrorResponse> handleInvalidApprovalTransitionException(
      InvalidApprovalTransitionException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ErrorResponse.of("INVALID_APPROVAL_TRANSITION", e.getMessage()));
  }

  @ExceptionHandler(ConcurrentApprovalModificationException.class)
  public ResponseEntity<ErrorResponse> handleConcurrentApprovalModificationException(
      ConcurrentApprovalModificationException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            ErrorResponse.of(
                "CONCURRENT_APPROVAL_MODIFICATION", "다른 요청이 동시에 처리 중입니다. 잠시 후 다시 시도해주세요."));
  }

  @ExceptionHandler(ApprovalPersistenceException.class)
  public ResponseEntity<ErrorResponse> handleApprovalPersistenceException(
      ApprovalPersistenceException e) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ErrorResponse.of("INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다."));
  }

  @ExceptionHandler(SuspiciousTransferException.class)
  public ResponseEntity<ErrorResponse> handleSuspiciousTransferException(
      SuspiciousTransferException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ErrorResponse.of("SUSPICIOUS_TRANSFER", e.getMessage()));
  }

  @ExceptionHandler(InvalidCredentialsException.class)
  public ResponseEntity<ErrorResponse> handleInvalidCredentialsException(
      InvalidCredentialsException e) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(ErrorResponse.of("INVALID_CREDENTIALS", e.getMessage()));
  }

  @ExceptionHandler(DuplicateAdminUsernameException.class)
  public ResponseEntity<ErrorResponse> handleDuplicateAdminUsernameException(
      DuplicateAdminUsernameException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ErrorResponse.of("DUPLICATE_ADMIN_USERNAME", e.getMessage()));
  }

  @ExceptionHandler(AdminUserPersistenceException.class)
  public ResponseEntity<ErrorResponse> handleAdminUserPersistenceException(
      AdminUserPersistenceException e) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ErrorResponse.of("INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다."));
  }
}
