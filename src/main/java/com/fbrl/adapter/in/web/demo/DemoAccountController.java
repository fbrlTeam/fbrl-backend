package com.fbrl.adapter.in.web.demo;

import com.fbrl.adapter.in.web.dto.AccountResponse;
import com.fbrl.adapter.in.web.dto.LedgerEntryResponse;
import com.fbrl.adapter.in.web.dto.PageResponse;
import com.fbrl.application.port.in.CreateDemoAccountUseCase;
import com.fbrl.application.port.in.GetAccountUseCase;
import com.fbrl.application.port.in.GetAccountUseCase.AccountDetail;
import com.fbrl.application.port.in.GetLedgerEntriesUseCase;
import com.fbrl.application.port.in.GetLedgerEntriesUseCase.GetLedgerEntriesQuery;
import com.fbrl.application.port.out.PagedResult;
import com.fbrl.domain.model.Account;
import com.fbrl.domain.model.LedgerEntry;
import com.fbrl.domain.model.Money;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/demo/accounts")
public class DemoAccountController {
  private final CreateDemoAccountUseCase createDemoAccountUseCase;
  private final GetAccountUseCase demoGetAccountUseCase;
  private final GetLedgerEntriesUseCase demoGetLedgerEntriesUseCase;

  public DemoAccountController(
      CreateDemoAccountUseCase createDemoAccountUseCase,
      @Qualifier("demo") GetAccountUseCase demoGetAccountUseCase,
      @Qualifier("demo") GetLedgerEntriesUseCase demoGetLedgerEntriesUseCase) {
    this.createDemoAccountUseCase = createDemoAccountUseCase;
    this.demoGetAccountUseCase = demoGetAccountUseCase;
    this.demoGetLedgerEntriesUseCase = demoGetLedgerEntriesUseCase;
  }

  @PostMapping
  public ResponseEntity<AccountResponse> createAccount() {
    Account account = createDemoAccountUseCase.createAccount();
    return ResponseEntity.created(URI.create("/api/v1/demo/accounts/" + account.getAccountNumber()))
        .body(AccountResponse.from(account, Money.ZERO));
  }

  @GetMapping("/{accountNumber}")
  public ResponseEntity<AccountResponse> getAccount(@PathVariable String accountNumber) {
    AccountDetail detail = demoGetAccountUseCase.getAccount(accountNumber);
    return ResponseEntity.ok(AccountResponse.from(detail.account(), detail.balance()));
  }

  @GetMapping("/{accountNumber}/ledger-entries")
  public ResponseEntity<PageResponse<LedgerEntryResponse>> getLedgerEntries(
      @PathVariable String accountNumber,
      @RequestParam Instant from,
      @RequestParam Instant to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    PagedResult<LedgerEntry> result =
        demoGetLedgerEntriesUseCase.getLedgerEntries(
            new GetLedgerEntriesQuery(accountNumber, from, to, page, size));
    List<LedgerEntryResponse> content =
        result.items().stream().map(LedgerEntryResponse::from).toList();
    return ResponseEntity.ok(PageResponse.of(content, result.totalElements(), page, size));
  }
}
