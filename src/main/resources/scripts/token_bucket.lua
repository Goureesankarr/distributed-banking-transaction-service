-- Token bucket rate limiter.
--
-- Runs as a single atomic Redis script so that N service instances share one
-- bucket per principal without any locking or read-modify-write races.
--
-- KEYS[1] bucket key
-- ARGV[1] capacity (max burst)
-- ARGV[2] refill rate, tokens per second
-- ARGV[3] current time in milliseconds (passed in, so the script stays
--         deterministic and replicates safely)
-- ARGV[4] tokens requested
-- ARGV[5] key TTL in milliseconds
--
-- returns { allowed(0|1), tokens_remaining, retry_after_seconds }

local key      = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill   = tonumber(ARGV[2])
local now      = tonumber(ARGV[3])
local wanted   = tonumber(ARGV[4])
local ttl      = tonumber(ARGV[5])

local bucket = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(bucket[1])
local ts     = tonumber(bucket[2])

if tokens == nil or ts == nil then
    tokens = capacity
    ts = now
end

-- Refill for the time that has passed since the bucket was last touched.
local elapsed = now - ts
if elapsed < 0 then
    elapsed = 0
end
tokens = math.min(capacity, tokens + (elapsed / 1000.0) * refill)

local allowed = 0
local retry_after = 0

if tokens >= wanted then
    tokens = tokens - wanted
    allowed = 1
else
    local deficit = wanted - tokens
    retry_after = math.ceil(deficit / refill)
    if retry_after < 1 then
        retry_after = 1
    end
end

redis.call('HSET', key, 'tokens', tokens, 'ts', now)
redis.call('PEXPIRE', key, ttl)

return { allowed, math.floor(tokens), retry_after }
