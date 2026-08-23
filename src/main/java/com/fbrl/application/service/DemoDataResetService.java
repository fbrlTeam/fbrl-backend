package com.fbrl.application.service;

import com.fbrl.application.port.out.AccountRepositoryPort;
import com.fbrl.application.port.out.DemoBatchJobHistoryPort;
import com.fbrl.application.port.out.DemoDataWipePort;
import com.fbrl.application.port.out.DemoOutboxTamperPort;
import com.fbrl.application.port.out.DemoResetStatusPort;
import com.fbrl.application.port.out.PayloadSerializerPort;
import com.fbrl.application.port.out.SaveLedgerEntryPort;
import com.fbrl.application.port.out.SaveOutboxEventPort;
import com.fbrl.domain.event.TransferCompletedEvent;
import com.fbrl.domain.model.Account;
import com.fbrl.domain.model.LedgerEntry;
import com.fbrl.domain.model.LedgerEntry.LedgerEntryPair;
import com.fbrl.domain.model.Money;
import com.fbrl.domain.model.OutboxEvent;
import com.fbrl.domain.model.SystemAccounts;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemoDataResetService {

  public static final String SEED_SENDER_ACCOUNT_NUMBER = "DEMO-AUDIT-DEMO";
  public static final String SEED_RECEIVER_ACCOUNT_NUMBER = "DEMO-AUDIT-COUNTERPARTY";

  private static final List<String> DEMO_JOB_NAMES =
      List.of("demoEodSettlementJob", "demoReconciliationJob");
  private static final int NORMAL_OUTBOX_EVENT_COUNT = 3;
  private static final Money SEED_BALANCE = Money.wons(1_000_000);
  private static final Money SEED_TRANSFER_AMOUNT = Money.wons(100_000);
  private static final String TAMPERED_PAYLOAD =
      "{\"tampered\":true,\"note\":\"demo reset seed corruption\"}";

  private final DemoDataWipePort demoDataWipePort;
  private final DemoBatchJobHistoryPort demoBatchJobHistoryPort;
  private final DemoResetStatusPort demoResetStatusPort;
  private final DemoOutboxTamperPort demoOutboxTamperPort;
  private final AccountRepositoryPort demoAccountRepositoryPort;
  private final SaveLedgerEntryPort demoSaveLedgerEntryPort;
  private final SaveOutboxEventPort demoSaveOutboxEventPort;
  private final PayloadSerializerPort payloadSerializerPort;

  public DemoDataResetService(
      DemoDataWipePort demoDataWipePort,
      DemoBatchJobHistoryPort demoBatchJobHistoryPort,
      DemoResetStatusPort demoResetStatusPort,
      DemoOutboxTamperPort demoOutboxTamperPort,
      @Qualifier("demo") AccountRepositoryPort demoAccountRepositoryPort,
      @Qualifier("demo") SaveLedgerEntryPort demoSaveLedgerEntryPort,
      @Qualifier("demo") SaveOutboxEventPort demoSaveOutboxEventPort,
      PayloadSerializerPort payloadSerializerPort) {
    this.demoDataWipePort = demoDataWipePort;
    this.demoBatchJobHistoryPort = demoBatchJobHistoryPort;
    this.demoResetStatusPort = demoResetStatusPort;
    this.demoOutboxTamperPort = demoOutboxTamperPort;
    this.demoAccountRepositoryPort = demoAccountRepositoryPort;
    this.demoSaveLedgerEntryPort = demoSaveLedgerEntryPort;
    this.demoSaveOutboxEventPort = demoSaveOutboxEventPort;
    this.payloadSerializerPort = payloadSerializerPort;
  }

  @Transactional("demoTransactionManager")
  public void reset() {
    demoDataWipePort.wipeAll();
    demoBatchJobHistoryPort.deleteByJobNames(DEMO_JOB_NAMES);

    Instant now = Instant.now();
    demoAccountRepositoryPort.save(Account.create(SEED_SENDER_ACCOUNT_NUMBER));
    demoAccountRepositoryPort.save(Account.create(SEED_RECEIVER_ACCOUNT_NUMBER));

    LedgerEntryPair openingBalancePair =
        LedgerEntry.transferPair(
            SystemAccounts.OPENING_BALANCE_SOURCE,
            SEED_SENDER_ACCOUNT_NUMBER,
            SEED_BALANCE,
            "DEMO_RESET_SEED",
            now);
    demoSaveLedgerEntryPort.saveAll(openingBalancePair.entries());

    LedgerEntryPair transferPair =
        LedgerEntry.transferPair(
            SEED_SENDER_ACCOUNT_NUMBER,
            SEED_RECEIVER_ACCOUNT_NUMBER,
            SEED_TRANSFER_AMOUNT,
            "DEMO_RESET_TRANSFER",
            now);
    demoSaveLedgerEntryPort.saveAll(transferPair.entries());

    OutboxEvent lastSavedEvent = null;
    for (int i = 0; i < NORMAL_OUTBOX_EVENT_COUNT; i++) {
      TransferCompletedEvent event =
          new TransferCompletedEvent(
              SEED_SENDER_ACCOUNT_NUMBER, SEED_RECEIVER_ACCOUNT_NUMBER, SEED_TRANSFER_AMOUNT, now);
      String payload = payloadSerializerPort.serialize(event);
      OutboxEvent outboxEvent =
          OutboxEvent.create("Account", SEED_SENDER_ACCOUNT_NUMBER, "TRANSFER_COMPLETED", payload);
      lastSavedEvent = demoSaveOutboxEventPort.save(outboxEvent);
    }

    demoOutboxTamperPort.tamperPayload(lastSavedEvent.getId(), TAMPERED_PAYLOAD);

    demoResetStatusPort.recordResetCompleted(Instant.now());
  }
}
