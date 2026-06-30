-- Token bucket algorithm.
-- KEYS[1] = bucket key
-- ARGV[1] = capacity (max tokens)
-- ARGV[2] = refill rate (tokens per second)
-- ARGV[3] = now in ms
-- ARGV[4] = tokens requested (usually 1)
-- Returns: { allowed (1/0), remainingTokens, retryAfterMs }

local key       = KEYS[1]
local capacity  = tonumber(ARGV[1])
local refill    = tonumber(ARGV[2])  -- tokens per second
local now       = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])

local data = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(data[1])
local ts     = tonumber(data[2])

if tokens == nil then
  tokens = capacity
  ts = now
end

-- Refill based on elapsed time
local elapsed = math.max(0, now - ts) / 1000.0
tokens = math.min(capacity, tokens + (elapsed * refill))

local allowed = 0
local retry = 0
if tokens >= requested then
  tokens = tokens - requested
  allowed = 1
else
  local deficit = requested - tokens
  retry = math.ceil((deficit / refill) * 1000)
end

redis.call('HMSET', key, 'tokens', tokens, 'ts', now)
-- TTL: time to fully refill from empty, plus a small buffer
local ttl = math.ceil((capacity / refill) * 1000) + 1000
redis.call('PEXPIRE', key, ttl)

return { allowed, math.floor(tokens), retry }
