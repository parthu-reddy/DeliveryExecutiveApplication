package com.fooddelivery.delivery.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.dto.maps.LiveRiderLocation;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs {@link RiderLocationSnapshotWriter#SCRIPT} itself -- not a copy -- with {@code redis.call}
 * (GET, SET .. PX) and {@code cjson.decode} backed by a map, the way Redis evaluates it. The stored
 * value is the real Jackson serialisation of {@link LiveRiderLocation}, so renaming its
 * {@code observedAtEpochMs} component would break the ordering check here, not silently in Redis.
 */
class RiderLocationSnapshotWriterScriptTest {

    private static final String ACTIVE_KEY = "driver:active_order:rider-1";
    private static final String ORDER = "order-1";
    private static final String SNAPSHOT_KEY = LiveRiderLocation.redisKey(ORDER);

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, String> store = new HashMap<>();
    private final List<List<String>> sets = new ArrayList<>();

    @Test
    void writesThePositionOfTheRidersCurrentOrderWithItsExpiry() throws Exception {
        store.put(ACTIVE_KEY, ORDER);
        String json = snapshot(1_000);

        assertThat(run(json, 1_000, 25_000)).isEqualTo(1);

        assertThat(store).containsEntry(SNAPSHOT_KEY, json);
        assertThat(sets).containsExactly(List.of(SNAPSHOT_KEY, json, "PX", "25000"));
    }

    @Test
    void refusesARiderWithNoActiveOrder() throws Exception {
        assertThat(run(snapshot(1_000), 1_000, 25_000)).isZero();
        assertThat(sets).isEmpty();
    }

    @Test
    void refusesARiderNowOnAnotherOrder() throws Exception {
        store.put(ACTIVE_KEY, "order-2");
        assertThat(run(snapshot(1_000), 1_000, 25_000)).isZero();
        assertThat(store).doesNotContainKey(SNAPSHOT_KEY);
    }

    @Test
    void neverReplacesANewerPositionWithAnOlderOne() throws Exception {
        store.put(ACTIVE_KEY, ORDER);
        String newer = snapshot(2_000);
        store.put(SNAPSHOT_KEY, newer);

        assertThat(run(snapshot(1_000), 1_000, 25_000)).isZero();

        assertThat(store).containsEntry(SNAPSHOT_KEY, newer);
        assertThat(sets).isEmpty();
    }

    @Test
    void replacesAnOlderPosition() throws Exception {
        store.put(ACTIVE_KEY, ORDER);
        store.put(SNAPSHOT_KEY, snapshot(1_000));
        String newer = snapshot(2_000);

        assertThat(run(newer, 2_000, 29_000)).isEqualTo(1);

        assertThat(store).containsEntry(SNAPSHOT_KEY, newer);
    }

    @Test
    void replacesAnUnreadableSnapshot() throws Exception {
        store.put(ACTIVE_KEY, ORDER);
        store.put(SNAPSHOT_KEY, "not json");
        String json = snapshot(1_000);

        assertThat(run(json, 1_000, 25_000)).isEqualTo(1);

        assertThat(store).containsEntry(SNAPSHOT_KEY, json);
    }

    private String snapshot(long observedAt) throws Exception {
        return mapper.writeValueAsString(new LiveRiderLocation("rider-1", ORDER, "BLR", 12.97, 77.59, observedAt));
    }

    private long run(String json, long observedAt, long px) {
        Globals lua = JsePlatform.standardGlobals();
        LuaTable redis = new LuaTable();
        redis.set("call", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String command = args.checkjstring(1);
                if ("GET".equals(command)) {
                    String value = store.get(args.checkjstring(2));
                    return value == null ? LuaValue.FALSE : LuaValue.valueOf(value);
                }
                if ("SET".equals(command) && args.narg() == 5 && "PX".equals(args.checkjstring(4))) {
                    store.put(args.checkjstring(2), args.checkjstring(3));
                    sets.add(List.of(args.checkjstring(2), args.checkjstring(3), "PX", args.checkjstring(5)));
                    return LuaValue.valueOf("OK");
                }
                throw new LuaError("unexpected redis.call " + command);
            }
        });
        LuaTable cjson = new LuaTable();
        cjson.set("decode", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue text) {
                try {
                    JsonNode node = mapper.readTree(text.checkjstring());
                    LuaTable table = new LuaTable();
                    node.fields().forEachRemaining(field -> table.set(field.getKey(), field.getValue().isNumber()
                            ? LuaValue.valueOf(field.getValue().doubleValue())
                            : LuaValue.valueOf(field.getValue().asText())));
                    return table;
                } catch (Exception unreadable) {
                    throw new LuaError("cjson: " + unreadable.getMessage());
                }
            }
        });
        lua.set("redis", redis);
        lua.set("cjson", cjson);
        lua.set("KEYS", LuaValue.listOf(new LuaValue[] {LuaValue.valueOf(ACTIVE_KEY), LuaValue.valueOf(SNAPSHOT_KEY)}));
        lua.set("ARGV", LuaValue.listOf(new LuaValue[] {LuaValue.valueOf(ORDER), LuaValue.valueOf(json),
                LuaValue.valueOf(Long.toString(observedAt)), LuaValue.valueOf(Long.toString(px))}));
        return lua.load(RiderLocationSnapshotWriter.SCRIPT, "snapshot").call().tolong();
    }
}
