package com.payflash.sale;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Flash-sale stock in Redis. The {braces} in key names are Redis Cluster hash tags: every key of one
 * sale lands in the same slot, which a multi-key Lua script requires on a cluster.
 */
@Component
public class SaleInventory {

    public static final long SOLD_OUT = -1;
    public static final long ALREADY_RESERVED = -2;

    private static final int PUSH_CHUNK = 1_000;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> reserveScript;
    private final DefaultRedisScript<Long> releaseScript;
    private final DefaultRedisScript<Long> expireScript;

    public SaleInventory(StringRedisTemplate redis) {
        this.redis = redis;
        this.reserveScript = script("scripts/sale-reserve.lua");
        this.releaseScript = script("scripts/sale-release.lua");
        this.expireScript = script("scripts/sale-expire.lua");
    }

    /** @return unit number, {@link #SOLD_OUT} or {@link #ALREADY_RESERVED} */
    public long reserve(long saleId, long userId) {
        Long result = redis.execute(reserveScript, List.of(unitsKey(saleId), buyersKey(saleId)), String.valueOf(userId));
        return result == null ? SOLD_OUT : result;
    }

    /** @return true if the unit went back into the pool (false if it was already released) */
    public boolean release(long saleId, long userId, int unitNo) {
        Long result = redis.execute(releaseScript, List.of(unitsKey(saleId), buyersKey(saleId)),
                String.valueOf(unitNo), String.valueOf(userId));
        return result != null && result == 1;
    }

    /**
     * Returns the unit of a hold that expired unpaid. Unlike {@link #release}, the buyer keeps their place in
     * the buyers set and cannot reserve again in this sale.
     *
     * @return true if the unit went back into the pool (false if it was already returned)
     */
    public boolean expire(long saleId, long userId, int unitNo) {
        Long result = redis.execute(expireScript, List.of(unitsKey(saleId), buyersKey(saleId), expiredKey(saleId)),
                String.valueOf(unitNo), String.valueOf(userId));
        return result != null && result == 1;
    }

    public long remaining(long saleId) {
        Long size = redis.opsForList().size(unitsKey(saleId));
        return size == null ? 0 : size;
    }

    public boolean isInitialised(long saleId) {
        return Boolean.TRUE.equals(redis.hasKey(initialisedKey(saleId)));
    }

    /**
     * (Re)builds the pool: every unit not in {@code takenUnits} is free, every user in {@code buyers} is
     * registered. Runs in a MULTI/EXEC transaction so readers never see a half-built pool.
     */
    public void rebuild(long saleId, int totalUnits, Collection<Integer> takenUnits, Collection<Long> buyers) {
        var taken = new java.util.HashSet<>(takenUnits);
        List<String> free = new ArrayList<>(totalUnits);
        for (int unit = 1; unit <= totalUnits; unit++) {
            if (!taken.contains(unit)) {
                free.add(String.valueOf(unit));
            }
        }
        redis.execute(new org.springframework.data.redis.core.SessionCallback<List<Object>>() {
            @Override
            @SuppressWarnings({"unchecked", "rawtypes"})
            public List<Object> execute(org.springframework.data.redis.core.RedisOperations ops) {
                ops.multi();
                ops.delete(List.of(unitsKey(saleId), buyersKey(saleId)));
                for (int i = 0; i < free.size(); i += PUSH_CHUNK) {
                    ops.opsForList().rightPushAll(unitsKey(saleId), free.subList(i, Math.min(i + PUSH_CHUNK, free.size())));
                }
                if (!buyers.isEmpty()) {
                    ops.opsForSet().add(buyersKey(saleId), buyers.stream().map(String::valueOf).toArray(String[]::new));
                }
                // Buyers whose hold expired keep their one chance used up.
                ops.opsForSet().unionAndStore(buyersKey(saleId), expiredKey(saleId), buyersKey(saleId));
                ops.opsForValue().set(initialisedKey(saleId), "1");
                return ops.exec();
            }
        });
    }

    // ---------------------------------------------------------------- hold lookup (status polling)

    /** Remembers who owns a hold until the order row exists, so the client can poll its status. */
    public void rememberHold(UUID holdToken, long saleId, long userId, int unitNo, Duration ttl) {
        redis.opsForValue().set(holdKey(holdToken), saleId + ":" + userId + ":" + unitNo, ttl);
    }

    public Optional<PendingHold> pendingHold(UUID holdToken) {
        String value = redis.opsForValue().get(holdKey(holdToken));
        if (value == null) {
            return Optional.empty();
        }
        String[] parts = value.split(":");
        return Optional.of(new PendingHold(Long.parseLong(parts[0]), Long.parseLong(parts[1]),
                Integer.parseInt(parts[2])));
    }

    public record PendingHold(long saleId, long userId, int unitNo) {
    }

    // ---------------------------------------------------------------- keys

    static String unitsKey(long saleId) {
        return "sale:{" + saleId + "}:units";
    }

    static String buyersKey(long saleId) {
        return "sale:{" + saleId + "}:buyers";
    }

    static String expiredKey(long saleId) {
        return "sale:{" + saleId + "}:expired";
    }

    static String initialisedKey(long saleId) {
        return "sale:{" + saleId + "}:ready";
    }

    static String holdKey(UUID holdToken) {
        return "sale:hold:" + holdToken;
    }

    private static DefaultRedisScript<Long> script(String path) {
        var script = new DefaultRedisScript<Long>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }
}
