package com.paytm.wallet.persistence;

import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.domain.TransferStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TransferRepository {

    private static final RowMapper<Transfer> ROW_MAPPER = new RowMapper<>() {
        @Override
        public Transfer mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Transfer(
                    rs.getObject("id", UUID.class),
                    rs.getString("idempotency_key"),
                    rs.getString("initiated_by_user_key"),
                    rs.getObject("from_wallet_id", UUID.class),
                    rs.getObject("to_wallet_id", UUID.class),
                    rs.getLong("amount_paise"),
                    TransferStatus.valueOf(rs.getString("status")),
                    rs.getString("failure_reason"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant()
            );
        }
    };

    private final JdbcTemplate jdbcTemplate;

    public TransferRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean tryInsertPending(UUID id,
                                    String idempotencyKey,
                                    String initiatedByUserKey,
                                    UUID fromWalletId,
                                    UUID toWalletId,
                                    long amountPaise) {
        int rows = jdbcTemplate.update("""
                INSERT INTO transfers(
                    id,
                    idempotency_key,
                    initiated_by_user_key,
                    from_wallet_id,
                    to_wallet_id,
                    amount_paise,
                    status
                )
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING')
                ON CONFLICT (idempotency_key) DO NOTHING
                """, id, idempotencyKey, initiatedByUserKey, fromWalletId, toWalletId, amountPaise);
        return rows == 1;
    }

    public Optional<Transfer> findByIdempotencyKey(String idempotencyKey) {
        return queryOne("""
                SELECT id, idempotency_key, initiated_by_user_key,
                       from_wallet_id, to_wallet_id, amount_paise,
                       status, failure_reason, created_at, updated_at
                FROM transfers
                WHERE idempotency_key = ?
                """, idempotencyKey);
    }

    public Optional<Transfer> findById(UUID id) {
        return queryOne("""
                SELECT id, idempotency_key, initiated_by_user_key,
                       from_wallet_id, to_wallet_id, amount_paise,
                       status, failure_reason, created_at, updated_at
                FROM transfers
                WHERE id = ?
                """, id);
    }

    public void markSucceeded(UUID id) {
        jdbcTemplate.update("""
                UPDATE transfers
                SET status = 'SUCCEEDED', failure_reason = NULL, updated_at = NOW()
                WHERE id = ?
                """, id);
    }

    public void markDeclined(UUID id, String reason) {
        jdbcTemplate.update("""
                UPDATE transfers
                SET status = 'DECLINED', failure_reason = ?, updated_at = NOW()
                WHERE id = ?
                """, reason, id);
    }

    private Optional<Transfer> queryOne(String sql, Object arg) {
        return jdbcTemplate.query(sql, ROW_MAPPER, arg).stream().findFirst();
    }
}
