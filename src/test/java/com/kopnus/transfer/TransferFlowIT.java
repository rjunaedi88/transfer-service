package com.kopnus.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Menguji API lewat HTTP sungguhan terhadap PostgreSQL sungguhan, memakai script
 * di folder database. Klaim concurrency tidak bisa dibuktikan dengan mock.
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.jwt.secret=integration-test-secret-with-32-characters",
                "app.rate-limit.transfer.capacity=100000",
                "app.rate-limit.login.capacity=100000"
        })
class TransferFlowIT {

    private static final String BUDI = "1000000001";
    private static final String SITI = "1000000002";
    private static final String BUDI_SECOND = "1000000003";
    private static final String ANDI_BLOCKED = "1000000004";
    private static final String UNKNOWN = "9999999999";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withCopyFileToContainer(MountableFile.forHostPath("database"), "/docker-entrypoint-initdb.d/");

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private String budiToken;
    private String sitiToken;

    @BeforeEach
    void resetData() throws Exception {
        jdbcTemplate.execute("TRUNCATE account_mutations, transfers");
        setBalance(BUDI, "10000000.00");
        setBalance(SITI, "5000000.00");
        budiToken = login("budi");
        sitiToken = login("siti");
    }

    @Test
    void transferMovesMoneyAndWritesLedger() throws Exception {
        HttpResponse<String> response = transfer(budiToken, "key-1", BUDI, SITI, "2500000.50");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(balance(BUDI)).isEqualByComparingTo("7499999.50");
        assertThat(balance(SITI)).isEqualByComparingTo("7500000.50");
        assertThat(rowCount("transfers")).isEqualTo(1);
        assertThat(rowCount("account_mutations")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT balance_after FROM account_mutations WHERE type = 'DEBIT'", BigDecimal.class))
                .isEqualByComparingTo("7499999.50");
    }

    @Test
    void retryWithSameKeyDoesNotMoveMoneyTwice() throws Exception {
        HttpResponse<String> first = transfer(budiToken, "key-1", BUDI, SITI, "1000");
        HttpResponse<String> retry = transfer(budiToken, "key-1", BUDI, SITI, "1000.00");

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(retry.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json(retry)).isEqualTo(json(first));
        assertThat(balance(BUDI)).isEqualByComparingTo("9999000");
    }

    @Test
    void sameKeyWithDifferentAmountIsRejected() throws Exception {
        transfer(budiToken, "key-1", BUDI, SITI, "1000");

        HttpResponse<String> response = transfer(budiToken, "key-1", BUDI, SITI, "2000");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(errorCode(response)).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
        assertThat(balance(BUDI)).isEqualByComparingTo("9999000");
    }

    @Test
    void differentCustomersMayUseSameKey() throws Exception {
        assertThat(transfer(budiToken, "shared", BUDI, SITI, "1000").statusCode()).isEqualTo(201);
        assertThat(transfer(sitiToken, "shared", SITI, BUDI, "500").statusCode()).isEqualTo(201);
    }

    @Test
    void businessRulesAreEnforced() throws Exception {
        assertError(transfer(budiToken, "a", SITI, BUDI, "1000"), 403, "ACCOUNT_ACCESS_DENIED");
        assertError(transfer(budiToken, "b", BUDI, UNKNOWN, "1000"), 404, "ACCOUNT_NOT_FOUND");
        assertError(transfer(budiToken, "c", BUDI, ANDI_BLOCKED, "1000"), 422, "ACCOUNT_INACTIVE");
        assertError(transfer(budiToken, "d", BUDI, SITI, "10000000.01"), 422, "INSUFFICIENT_BALANCE");
        assertError(transfer(budiToken, "e", BUDI, BUDI, "1000"), 400, "SAME_ACCOUNT");
        assertThat(balance(BUDI)).isEqualByComparingTo("10000000");
        assertThat(rowCount("transfers")).isZero();
    }

    @Test
    void failedTransferCanBeRetriedWithSameKeyAfterTopUp() throws Exception {
        assertThat(transfer(budiToken, "key-1", BUDI, SITI, "20000000").statusCode()).isEqualTo(422);
        setBalance(BUDI, "30000000.00");

        assertThat(transfer(budiToken, "key-1", BUDI, SITI, "20000000").statusCode()).isEqualTo(201);
    }

    @Test
    void concurrentTransfersNeverOverdrawAccount() throws Exception {
        setBalance(BUDI, "1000.00");

        List<HttpResponse<String>> responses = runConcurrently(40, i ->
                () -> transfer(budiToken, "key-" + i, BUDI, SITI, "100"));

        Map<Integer, Long> statuses = countByStatus(responses);
        assertThat(statuses).containsOnly(Map.entry(201, 10L), Map.entry(422, 30L));
        assertThat(balance(BUDI)).isEqualByComparingTo("0");
        assertThat(balance(SITI)).isEqualByComparingTo("5001000");
        assertThat(rowCount("transfers")).isEqualTo(10);
        assertThat(rowCount("account_mutations")).isEqualTo(20);
    }

    @Test
    void concurrentRequestsWithSameKeyMoveMoneyOnce() throws Exception {
        // Nominal sama dengan seluruh saldo: request kembar tidak boleh gagal "saldo kurang"
        setBalance(BUDI, "1000.00");

        List<HttpResponse<String>> responses = runConcurrently(20, i ->
                () -> transfer(budiToken, "same-key", BUDI, SITI, "1000"));

        assertThat(countByStatus(responses)).containsOnly(Map.entry(201, 1L), Map.entry(200, 19L));
        assertThat(responses.stream().map(this::transferId).distinct()).hasSize(1);
        assertThat(balance(BUDI)).isEqualByComparingTo("0");
        assertThat(rowCount("transfers")).isEqualTo(1);
    }

    @Test
    void concurrentRequestsWithSameKeyButDifferentAccountsMoveMoneyOnce() throws Exception {
        // Beda rekening sumber berarti tidak saling tertahan kunci akun;
        // yang menjaga di sini adalah unique constraint
        List<HttpResponse<String>> responses = runConcurrently(20, i -> () -> i % 2 == 0
                ? transfer(budiToken, "same-key", BUDI, SITI, "1000")
                : transfer(budiToken, "same-key", BUDI_SECOND, SITI, "1000"));

        assertThat(rowCount("transfers")).isEqualTo(1);
        assertThat(responses.stream().filter(r -> r.statusCode() == 201)).hasSize(1);
        assertThat(balance(SITI)).isEqualByComparingTo("5001000");
        assertThat(responses).allMatch(r -> List.of(200, 201, 409).contains(r.statusCode()));
    }

    @Test
    void oppositeTransfersDoNotDeadlock() throws Exception {
        List<HttpResponse<String>> responses = runConcurrently(60, i -> () -> i % 2 == 0
                ? transfer(budiToken, "key-" + i, BUDI, SITI, "1000")
                : transfer(sitiToken, "key-" + i, SITI, BUDI, "1000"));

        assertThat(countByStatus(responses)).containsOnly(Map.entry(201, 60L));
        assertThat(balance(BUDI)).isEqualByComparingTo("10000000");
        assertThat(balance(SITI)).isEqualByComparingTo("5000000");
    }

    @Test
    void lockTimeoutReturns503AndRetrySucceeds() throws Exception {
        AtomicReference<HttpResponse<String>> whileLocked = new AtomicReference<>();

        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.queryForList("SELECT 1 FROM accounts WHERE account_number = ? FOR UPDATE", BUDI);
            try {
                whileLocked.set(transfer(budiToken, "key-1", BUDI, SITI, "1000"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertError(whileLocked.get(), 503, "SERVICE_BUSY");
        assertThat(balance(BUDI)).isEqualByComparingTo("10000000");
        assertThat(transfer(budiToken, "key-1", BUDI, SITI, "1000").statusCode()).isEqualTo(201);
    }

    @Test
    void invalidTokenAndWrongPasswordAreRejected() throws Exception {
        assertError(transfer("not-a-token", "key-1", BUDI, SITI, "1000"), 401, "UNAUTHORIZED");
        assertError(post("/api/v1/auth/login", null, null,
                "{\"username\":\"budi\",\"password\":\"wrong\"}"), 401, "INVALID_CREDENTIALS");
        assertThat(balance(BUDI)).isEqualByComparingTo("10000000");
    }

    private List<HttpResponse<String>> runConcurrently(int count,
            Function<Integer, Callable<HttpResponse<String>>> taskFactory) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < count; i++) {
                Callable<HttpResponse<String>> task = taskFactory.apply(i);
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<HttpResponse<String>> responses = new ArrayList<>();
            for (Future<HttpResponse<String>> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }
            return responses;
        }
    }

    private Map<Integer, Long> countByStatus(List<HttpResponse<String>> responses) {
        return responses.stream().collect(Collectors.groupingBy(HttpResponse::statusCode, Collectors.counting()));
    }

    private String login(String username) throws Exception {
        HttpResponse<String> response = post("/api/v1/auth/login", null, null,
                "{\"username\":\"%s\",\"password\":\"Password123!\"}".formatted(username));
        assertThat(response.statusCode()).isEqualTo(200);
        return json(response).get("accessToken").asString();
    }

    private HttpResponse<String> transfer(String token, String key, String source, String destination, String amount)
            throws IOException, InterruptedException {
        String body = """
                {"sourceAccountNumber":"%s","destinationAccountNumber":"%s","amount":%s,"description":"test"}
                """.formatted(source, destination, amount);
        return post("/api/v1/transfers", token, key, body);
    }

    private HttpResponse<String> post(String path, String token, String idempotencyKey, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) {
        return jsonMapper.readTree(response.body());
    }

    private String errorCode(HttpResponse<String> response) {
        return json(response).get("code").asString();
    }

    private UUID transferId(HttpResponse<String> response) {
        return UUID.fromString(json(response).get("transferId").asString());
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(errorCode(response)).isEqualTo(code);
    }

    private BigDecimal balance(String accountNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM accounts WHERE account_number = ?", BigDecimal.class, accountNumber);
    }

    private void setBalance(String accountNumber, String balance) {
        jdbcTemplate.update("UPDATE accounts SET balance = ?::numeric WHERE account_number = ?", balance, accountNumber);
    }

    private long rowCount(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }
}
