package com.svp.engine;

import com.svp.engine.model.VirtualRequest;
import com.svp.engine.model.VirtualResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** PUT / GET / MERGE / DELETE / LIST semantics within one session. */
class StatefulBehaviourTest {

    private final StubMatchingEngine engine = TestEngines.seeded();

    private VirtualResponse call(String session, String method, String path, Object body) {
        return engine.matchAndExecute(new VirtualRequest(session, "HTTP", method, path, Map.of(), body));
    }

    @Test
    void fullLifecycle() {
        VirtualResponse created = call("life", "POST", "/api/v1/accounts",
                Map.of("account_holder", "Jane", "initial_deposit", 10, "currency", "USD"));
        String id = (String) created.bodyMap().get("account_id");
        assertThat(id).isEqualTo("ACC-1001");
        assertThat(created.stateMutated()).isTrue();

        VirtualResponse read = call("life", "GET", "/api/v1/accounts/" + id, null);
        assertThat(read.bodyMap()).containsEntry("account_holder", "Jane");
        assertThat(read.stateMutated()).isFalse();

        VirtualResponse updated = call("life", "PUT", "/api/v1/accounts/" + id,
                Map.of("status", "FROZEN", "flags", Map.of("aml", true)));
        assertThat(updated.bodyMap()).containsEntry("status", "FROZEN").containsEntry("account_holder", "Jane");
        assertThat(call("life", "GET", "/api/v1/accounts/" + id, null).bodyMap()).containsEntry("status", "FROZEN");

        VirtualResponse list = call("life", "GET", "/api/v1/accounts", null);
        assertThat(list.bodyMap()).containsEntry("count", 1);
        assertThat((List<?>) list.bodyMap().get("items")).hasSize(1);

        VirtualResponse deleted = call("life", "DELETE", "/api/v1/accounts/" + id, null);
        assertThat(deleted.statusCode()).isEqualTo(204);
        assertThat(deleted.body()).isNull();
        assertThat(deleted.stateMutated()).isTrue();

        assertThat(call("life", "GET", "/api/v1/accounts/" + id, null).statusCode()).isEqualTo(404);
        assertThat(call("life", "DELETE", "/api/v1/accounts/" + id, null).statusCode()).isEqualTo(404);
        assertThat(call("life", "PUT", "/api/v1/accounts/" + id, Map.of("x", 1)).statusCode()).isEqualTo(404);
    }

    @Test
    void mergePatchNullRemovesAField() {
        call("m", "POST", "/api/v1/accounts", Map.of("account_id", "A1", "currency", "USD", "account_holder", "X"));
        java.util.HashMap<String, Object> patch = new java.util.HashMap<>();
        patch.put("account_holder", null);
        assertThat(call("m", "PUT", "/api/v1/accounts/A1", patch).bodyMap()).doesNotContainKey("account_holder");
    }

    @Test
    void storedValuesCannotBeMutatedThroughAResponse() {
        call("imm", "POST", "/api/v1/accounts", Map.of("account_id", "A1", "currency", "USD", "account_holder", "X"));
        Map<String, Object> first = call("imm", "GET", "/api/v1/accounts/A1", null).bodyMap();
        try {
            first.put("account_holder", "HACKED");
        } catch (UnsupportedOperationException expected) {
            // frozen value: exactly what we want
        }
        assertThat(call("imm", "GET", "/api/v1/accounts/A1", null).bodyMap()).containsEntry("account_holder", "X");
    }

    @Test
    void paymentsAreStoredUnderTheGeneratedTransactionId() {
        VirtualResponse paid = engine.matchAndExecute(new VirtualRequest("pay", "HTTP", "POST", "/api/v1/payments",
                Map.of("Content-Type", "application/json"),
                Map.of("merchant_id", "MERCH_1092", "amount", 42.0, "card", Map.of("network", "VISA"))));
        String txn = (String) paid.bodyMap().get("transaction_id");
        VirtualResponse fetched = call("pay", "GET", "/api/v1/payments/" + txn, null);
        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(fetched.bodyMap()).containsEntry("amount", 42.0).containsEntry("status", "APPROVED");
    }
}
