-- Returns a unit to the pool when a hold expires (or a reservation could not be queued).
-- Idempotent: the unit goes back only if the user was still registered as a buyer, so releasing
-- the same hold twice can never put a duplicate unit into the list.
--
-- KEYS[1] = sale:{id}:units
-- KEYS[2] = sale:{id}:buyers
-- ARGV[1] = unit number
-- ARGV[2] = user id
--
-- Returns 1 if released, 0 if it had already been released.
if redis.call('SREM', KEYS[2], ARGV[2]) == 1 then
    redis.call('RPUSH', KEYS[1], ARGV[1])
    return 1
end
return 0
