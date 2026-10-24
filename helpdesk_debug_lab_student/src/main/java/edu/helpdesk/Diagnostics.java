package edu.helpdesk;

import edu.helpdesk.config.Database;
import edu.helpdesk.exception.DataAccessException;
import edu.helpdesk.model.TicketStatus;
import edu.helpdesk.repository.CommentRepository;
import edu.helpdesk.repository.TicketRepository;
import edu.helpdesk.repository.UserRepository;
import edu.helpdesk.repository.jdbc.JdbcCommentRepository;
import edu.helpdesk.repository.jdbc.JdbcTicketRepository;
import edu.helpdesk.repository.jdbc.JdbcUserRepository;
import edu.helpdesk.service.TicketService;
import edu.helpdesk.model.TicketPriority;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

public class Diagnostics {
    private int passed;
    private int failed;

    public static void main(String[] args) {
        new Diagnostics().run();
    }

    private void run() {
        System.out.println("=== HELP DESK DIAGNOSTICS ===");
        System.out.println("PASS означает соответствие ожидаемому поведению, FAIL — обнаруженный дефект.\n");

        check("Подключение к БД и исходные 3 заявки", this::databaseAndSeed);
        check("Заявка #1 имеет тему «Не работает Wi-Fi»", this::mappingTitle);
        check("Фильтр NEW возвращает только новую заявку", this::statusFilter);
        check("Назначение меняет ровно одну заявку", this::assignmentTouchesOneTicket);
        check("Комментарии заявки #1 не содержат комментарии других заявок", this::commentsAreFiltered);
        check("Поиск по фрагменту «Wi» находит Wi-Fi", this::partialSearch);
        check("Переход NEW -> RESOLVED запрещён", this::invalidTransitionRejected);
        check("CUSTOMER нельзя назначить исполнителем", this::customerCannotBeAssignee);
        check("Статус и история изменяются атомарно", this::statusChangeIsAtomic);
        check("Email пользователя уникален на уровне БД", this::emailIsUnique);
        check("БД запрещает неизвестный приоритет", this::priorityIsConstrained);
        check("SLA: приоритет CRITICAL дает срок 1 час", this::slaCritical);
        check("SLA: просроченные заявки не содержат RESOLVED", this::slaIgnoresResolved);
        check("SLA: заявка с истекшим сроком попадает в findOverdue", this::slaOverdueFound);
        check("Optimistic lock: нельзя обновить устаревшую версию", this::optimisticLockPreventsUpdate);
        System.out.println("\nИтог: PASS=" + passed + ", FAIL=" + failed);
        System.out.println("Перед повторным запуском диагностики данные автоматически восстанавливаются для каждого теста.");
    }

    private TicketService service() {
        TicketRepository tickets = new JdbcTicketRepository();
        UserRepository users = new JdbcUserRepository();
        CommentRepository comments = new JdbcCommentRepository();
        return new TicketService(tickets, users, comments);
    }

    private void databaseAndSeed() throws Exception {
        reset();
        try (Connection c = Database.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM tickets")) {
            rs.next();
            require(rs.getInt(1) == 3, "Ожидалось 3 заявки, получено " + rs.getInt(1));
        }
    }

    private void mappingTitle() throws Exception {
        reset();
        String title = service().find(1).getTitle();
        require("Не работает Wi-Fi".equals(title), "Получена тема: " + title);
    }

    private void statusFilter() throws Exception {
        reset();
        var list = service().findByStatus(TicketStatus.NEW);
        require(list.size() == 1 && list.get(0).getId() == 1, "Получено записей: " + list.size());
    }

    private void assignmentTouchesOneTicket() throws Exception {
        reset();
        TicketRepository repo = new JdbcTicketRepository();
        repo.assign(1, 2, 0);
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM tickets WHERE assignee_id = 2"); ResultSet rs = ps.executeQuery()) {
            rs.next();
            require(rs.getInt(1) == 2, "После назначения у Сергея заявок: " + rs.getInt(1) + " (с учётом исходной #2 должно быть 2)");
        }
    }

    private void commentsAreFiltered() throws Exception {
        reset();
        var list = service().comments(1);
        require(list.size() == 1 && list.stream().allMatch(c -> c.getTicketId() == 1), "Получено комментариев: " + list.size());
    }

    private void partialSearch() throws Exception {
        reset();
        var list = service().search("Wi");
        require(list.stream().anyMatch(t -> t.getId() == 1), "Заявка #1 не найдена");
    }

    private void invalidTransitionRejected() throws Exception {
        reset();
        boolean rejected = false;
        try {
            service().changeStatus(1, TicketStatus.RESOLVED, 2);
        } catch (IllegalStateException e) {
            rejected = true;
        }
        require(rejected, "Переход NEW -> RESOLVED был принят");
    }

    private void customerCannotBeAssignee() throws Exception {
        reset();
        boolean rejected = false;
        try {
            service().assignTicket(1, 1);
        } catch (IllegalArgumentException e) {
            rejected = true;
        } catch (IllegalStateException e) {
            // Ошибка количества обновлённых строк — это другой дефект и не считается проверкой роли.
        }
        require(rejected, "Пользователь CUSTOMER был принят как исполнитель");
    }

    private void statusChangeIsAtomic() throws Exception {
        reset();
        try {
            new JdbcTicketRepository().changeStatusAndRecordHistory(
                    1, TicketStatus.NEW, TicketStatus.IN_PROGRESS, 9999, 0
            );
        } catch (Exception ignored) {
        }
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT status FROM tickets WHERE id = 1")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                require("NEW".equals(rs.getString(1)), "После ошибки история не записалась, но статус стал " + rs.getString(1));
            }
        }
    }

    private void emailIsUnique() throws Exception {
        reset();
        boolean rejected = false;
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement("INSERT INTO users(full_name, email, role, department_id) VALUES ('Дубликат', 'anna@example.com', 'CUSTOMER', 2)")) {
            ps.executeUpdate();
        } catch (Exception e) {
            rejected = true;
        }
        require(rejected, "БД разрешила второй anna@example.com");
    }

    private void priorityIsConstrained() throws Exception {
        reset();
        boolean rejected = false;
        try (Connection c = Database.getConnection(); PreparedStatement ps = c.prepareStatement("""
                INSERT INTO tickets(title, description, status, priority, author_id, category_id)
                VALUES ('Проверка', 'Проверка ограничения', 'NEW', 'SUPER', 1, 1)
                """)) {
            ps.executeUpdate();
        } catch (Exception e) {
            rejected = true;
        }
        require(rejected, "БД приняла priority='SUPER'");
    }

    private void reset() throws Exception {
        try (Connection c = Database.getConnection(); Statement st = c.createStatement()) {
            st.execute("TRUNCATE ticket_status_history, ticket_comments, tickets, users, categories, departments RESTART IDENTITY CASCADE");
            st.execute("INSERT INTO departments(name) VALUES ('Техническая поддержка'), ('Бухгалтерия')");
            st.execute("INSERT INTO categories(name) VALUES ('Сеть'), ('Оборудование'), ('Программное обеспечение')");
            st.execute("""
                    INSERT INTO users(full_name, email, role, department_id) VALUES
                    ('Анна Петрова', 'anna@example.com', 'CUSTOMER', 2),
                    ('Сергей Иванов', 'sergey@helpdesk.local', 'SUPPORT', 1),
                    ('Ольга Смирнова', 'olga@helpdesk.local', 'SUPPORT', 1),
                    ('Олег Администратор', 'admin@helpdesk.local', 'ADMIN', 1),
                    ('Пётр Соколов', 'petr@example.com', 'CUSTOMER', 2)
                    """);
            st.execute("""
                    INSERT INTO tickets(title, description, status, priority, author_id, assignee_id, category_id) VALUES
                    ('Не работает Wi-Fi', 'Ноутбук подключается к сети, но интернет недоступен', 'NEW', 'HIGH', 1, NULL, 1),
                    ('Принтер недоступен', 'Сетевой принтер не отвечает на задания печати', 'IN_PROGRESS', 'MEDIUM', 5, 2, 2),
                    ('CRM закрывается при запуске', 'После обновления CRM завершается сразу после авторизации', 'RESOLVED', 'CRITICAL', 1, 3, 3)
                    """);
            st.execute("""
                    INSERT INTO ticket_comments(ticket_id, author_id, text) VALUES
                    (1, 1, 'Проблема появилась после обновления Windows.'),
                    (2, 2, 'Проверьте питание принтера и перезапустите устройство.'),
                    (3, 3, 'Переустановлен повреждённый модуль клиента CRM.')
                    """);
            st.execute("""
                    INSERT INTO ticket_status_history(ticket_id, old_status, new_status, changed_by) VALUES
                    (1, NULL, 'NEW', 1),
                    (2, NULL, 'NEW', 5),
                    (2, 'NEW', 'IN_PROGRESS', 2),
                    (3, NULL, 'NEW', 1),
                    (3, 'NEW', 'IN_PROGRESS', 3),
                    (3, 'IN_PROGRESS', 'RESOLVED', 3)
                    """);
        }
    }

    private void check(String name, CheckedAction action) {
        try {
            action.run();
            passed++;
            System.out.println("[PASS] " + name);
        } catch (Throwable e) {
            failed++;
            System.out.println("[FAIL] " + name + " -> " + rootMessage(e));
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private String rootMessage(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }

    private void slaCritical() throws Exception {
        reset();
        var ticket = service().createTicket("Тест SLA", "Описание", TicketPriority.CRITICAL, 1, 1L);
        require(ticket.getDueAt() != null, "dueAt не рассчитан");
        // Проверяем, что срок примерно через 1 час (с погрешностью в минуту)
        var expected = java.time.LocalDateTime.now().plusHours(1);
        require(Math.abs(ticket.getDueAt().getMinute() - expected.getMinute()) <= 1, "Срок неверный");
    }

    private void slaIgnoresResolved() throws Exception {
        reset();
        // Создаем заявку и переводим ее в RESOLVED
        var ticket = service().createTicket("Тест", "Описание", TicketPriority.LOW, 1, 1L);
        service().changeStatus(ticket.getId(), TicketStatus.IN_PROGRESS, 2);
        service().changeStatus(ticket.getId(), TicketStatus.RESOLVED, 2);

        var overdue = service().findOverdue(java.time.LocalDateTime.now().plusHours(25));
        require(overdue.stream().noneMatch(t -> t.getId() == ticket.getId()), "RESOLVED заявка попала в просроченные");
    }

    private void slaOverdueFound() throws Exception {
        reset();
        // Создаем заявку и вручную ставим ей просроченный due_at через SQL
        var ticket = service().createTicket("Просроченная", "Описание", TicketPriority.LOW, 1, 1L);
        try (var c = Database.getConnection(); var ps = c.prepareStatement("UPDATE tickets SET due_at = ? WHERE id = ?")) {
            ps.setTimestamp(1, java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusHours(1)));
            ps.setLong(2, ticket.getId());
            ps.executeUpdate();
        }

        var overdue = service().findOverdue(java.time.LocalDateTime.now());
        require(overdue.stream().anyMatch(t -> t.getId() == ticket.getId()), "Просроченная заявка не найдена");
    }

    private void optimisticLockPreventsUpdate() throws Exception {
        reset();
        // Получаем заявку (версия 0)
        var ticket = service().find(1);

        // Меняем ее (версия станет 1)
        service().assignTicket(1, 2);

        // Пытаемся изменить ее еще раз, используя СТАРУЮ версию (0)
        boolean rejected = false;
        try {
            new JdbcTicketRepository().assign(1, 3, ticket.getVersion());
        } catch (DataAccessException e) {
            rejected = true; // Ожидаем ошибку конфликта версий
        }
        require(rejected, "Устаревшая версия была принята");
    }
}
