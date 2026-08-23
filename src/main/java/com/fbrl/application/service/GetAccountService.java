package com.fbrl.application.service;

import com.fbrl.application.port.in.GetAccountUseCase;
import com.fbrl.application.port.out.AccountRepositoryPort;
import com.fbrl.domain.exception.AccountNotFoundException;
import com.fbrl.domain.model.Account;
import com.fbrl.domain.model.Money;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Primary
@Service
public class GetAccountService implements GetAccountUseCase {
  private final AccountRepositoryPort accountRepositoryPort;
  private final AccountBalanceCalculator accountBalanceCalculator;

  public GetAccountService(
      AccountRepositoryPort accountRepositoryPort,
      AccountBalanceCalculator accountBalanceCalculator) {
    this.accountRepositoryPort = accountRepositoryPort;
    this.accountBalanceCalculator = accountBalanceCalculator;
  }

  @Override
  @Transactional(readOnly = true)
  public AccountDetail getAccount(String accountNumber) {
    Account account =
        accountRepositoryPort
            .findByAccountNumber(accountNumber)
            .orElseThrow(
                () -> new AccountNotFoundException("계좌를 찾을 수 없습니다. 계좌번호: " + accountNumber));

    Money balance = accountBalanceCalculator.calculate(account);
    return new AccountDetail(account, balance);
  }
}
