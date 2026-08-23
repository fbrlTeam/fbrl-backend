package com.fbrl.application.service;

import com.fbrl.application.port.in.GetAccountUseCase;
import com.fbrl.application.port.out.AccountRepositoryPort;
import com.fbrl.domain.exception.AccountNotFoundException;
import com.fbrl.domain.model.Account;
import com.fbrl.domain.model.Money;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Qualifier("demo")
public class DemoGetAccountService implements GetAccountUseCase {

  private final AccountRepositoryPort demoAccountRepositoryPort;
  private final DemoAccountBalanceCalculator demoAccountBalanceCalculator;

  public DemoGetAccountService(
      @Qualifier("demo") AccountRepositoryPort demoAccountRepositoryPort,
      DemoAccountBalanceCalculator demoAccountBalanceCalculator) {
    this.demoAccountRepositoryPort = demoAccountRepositoryPort;
    this.demoAccountBalanceCalculator = demoAccountBalanceCalculator;
  }

  @Override
  @Transactional(value = "demoTransactionManager", readOnly = true)
  public AccountDetail getAccount(String accountNumber) {
    Account account =
        demoAccountRepositoryPort
            .findByAccountNumber(accountNumber)
            .orElseThrow(
                () -> new AccountNotFoundException("계좌를 찾을 수 없습니다. 계좌번호: " + accountNumber));

    Money balance = demoAccountBalanceCalculator.calculate(account);
    return new AccountDetail(account, balance);
  }
}
