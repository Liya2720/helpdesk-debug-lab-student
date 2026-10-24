package edu.helpdesk.repository.jdbc;

import edu.helpdesk.config.Database;
import edu.helpdesk.exception.DataAccessException;
import edu.helpdesk.model.Ticket;
import edu.helpdesk.model.TicketPriority;
import edu.helpdesk.model.TicketStatus;
import edu.helpdesk.repository.TicketRepository;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class JdbcTicketRepository implements TicketRepository {

    @Override
    public Ticket create(String title, String description, TicketPriority priority, long authorId, Long categoryId, LocalDateTime dueAt) {
        String sql = """
            INSERT INTO tickets(title, description, status, priority, author_id, category_id, due_at)
            VALUES (?, ?, 'NEW', ?, ?, ?, ?)
            RETURNING *
            """;

        try (Connection connection = Database.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {

            ps.setString(1, title);
            ps.setString(2, description);
            ps.setString(3, priority.name());
            ps.setLong(4, authorId);
            if (categoryId != null) {
                ps.setLong(5, categoryId);
            } else {
                ps.setNull(5, java.sql.Types.BIGINT);
            }
            if (dueAt != null) {
                ps.setTimestamp(6, java.sql.Timestamp.valueOf(dueAt));
            } else {
                ps.setNull(6, java.sql.Types.TIMESTAMP);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return mapTicket(rs);
            }
        } catch (SQLException e) {
            throw new DataAccessException("Ошибка создания заявки", e);
        }
    }

    @Override
    public Optional<Ticket> findById(long id) {
        String sql = "SELECT * FROM tickets WHERE id = ?";
        try (Connection connection = Database.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapTicket(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DataAccessException("Ошибка чтения заявки", e);
        }
    }

    @Override
    public List<Ticket> findAll() {
        String sql = "SELECT * FROM tickets ORDER BY id";
        return list(sql, ps -> { });
    }

    @Override
    public List<Ticket> findByStatus(TicketStatus status) {
        String sql = "SELECT * FROM tickets WHERE status = ? ORDER BY id";
        return list(sql, ps -> ps.setString(1, status.name()));
    }

    @Override
    public List<Ticket> searchByTitle(String query) {
        String sql = "SELECT * FROM tickets WHERE title ILIKE ? ORDER BY id";
        return list(sql, ps -> ps.setString(1, "%" + query + "%"));
    }

    @Override
    public int assign(long ticketId, long assigneeId, int currentVersion) {
        String sql = "UPDATE tickets SET assignee_id = ?, updated_at = CURRENT_TIMESTAMP, version = version + 1 WHERE id = ? AND version = ?";
        try (Connection connection = Database.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, assigneeId);
            ps.setLong(2, ticketId);
            ps.setInt(3, currentVersion);

            int updated = ps.executeUpdate();
            if (updated != 1) {
                throw new DataAccessException("Ожидалась одна изменённая заявка, обновлено: " + updated, null);
            }
            return updated;
        } catch (SQLException e) {
            throw new DataAccessException("Ошибка назначения исполнителя", e);
        }
    }

    @Override
    public void changeStatusAndRecordHistory(long ticketId, TicketStatus oldStatus, TicketStatus newStatus, long changedBy, int currentVersion) {
        String updateSql = "UPDATE tickets SET status = ?, updated_at = CURRENT_TIMESTAMP, version = version + 1 WHERE id = ? AND version = ?";
        String historySql = "INSERT INTO ticket_status_history(ticket_id, old_status, new_status, changed_by) VALUES (?, ?, ?, ?)";
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement ps1 = connection.prepareStatement(updateSql);
                 PreparedStatement ps2 = connection.prepareStatement(historySql)) {
                ps1.setString(1, newStatus.name());
                ps1.setLong(2, ticketId);
                ps1.setInt(3, currentVersion);

                int updated = ps1.executeUpdate();

                if (updated != 1) {
                    throw new DataAccessException("Конфликт версий: заявка уже изменена другим пользователем", null);
                }

                ps2.setLong(1, ticketId);
                ps2.setString(2, oldStatus != null ? oldStatus.name() : null);
                ps2.setString(3, newStatus.name());
                ps2.setLong(4, changedBy);
                ps2.executeUpdate();

                connection.commit();

            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }

        } catch (SQLException e) {
            throw new DataAccessException("Ошибка соединения с БД", e);
        }
    }

    private List<Ticket> list(String sql, SqlBinder binder) {
        List<Ticket> result = new ArrayList<>();
        try (Connection connection = Database.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(mapTicket(rs));
            }
            return result;
        } catch (SQLException e) {
            throw new DataAccessException("Ошибка выборки заявок", e);
        }
    }

    private Ticket mapTicket(ResultSet rs) throws SQLException {
        return new Ticket(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("description"),
                TicketStatus.valueOf(rs.getString("status")),
                TicketPriority.valueOf(rs.getString("priority")),
                rs.getLong("author_id"),
                nullableLong(rs, "assignee_id"),
                nullableLong(rs, "category_id"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime(),
                rs.getTimestamp("due_at") != null ? rs.getTimestamp("due_at").toLocalDateTime() : null,
                rs.getInt("version")
        );
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    @Override
    public List<Ticket> findOverdue(LocalDateTime now) {
        // Ищем заявки, у которых срок истек, и они не RESOLVED/CLOSED
        String sql = "SELECT * FROM tickets WHERE due_at < ? AND status NOT IN ('RESOLVED', 'CLOSED') ORDER BY due_at";

        return list(sql, ps -> ps.setTimestamp(1, Timestamp.valueOf(now)));
    }
}
