package dev.omatheusmesmo.vintelli.jsonrpc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TestSupport {

    static final long AWAIT_MILLIS = 10_000L;

    private TestSupport() {
    }

    static Map<String, Object> params() {
        return new LinkedHashMap<>();
    }

    static Map<String, Object> params(String key, Object value) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(key, value);
        return params;
    }

    static Map<String, Object> params(String firstKey, Object firstValue, Object... rest) {
        Map<String, Object> params = params(firstKey, firstValue);
        for (int index = 0; index + 1 < rest.length; index += 2) {
            params.put((String) rest[index], rest[index + 1]);
        }
        return params;
    }

    static List<Object> listOf(Object... items) {
        List<Object> list = new ArrayList<>();
        for (Object item : items) {
            list.add(item);
        }
        return list;
    }

    static Object await(JsonRpcEndpoint.PendingRequest pending) throws Exception {
        return pending.response().get(AWAIT_MILLIS, TimeUnit.MILLISECONDS);
    }

    static <T extends Throwable> T awaitFailure(JsonRpcEndpoint.PendingRequest pending, Class<T> type)
            throws Exception {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> pending.response().get(AWAIT_MILLIS, TimeUnit.MILLISECONDS),
                "pending request completed successfully, expected a failure");
        return assertInstanceOf(type, failure.getCause());
    }

    static void awaitLatch(CountDownLatch latch) throws InterruptedException, TimeoutException {
        if (!latch.await(AWAIT_MILLIS, TimeUnit.MILLISECONDS)) {
            throw new TimeoutException("condition was not reached within "
                    + AWAIT_MILLIS + " ms");
        }
    }

    static void awaitCondition(Supplier<Boolean> condition) throws InterruptedException, TimeoutException {
        awaitCondition(condition, "condition was not reached within " + AWAIT_MILLIS + " ms");
    }

    static void awaitCondition(Supplier<Boolean> condition, String message)
            throws InterruptedException, TimeoutException {
        long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            Thread.sleep(20L);
        }
        throw new TimeoutException(message);
    }

    static void assertNoUnexpectedErrors(List<Throwable> errors) {
        List<Throwable> unexpected = new ArrayList<>();
        for (Throwable error : errors) {
            if (!(error instanceof JsonRpcProtocolException)) {
                unexpected.add(error);
            }
        }
        assertTrue(unexpected.isEmpty(), () -> "unexpected transport errors: " + unexpected);
    }
}
