package com.onepipe.migration;

import com.onepipe.OnepipeBoardingSchoolApplication;
import com.onepipe.config.DataSeeder;
import com.onepipe.integration.MockOnepipeClient;
import com.onepipe.integration.OnepipeClient;
import com.onepipe.testsupport.ManualScheduling;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import javax.sql.DataSource;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real PostgreSQL verification, deliberately separate from H2. Supply a disposable local
 * PostgreSQL database through GREENFIELD_MIGRATION_TEST_DB_URL, _USERNAME and _PASSWORD.
 * Every test creates and removes only its own randomly named schema. Without the URL,
 * JUnit reports these tests as skipped; H2 success is never presented as PostgreSQL evidence.
 */
@EnabledIfEnvironmentVariable(named = "GREENFIELD_MIGRATION_TEST_DB_URL", matches = ".+")
class PostgreSqlMigrationIntegrationTest {

    private static final String LOCATION = "classpath:db/migration/postgresql";
    private static final List<String> ENTITY_TABLES = List.of(
            "app_users", "branches", "parents", "students", "payments", "payment_collections");

    @TempDir
    Path temporaryDirectory;

    private String databaseUrl;
    private String schemaUrl;
    private String username;
    private String password;
    private String schema;
    private boolean schemaCreated;

    @BeforeEach
    void createOwnedLocalSchema() throws Exception {
        databaseUrl = requiredEnvironment("GREENFIELD_MIGRATION_TEST_DB_URL");
        username = requiredEnvironment("GREENFIELD_MIGRATION_TEST_DB_USERNAME");
        password = requiredEnvironment("GREENFIELD_MIGRATION_TEST_DB_PASSWORD");
        assertThat(databaseUrl).startsWith("jdbc:postgresql://");
        URI uri = URI.create(databaseUrl.substring("jdbc:".length()));
        assertThat(uri.getHost()).as("Migration tests require an explicitly local PostgreSQL instance")
                .isIn("localhost", "127.0.0.1", "[::1]", "::1");
        assertThat(databaseUrl.toLowerCase()).doesNotContain("currentschema=");
        schema = "task9_" + UUID.randomUUID().toString().replace("-", "");
        schemaUrl = databaseUrl + (databaseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        try (Connection connection = DriverManager.getConnection(databaseUrl, username, password)) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            connection.createStatement().execute("CREATE SCHEMA " + schema);
            schemaCreated = true;
        }
    }

    @AfterEach
    void removeOnlyOwnedSchema() throws SQLException {
        if (schemaCreated) {
            assertThat(schema).matches("task9_[a-f0-9]{32}");
            try (Connection connection = DriverManager.getConnection(databaseUrl, username, password)) {
                connection.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test
    void emptyDatabaseSchemaContainsEveryEntityAndInheritedIdentityAndAuditColumn() throws Exception {
        assertThat(flyway().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(strings("SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema()"))
                .containsExactlyInAnyOrder("app_users", "branches", "parents", "students", "payments",
                        "payment_collections", "flyway_schema_history");
        for (String table : ENTITY_TABLES) {
            assertThat(text("SELECT data_type || ':' || is_identity FROM information_schema.columns"
                    + " WHERE table_schema=current_schema() AND table_name='" + table + "' AND column_name='id'"))
                    .isEqualTo("bigint:YES");
            assertThat(number("SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema()"
                    + " AND table_name='" + table + "' AND column_name IN ('created_at','updated_at')"
                    + " AND data_type='timestamp without time zone' AND datetime_precision=6")).isEqualTo(2);
            assertThat(number("SELECT count(*) FROM information_schema.table_constraints"
                    + " WHERE table_schema=current_schema() AND table_name='" + table
                    + "' AND constraint_type='PRIMARY KEY'")).isEqualTo(1);
        }
    }

    @Test
    void financialAndLifecycleColumnsUseMappedPrecisionAndTimestampTypes() throws Exception {
        flyway().migrate();
        assertThat(number("SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema()"
                + " AND ((table_name='payments' AND column_name IN ('total_amount','down_payment_amount','amount_per_cycle'))"
                + " OR (table_name='payment_collections' AND column_name='confirmed_amount'))"
                + " AND data_type='numeric' AND numeric_precision=38 AND numeric_scale=2")).isEqualTo(4);
        assertThat(text(columnType("payments", "accounting_version"))).isEqualTo("integer");
        assertThat(text(columnType("payments", "row_version"))).isEqualTo("bigint");
        assertThat(text(columnType("payments", "billing_anchor"))).isEqualTo("timestamp without time zone");
        assertThat(text(columnType("payments", "virtual_account_expired_at")))
                .isEqualTo("timestamp with time zone");
        assertThat(text(columnType("payment_collections", "confirmed_at")))
                .isEqualTo("timestamp with time zone");
        assertThat(text("SELECT is_nullable FROM information_schema.columns WHERE table_schema=current_schema()"
                + " AND table_name='payments' AND column_name='row_version'")).isEqualTo("NO");
    }

    @Test
    void relationshipAndPaymentLookupIndexesExistWithoutDuplicatingUniqueIndexes() throws Exception {
        flyway().migrate();
        assertThat(number("SELECT count(*) FROM pg_constraint WHERE connamespace=current_schema()::regnamespace"
                + " AND contype='f'")).isEqualTo(8);
        assertThat(number("SELECT count(*) FROM pg_constraint fk WHERE fk.connamespace=current_schema()::regnamespace"
                + " AND fk.contype='f' AND NOT EXISTS (SELECT 1 FROM pg_index idx"
                + " WHERE idx.indrelid=fk.conrelid AND idx.indisvalid AND idx.indkey[0]=fk.conkey[1])"))
                .as("Every foreign key has an index with its referencing column first").isZero();
        assertThat(strings("SELECT indexdef FROM pg_indexes WHERE schemaname=current_schema() AND tablename='payments'"))
                .anyMatch(index -> index.endsWith("(status)"))
                .anyMatch(index -> index.endsWith("(one_pipe_payment_id)"))
                .anyMatch(index -> index.endsWith("(student_id, category, status)"));
    }

    @Test
    void repeatedMigrationPreservesIdentitiesFinancialDataAndHistory() throws Exception {
        flyway().migrate();
        long firstPayment = insertPayment("repeat-first");
        insertCollection(firstPayment, "repeat-event", "FULL", "100.00", "NGN", "MOCK", "FULL");
        assertThat(flyway().migrate().migrationsExecuted).isZero();
        assertThat(number("SELECT count(*) FROM flyway_schema_history WHERE success")).isEqualTo(1);
        assertThat(number("SELECT count(*) FROM payment_collections")).isEqualTo(1);
        assertThat(text("SELECT confirmed_amount::text FROM payment_collections")).isEqualTo("100.00");
        assertThat(insertPayment("repeat-next")).isGreaterThan(firstPayment);
        assertThat(flyway().validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void relationshipsRejectOrphansAndEnforceOneToOneUserOwnership() throws Exception {
        flyway().migrate();
        long user = insertId("INSERT INTO app_users(email,password,role) VALUES ('parent@example.invalid','test-only','PARENT') RETURNING id");
        execute("INSERT INTO parents(user_id) VALUES (" + user + ")");
        assertSqlState("INSERT INTO parents(user_id) VALUES (" + user + ")", "23505");
        execute("INSERT INTO branches(admin_user_id) VALUES (" + user + ")");
        assertSqlState("INSERT INTO branches(admin_user_id) VALUES (" + user + ")", "23505");
        assertSqlState("INSERT INTO students(first_name,surname,student_reg_id,parent_id)"
                + " VALUES ('Demo','Student','orphan',-1)", "23503");
        assertSqlState("INSERT INTO payments(row_version,student_id) VALUES (0,-1)", "23503");
        assertSqlState("INSERT INTO payments(row_version,parent_id) VALUES (0,-1)", "23503");
        assertSqlState("INSERT INTO payments(row_version,branch_id) VALUES (0,-1)", "23503");
        assertSqlState("INSERT INTO parents(user_id) VALUES (-1)", "23503");
        assertSqlState("INSERT INTO branches(admin_user_id) VALUES (-1)", "23503");
    }

    @Test
    void entityUniqueConstraintsRejectRepeatedEmailsRegistrationAndRequestReferences() throws Exception {
        flyway().migrate();
        execute("INSERT INTO app_users(email,password) VALUES ('unique@example.invalid','test-only')");
        assertSqlState("INSERT INTO app_users(email,password) VALUES ('unique@example.invalid','test-only')", "23505");
        execute("INSERT INTO students(first_name,surname,student_reg_id) VALUES ('Demo','Student','DEMO-1')");
        assertSqlState("INSERT INTO students(first_name,surname,student_reg_id) VALUES ('Demo','Other','DEMO-1')", "23505");
        insertPayment("unique-payment");
        assertSqlState("INSERT INTO payments(row_version,transaction_ref) VALUES (0,'txn-unique-payment')", "23505");
        assertSqlState("INSERT INTO payments(row_version,request_ref) VALUES (0,'req-unique-payment')", "23505");
        execute("INSERT INTO branches(rc_number,request_ref,transaction_ref) VALUES ('DEMO-RC','branch-req','branch-txn')");
        assertSqlState("INSERT INTO branches(rc_number) VALUES ('DEMO-RC')", "23505");
        assertSqlState("INSERT INTO branches(request_ref) VALUES ('branch-req')", "23505");
        assertSqlState("INSERT INTO branches(transaction_ref) VALUES ('branch-txn')", "23505");
    }

    @Test
    void ledgerEnforcesGlobalEventAndPerPaymentSlotUniqueness() throws Exception {
        flyway().migrate();
        long first = insertPayment("ledger-first");
        long second = insertPayment("ledger-second");
        insertCollection(first, "global-event", "FULL", "100.00", "NGN", "MOCK", "FULL");
        assertThatThrownBy(() -> insertCollection(second, "global-event", "FULL", "100.00", "NGN", "MOCK", "FULL"))
                .isInstanceOf(SQLException.class).extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("23505");
        assertThatThrownBy(() -> insertCollection(first, "another-event", "FULL", "100.00", "NGN", "MOCK", "FULL"))
                .isInstanceOf(SQLException.class).extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("23505");
        assertThat(number("SELECT count(*) FROM payment_collections")).isEqualTo(1);
    }

    @Test
    void ledgerRejectsInvalidAmountCurrencySourceTypeAndMissingPayment() throws Exception {
        flyway().migrate();
        long payment = insertPayment("ledger-invalid");
        assertInvalidCollection(payment, "0.00", "NGN", "MOCK", "FULL", "23514");
        assertInvalidCollection(payment, "-0.01", "NGN", "MOCK", "FULL", "23514");
        assertInvalidCollection(payment, "1.00", "USD", "MOCK", "FULL", "23514");
        assertInvalidCollection(payment, "1.00", "NGN", "UNKNOWN", "FULL", "23514");
        assertInvalidCollection(payment, "1.00", "NGN", "MOCK", "UNKNOWN", "23514");
        assertInvalidCollection(-1, "1.00", "NGN", "MOCK", "FULL", "23503");
        assertThat(number("SELECT count(*) FROM payment_collections")).isZero();
    }

    @Test
    void enumsAndMandatoryColumnsAreConstrainedWithoutInventingLegacyAccounting() throws Exception {
        flyway().migrate();
        assertSqlState("INSERT INTO app_users(email,password,role) VALUES ('role@example.invalid','test-only','UNKNOWN')", "23514");
        assertSqlState("INSERT INTO app_users(email) VALUES ('missing-password@example.invalid')", "23502");
        assertSqlState("INSERT INTO payments(row_version,status) VALUES (0,'UNKNOWN')", "23514");
        assertSqlState("INSERT INTO payments(row_version,payment_type) VALUES (0,'UNKNOWN')", "23514");
        assertSqlState("INSERT INTO payments(row_version,installment_frequency) VALUES (0,'UNKNOWN')", "23514");
        execute("INSERT INTO payments(row_version,status,completed_payments,total_amount) VALUES (0,'ACTIVE',3,100.00)");
        flyway().migrate();
        assertThat(number("SELECT count(*) FROM payment_collections")).isZero();
        assertThat(number("SELECT count(*) FROM payments WHERE accounting_version IS NULL AND billing_anchor IS NULL"
                + " AND virtual_account_expired_at IS NULL AND status='ACTIVE' AND completed_payments=3")).isEqualTo(1);
    }

    @Test
    void nonemptyUnmanagedSchemaIsRejectedWithoutCreatingAnAutomaticBaseline() throws Exception {
        execute("CREATE TABLE existing_record(id bigint PRIMARY KEY)");
        execute("INSERT INTO existing_record VALUES (17)");
        assertThatThrownBy(() -> flyway().migrate()).isInstanceOf(FlywayException.class)
                .hasMessageContaining("non-empty schema");
        assertThat(number("SELECT id FROM existing_record")).isEqualTo(17);
        assertThat(number("SELECT count(*) FROM information_schema.tables WHERE table_schema=current_schema()"
                + " AND table_name='flyway_schema_history'")).isZero();
    }

    @Test
    void failedPostgresqlMigrationRollsBackItsDdlAndDoesNotRecordSuccess() throws Exception {
        Path scripts = Files.createDirectory(temporaryDirectory.resolve("broken"));
        Files.writeString(scripts.resolve("V1__deliberate_failure.sql"),
                "CREATE TABLE should_rollback(id bigint PRIMARY KEY);\nTHIS IS INVALID SQL;\n");
        assertThatThrownBy(() -> flyway("filesystem:" + scripts.toAbsolutePath()).migrate())
                .isInstanceOf(FlywayException.class);
        assertThat(number("SELECT count(*) FROM information_schema.tables WHERE table_schema=current_schema()"
                + " AND table_name='should_rollback'")).isZero();
        assertThat(number("SELECT count(*) FROM flyway_schema_history WHERE success")).isZero();
    }

    @Test
    void completeHostedApplicationMigratesBeforeJpaValidationAndPreservesDataOnRestart() throws Exception {
        try (ConfigurableApplicationContext context = hostedContext()) {
            assertThat(context.getBean(EntityManagerFactory.class).isOpen()).isTrue();
            assertThat(context.getBean(OnepipeClient.class)).isInstanceOf(MockOnepipeClient.class);
            assertThat(context.getBeansOfType(DataSeeder.class)).isEmpty();
            assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
            assertThat(context.getBean(Flyway.class).info().applied()).hasSize(1);
            try (Connection connection = context.getBean(DataSource.class).getConnection()) {
                assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            }
            assertThat(number("SELECT count(*) FROM app_users")).isZero();
            insertPayment("hosted-restart");
        }
        try (ConfigurableApplicationContext context = hostedContext()) {
            assertThat(context.getBean(EntityManagerFactory.class).isOpen()).isTrue();
            assertThat(number("SELECT count(*) FROM payments WHERE transaction_ref='txn-hosted-restart'")).isEqualTo(1);
            assertThat(number("SELECT count(*) FROM flyway_schema_history WHERE success")).isEqualTo(1);
        }
    }

    @Test
    void hostedStartupFailsWhenMappedSchemaIsIncompatibleRatherThanRepairingIt() throws Exception {
        flyway().migrate();
        execute("ALTER TABLE payments DROP COLUMN billing_anchor");
        assertThatThrownBy(this::hostedContext).hasStackTraceContaining("Schema-validation: missing column [billing_anchor]");
        assertThat(number("SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema()"
                + " AND table_name='payments' AND column_name='billing_anchor'")).isZero();
    }

    @Test
    void hostedStartupStopsOnMigrationChecksumFailure() throws Exception {
        flyway().migrate();
        execute("UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='1'");
        assertThatThrownBy(this::hostedContext).hasStackTraceContaining("Migration checksum mismatch");
        assertThat(number("SELECT count(*) FROM flyway_schema_history WHERE success")).isEqualTo(1);
        assertThat(number("SELECT count(*) FROM app_users")).isZero();
    }

    private ConfigurableApplicationContext hostedContext() {
        byte[] signingKey = new byte[32];
        new SecureRandom().nextBytes(signingKey);
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.config.location", "classpath:/");
        properties.put("spring.profiles.active", "hosted");
        properties.put("DB_URL", schemaUrl);
        properties.put("DB_USERNAME", username);
        properties.put("DB_PASSWORD", password);
        properties.put("JWT_SECRET", Base64.getEncoder().encodeToString(signingKey));
        properties.put("app.demo.seed.enabled", "false");
        properties.put("app.demo.payments.simulation.enabled", "false");
        properties.put("server.address", "127.0.0.1");
        properties.put("server.port", "0");
        properties.put("logging.level.root", "ERROR");
        properties.put("spring.main.banner-mode", "off");
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("isolated-postgresql-migration-test", properties));
        return new SpringApplicationBuilder(OnepipeBoardingSchoolApplication.class, ManualScheduling.Configuration.class)
                .environment(environment).web(WebApplicationType.SERVLET).run();
    }

    private Flyway flyway() {
        return flyway(LOCATION);
    }

    private Flyway flyway(String location) {
        return Flyway.configure().dataSource(schemaUrl, username, password).defaultSchema(schema).schemas(schema)
                .locations(location).baselineOnMigrate(false).cleanDisabled(true).load();
    }

    private String columnType(String table, String column) {
        return "SELECT data_type FROM information_schema.columns WHERE table_schema=current_schema()"
                + " AND table_name='" + table + "' AND column_name='" + column + "'";
    }

    private long insertPayment(String reference) throws SQLException {
        return insertId("INSERT INTO payments(row_version,accounting_version,status,payment_type,total_amount,request_ref,transaction_ref)"
                + " VALUES (0,1,'PENDING','SINGLE_PAYMENT',100.00,'req-" + reference + "','txn-" + reference + "') RETURNING id");
    }

    private void insertCollection(long payment, String event, String slot, String amount, String currency,
                                  String source, String type) throws SQLException {
        execute("INSERT INTO payment_collections(payment_id,event_id,transaction_ref,collection_slot,confirmed_amount,currency,source,confirmed_at,collection_type)"
                + " VALUES (" + payment + ",'" + event + "','test-transaction','" + slot + "'," + amount + ",'" + currency
                + "','" + source + "',CURRENT_TIMESTAMP,'" + type + "')");
    }

    private void assertInvalidCollection(long payment, String amount, String currency, String source,
                                         String type, String expectedState) {
        assertThatThrownBy(() -> insertCollection(payment, "invalid-event", "FULL", amount, currency, source, type))
                .isInstanceOf(SQLException.class).extracting(error -> ((SQLException) error).getSQLState())
                .isEqualTo(expectedState);
    }

    private void assertSqlState(String sql, String state) {
        assertThatThrownBy(() -> execute(sql)).isInstanceOf(SQLException.class)
                .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo(state);
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long insertId(String sql) throws SQLException {
        return number(sql);
    }

    private long number(String sql) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private String text(String sql) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private List<String> strings(String sql) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (result.next()) {
                values.add(result.getString(1));
            }
            return values;
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(schemaUrl, username, password);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assertThat(value).as("Required local PostgreSQL migration-test environment variable: " + name).isNotBlank();
        return value;
    }
}
