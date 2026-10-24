package com.sanica.ledger.api;

import com.sanica.ledger.domain.Account;
import com.sanica.ledger.domain.Currencies;
import com.sanica.ledger.domain.Entry;
import com.sanica.ledger.domain.LedgerTransaction;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request and response bodies. Amounts are returned as strings so clients never parse money as floats. */
public final class Dtos {

    private Dtos() {
    }

    public record CreateAccountRequest(String ownerId, String currency) {
    }

    public record DepositRequest(UUID accountId, BigDecimal amount) {
    }

    public record TransferRequest(UUID fromAccountId, UUID toAccountId, BigDecimal amount) {
    }

    public record AccountResponse(UUID id, String ownerId, String currency, String kind, String balance,
                                  OffsetDateTime createdAt) {

        static AccountResponse from(Account account) {
            return new AccountResponse(account.id(), account.ownerId(), account.currency(), account.kind().name(),
                    Currencies.format(account.balance(), account.currency()), account.createdAt());
        }
    }

    public record EntryResponse(UUID id, UUID transactionId, UUID accountId, String amount, String currency,
                                OffsetDateTime createdAt) {

        static EntryResponse from(Entry entry) {
            return new EntryResponse(entry.id(), entry.transactionId(), entry.accountId(),
                    Currencies.format(entry.amount(), entry.currency()), entry.currency(), entry.createdAt());
        }
    }

    public record TransactionResponse(UUID id, String type, UUID sourceAccountId, UUID destinationAccountId,
                                      String sourceAmount, String sourceCurrency, String destinationAmount,
                                      String destinationCurrency, String fxRate, OffsetDateTime createdAt,
                                      List<EntryResponse> entries) {

        static TransactionResponse from(LedgerTransaction tx) {
            return new TransactionResponse(tx.id(), tx.type().name(), tx.sourceAccountId(),
                    tx.destinationAccountId(), Currencies.format(tx.sourceAmount(), tx.sourceCurrency()),
                    tx.sourceCurrency(), Currencies.format(tx.destinationAmount(), tx.destinationCurrency()),
                    tx.destinationCurrency(), tx.fxRate().toPlainString(), tx.createdAt(),
                    tx.entries().stream().map(EntryResponse::from).toList());
        }
    }

    public record ReconciliationResponse(boolean balanced, Map<String, String> netByCurrency,
                                         List<UUID> mismatchedAccounts) {
    }

    public record ErrorResponse(String error, String message) {
    }
}
