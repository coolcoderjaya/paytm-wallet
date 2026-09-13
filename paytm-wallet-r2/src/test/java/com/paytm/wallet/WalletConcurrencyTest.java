package com.paytm.wallet;

import com.paytm.wallet.domain.Wallet;
import com.paytm.wallet.security.TokenHasher;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class WalletConcurrencyTest extends PostgresIntegrationTestSupport {

    @Autowired WalletService walletService;
    @Autowired TokenHasher tokenHasher;
    @Autowired JdbcTemplate jdbcTemplate;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE transfers, wallets CASCADE");
        executor = Executors.newFixedThreadPool(50);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void concurrentGetOrCreateReturnsExactlyOneWallet() throws Exception {
        String userKey = tokenHasher.hash("race-user-" + UUID.randomUUID());
        int workers = 50;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Wallet>> futures = new ArrayList<>();

        for (int i = 0; i < workers; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                return walletService.getOrCreate(userKey);
            }));
        }

        start.countDown();

        Set<UUID> walletIds = new HashSet<>();
        for (Future<Wallet> future : futures) {
            walletIds.add(future.get(20, TimeUnit.SECONDS).id());
        }

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallets WHERE user_key = ?",
                Integer.class,
                userKey
        );

        assertThat(walletIds).hasSize(1);
        assertThat(rows).isEqualTo(1);
    }
}
