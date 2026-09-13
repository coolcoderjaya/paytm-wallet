package com.paytm.wallet.persistence;

import com.paytm.wallet.domain.Wallet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class WalletRepository {

    private static final RowMapper<Wallet> ROW_MAPPER = new RowMapper<>() {
        @Override
        public Wallet mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Wallet(
                    rs.getObject("id", UUID.class),
                    rs.getString("user_key"),
                    rs.getLong("balance_paise"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant()
            );
        }
    };

    private final JdbcTemplate jdbcTemplate;

    public WalletRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean tryCreate(UUID id, String userKey, long initialBalancePaise) {
        int rows = jdbcTemplate.update("""
                INSERT INTO wallets(id, user_key, balance_paise)
                VALUES (?, ?, ?)
                ON CONFLICT (user_key) DO NOTHING
                """, id, userKey, initialBalancePaise);
        return rows == 1;
    }

    public Optional<Wallet> findByUserKey(String userKey) {
        return jdbcTemplate.query("""
                SELECT id, user_key, balance_paise, created_at, updated_at
                FROM wallets
                WHERE user_key = ?
                """, ROW_MAPPER, userKey).stream().findFirst();
    }

    public Optional<Wallet> findById(UUID id) {
        return jdbcTemplate.query("""
                SELECT id, user_key, balance_paise, created_at, updated_at
                FROM wallets
                WHERE id = ?
                """, ROW_MAPPER, id).stream().findFirst();
    }

    public Optional<Wallet> lockById(UUID id) {
        return jdbcTemplate.query("""
                SELECT id, user_key, balance_paise, created_at, updated_at
                FROM wallets
                WHERE id = ?
                FOR UPDATE
                """, ROW_MAPPER, id).stream().findFirst();
    }

    public int debitIfSufficient(UUID id, long amountPaise) {
        return jdbcTemplate.update("""
                UPDATE wallets
                SET balance_paise = balance_paise - ?, updated_at = NOW()
                WHERE id = ? AND balance_paise >= ?
                """, amountPaise, id, amountPaise);
    }

    public int credit(UUID id, long amountPaise) {
        return jdbcTemplate.update("""
                UPDATE wallets
                SET balance_paise = balance_paise + ?, updated_at = NOW()
                WHERE id = ?
                """, amountPaise, id);
    }
}
