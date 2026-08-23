package com.fbrl.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

import com.fbrl.application.port.in.ApproveTransferUseCase.ApprovalDecisionResult;
import com.fbrl.application.port.in.ApproveTransferUseCase.ApproveTransferCommand;
import com.fbrl.application.port.in.TransferMoneyCommand;
import com.fbrl.application.port.in.TransferMoneyUseCase;
import com.fbrl.application.port.out.LoadApprovalRequestPort;
import com.fbrl.application.port.out.SaveApprovalRequestPort;
import com.fbrl.domain.exception.ApprovalRequestNotFoundException;
import com.fbrl.domain.exception.SelfApprovalNotAllowedException;
import com.fbrl.domain.model.ApprovalStatus;
import com.fbrl.domain.model.Money;
import com.fbrl.domain.model.TransferApprovalRequest;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApproveTransferService 단위 테스트")
class ApproveTransferServiceTest {

  @Mock private LoadApprovalRequestPort loadApprovalRequestPort;
  @Mock private SaveApprovalRequestPort saveApprovalRequestPort;
  @Mock private TransferMoneyUseCase transferMoneyUseCase;

  private ApproveTransferService sut;

  @Test
  @DisplayName("정상 승인 시 상태를 APPROVED로 저장하고 실제 이체를 트리거한다.")
  void approve_success_triggersTransfer() {
    sut =
        new ApproveTransferService(
            loadApprovalRequestPort, saveApprovalRequestPort, transferMoneyUseCase);

    TransferApprovalRequest request =
        TransferApprovalRequest.request("maker-1", "111-111", "222-222", Money.wons(20_000_000));
    given(loadApprovalRequestPort.loadByRequestId(request.getRequestId()))
        .willReturn(Optional.of(request));
    given(saveApprovalRequestPort.save(any())).willAnswer(invocation -> invocation.getArgument(0));

    ApprovalDecisionResult result =
        sut.approve(new ApproveTransferCommand(request.getRequestId(), "checker-1"));

    assertThat(result.status()).isEqualTo(ApprovalStatus.APPROVED);
    verify(transferMoneyUseCase)
        .transfer(new TransferMoneyCommand("111-111", "222-222", Money.wons(20_000_000)));
  }

  @Test
  @DisplayName("존재하지 않는 requestId면 ApprovalRequestNotFoundException을 던진다.")
  void approve_requestNotFound_throwsException() {
    sut =
        new ApproveTransferService(
            loadApprovalRequestPort, saveApprovalRequestPort, transferMoneyUseCase);

    given(loadApprovalRequestPort.loadByRequestId("missing")).willReturn(Optional.empty());

    assertThatThrownBy(() -> sut.approve(new ApproveTransferCommand("missing", "checker-1")))
        .isInstanceOf(ApprovalRequestNotFoundException.class);
  }

  @Test
  @DisplayName("기안자 본인이 승인을 시도하면 SelfApprovalNotAllowedException을 던지고 이체를 트리거하지 않는다.")
  void approve_bySameMaker_throwsExceptionAndNeverTransfers() {
    sut =
        new ApproveTransferService(
            loadApprovalRequestPort, saveApprovalRequestPort, transferMoneyUseCase);

    TransferApprovalRequest request =
        TransferApprovalRequest.request("maker-1", "111-111", "222-222", Money.wons(20_000_000));
    given(loadApprovalRequestPort.loadByRequestId(request.getRequestId()))
        .willReturn(Optional.of(request));

    assertThatThrownBy(
            () -> sut.approve(new ApproveTransferCommand(request.getRequestId(), "maker-1")))
        .isInstanceOf(SelfApprovalNotAllowedException.class);

    verify(transferMoneyUseCase, org.mockito.Mockito.never()).transfer(any());
  }

  @Test
  @DisplayName("이체 실행 중 도메인 예외가 아닌 인프라 예외가 발생하면 executionFailureReason에 원본 메시지 대신 고정 문구를 저장한다.")
  void approve_infraExceptionDuringTransfer_storesGenericExecutionFailureReason() {
    sut =
        new ApproveTransferService(
            loadApprovalRequestPort, saveApprovalRequestPort, transferMoneyUseCase);

    TransferApprovalRequest request =
        TransferApprovalRequest.request("maker-1", "111-111", "222-222", Money.wons(20_000_000));
    given(loadApprovalRequestPort.loadByRequestId(request.getRequestId()))
        .willReturn(Optional.of(request));
    given(saveApprovalRequestPort.save(any())).willAnswer(invocation -> invocation.getArgument(0));
    willThrow(new DataAccessResourceFailureException("Connection refused by database"))
        .given(transferMoneyUseCase)
        .transfer(any());

    assertThatThrownBy(
            () -> sut.approve(new ApproveTransferCommand(request.getRequestId(), "checker-1")))
        .isInstanceOf(DataAccessResourceFailureException.class);

    assertThat(request.getExecutionFailureReason()).isEqualTo("내부 오류로 이체가 실행되지 않았습니다.");
  }
}
