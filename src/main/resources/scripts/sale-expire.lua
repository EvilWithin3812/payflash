-- Returns the unit of an expired, unpaid hold to the pool. The buyer stays in the buyers set: one hold
-- per person per sale, so a bot cannot hold a unit, let it lapse and grab it again for the whole sale.
-- Idempotent: the expired set records each buyer once, so the unit goes back exactly once.
--
-- KEYS[1] = sale:{id}:units
-- KEYS[2] = sale:{id}:buyers
-- KEYS[3] = sale:{id}:expired
-- ARGV[1] = unit number
-- ARGV[2] = user id
--
-- Returns 1 if the unit went back, 0 if it had already been returned.
if redis.call('SISMEMBER', KEYS[2], ARGV[2]) == 1 and redis.call('SADD', KEYS[3], ARGV[2]) == 1 then
    redis.call('RPUSH', KEYS[1], ARGV[1])
    return 1
end
return 0
