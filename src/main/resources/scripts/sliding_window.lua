-- Sliding window log algorithm.
-- KEYS[1] = bucket key (e.g. "rl:sw:<id>")
-- ARGV[1] = window size in ms
-- ARGV[2] = max requests per window
-- ARGV[3] = current timestamp in ms
-- ARGV[4] = unique request id (for ZSET member uniqueness)
-- Returns: { allowed (1/0), remaining, retryAfterMs }

local key      = KEYS[1]
local window   = tonumber(ARGV[1])
local limit    = tonumber(ARGV[2])
local now      = tonumber(ARGV[3])
local reqId    = ARGV[4]
local cutoff   = now - window

-- Drop entries older than the window
redis.call('ZREMRANGEBYSCORE', key, 0, cutoff)

local count = tonumber(redis.call('ZCARD', key))

if count < limit then
  redis.call('ZADD', key, now, reqId)
  redis.call('PEXPIRE', key, window)
  return { 1, limit - count - 1, 0 }
else
  -- Find the oldest hit; user must wait until it falls out of the window
  local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
  local retry = window
  if oldest[2] then
    retry = (tonumber(oldest[2]) + window) - now
    if retry < 0 then retry = 0 end
  end
  return { 0, 0, retry }
end
