-- Atomically reserves one unit of a flash sale for a user.
-- Redis runs a script without interleaving any other command, so check-then-act is safe here
-- with no locks: thousands of concurrent calls are serialised by Redis itself.
--
-- KEYS[1] = sale:{id}:units   LIST of free unit numbers
-- KEYS[2] = sale:{id}:buyers  SET of user ids holding or owning a unit
-- ARGV[1] = user id
--
-- Returns the reserved unit number (> 0), -1 if sold out, -2 if this user already has one.
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return -2
end
local unit = redis.call('LPOP', KEYS[1])
if not unit then
    return -1
end
redis.call('SADD', KEYS[2], ARGV[1])
return tonumber(unit)
