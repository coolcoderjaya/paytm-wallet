package com.paytm.wallet;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.domain.TransferCommand;
import com.paytm.wallet.domain.TransferStatus;
import com.paytm.wallet.domain.Wallet;
import com.paytm.wallet.exception.IdempotencyConflictException;
import com.paytm.wallet.security.TokenHasher;
import com.paytm.wallet.service.TransferService;
import com.paytm.wallet.service.WalletService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class TransferConcurrencyTest extends PostgresIntegrationTestSupport {

    @Autowired WalletService walletService;
    @Autowired TransferService transferService;
    @Autowired TokenHasher tokenHasher;
    @Autowired JdbcTemplate jdbcTemplate;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE transfers, wallets CASCADE");
        executor = Executors.newFixedThreadPool(40);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void idempotentRetryStormAppliesMovementExactlyOnce() throws Exception {
        TestWallet a = createWallet("a");
        TestWallet b = createWallet("b");
        long amount = 1_000;
        String idempotencyKey = "storm-" + UUID.randomUUID();
        TransferCommand command = new TransferCommand(a.wallet.id(), b.wallet.id(), amount, idempotencyKey);

        int workers = 30;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Transfer>> futures = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                return transferService.transfer(a.userKey, command);
            }));
        }
        start.countDown();

        List<Transfer> responses = new ArrayList<>();
        for (Future<Transfer> future : futures) {
            responses.add(future.get(30, TimeUnit.SECONDS));
        }

        Set<UUID> transferIds = new HashSet<>();
        Set<TransferStatus> statuses = new HashSet<>();
        responses.forEach(t -> {
            transferIds.add(t.id());
            statuses.add(t.status());
        });

        Wallet aAfter = walletService.getOwnedWallet(a.userKey, a.wallet.id());
        Wallet bAfter = walletService.getOwnedWallet(b.userKey, b.wallet.id());
        Integer transferRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transfers WHERE idempotency_key = ?",
                Integer.class,
                idempotencyKey
        );

        assertThat(transferIds).hasSize(1);
        assertThat(statuses).containsExactly(TransferStatus.SUCCEEDED);
        assertThat(transferRows).isEqualTo(1);
        assertThat(aAfter.balancePaise()).isEqualTo(a.wallet.balancePaise() - amount);
        assertThat(bAfter.balancePaise()).isEqualTo(b.wallet.balancePaise() + amount);
    }

    @Test
    void sameIdempotencyKeyWithDifferentBodyIsConflict() {
        TestWallet a = createWallet("a");
        TestWallet b = createWallet("b");
        String key = "conflict-" + UUID.randomUUID();

        transferService.transfer(a.userKey, new TransferCommand(a.wallet.id(), b.wallet.id(), 500, key));

        assertThatThrownBy(() ->
                transferService.transfer(a.userKey, new TransferCommand(a.wallet.id(), b.wallet.id(), 501, key)))
                .isInstanceOf(IdempotencyConflictException.class);

        assertThatThrownBy(() ->
                transferService.transfer(a.userKey, new TransferCommand(a.wallet.id(), UUID.randomUUID(), 500, key)))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void conservationAndNoOverdraftHoldUnderContention() throws Exception {
        TestWallet a = createWallet("a");
        TestWallet b = createWallet("b");
        TestWallet c = createWallet("c");
        List<TestWallet> wallets = List.of(a, b, c);

        long before = wallets.stream().mapToLong(w -> w.wallet.balancePaise()).sum();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Transfer>> futures = new ArrayList<>();

        for (int i = 0; i < 300; i++) {
            TestWallet from = wallets.get(i % 3);
            TestWallet to = wallets.get((i + 1 + (i % 2)) % 3);
            if (from.wallet.id().equals(to.wallet.id())) {
                to = wallets.get((i + 1) % 3);
            }
            long amount = (i % 17 == 0) ? 500_000L : 250L + (i % 5) * 100L;
            TransferCommand command = new TransferCommand(
                    from.wallet.id(),
                    to.wallet.id(),
                    amount,
                    "contention-" + UUID.randomUUID()
            );
            String caller = from.userKey;
            Callable<Transfer> task = () -> {
                start.await();
                return transferService.transfer(caller, command);
            };
            futures.add(executor.submit(task));
        }

        start.countDown();
        int declined = 0;
        for (Future<Transfer> future : futures) {
            Transfer transfer = future.get(60, TimeUnit.SECONDS);
            if (transfer.status() == TransferStatus.DECLINED) {
                declined++;
            }
        }

        Wallet aAfter = walletService.getOwnedWallet(a.userKey, a.wallet.id());
        Wallet bAfter = walletService.getOwnedWallet(b.userKey, b.wallet.id());
        Wallet cAfter = walletService.getOwnedWallet(c.userKey, c.wallet.id());
        long after = aAfter.balancePaise() + bAfter.balancePaise() + cAfter.balancePaise();

        assertThat(after).isEqualTo(before);
        assertThat(aAfter.balancePaise()).isGreaterThanOrEqualTo(0);
        assertThat(bAfter.balancePaise()).isGreaterThanOrEqualTo(0);
        assertThat(cAfter.balancePaise()).isGreaterThanOrEqualTo(0);
        assertThat(declined).isGreaterThan(0);
    }

    private TestWallet createWallet(String label) {
        String userKey = tokenHasher.hash(label + "-" + UUID.randomUUID());
        return new TestWallet(userKey, walletService.getOrCreate(userKey));
    }

    private record TestWallet(String userKey, Wallet wallet) {}
}
