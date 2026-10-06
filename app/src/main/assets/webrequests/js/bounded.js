// Bounded state primitives for the whole background page — the catcher
// (requests.js) and the parser tree (parsers/common.js re-exports them) share
// this one file so neither imports the other for it.
//
// The background page lives as long as the app process, so every long-lived
// collection needs a bound. Before these, each parser hand-rolled its own:
// eight `processedXUrls` Sets with a setTimeout per entry, five FIFO caches,
// three expired-only sweeps that were not caps at all, a setInterval, and
// one cache whose TTL timer could delete a re-created entry — and the audit
// (2026-10) found the drift that copies accumulate (acast/substack grew past
// their "cap", nicoMeta too, interceptedResponses had no removal at all).
//
// Both primitives bound the SAME way, with NO timers: a TTL checked at
// lookup, plus a HARD FIFO cap enforced on every insert (expired entries go
// first, then the oldest). A stale entry nobody asks about again can
// therefore linger only up to the cap — a bound in COUNT, which is the only
// bound memory needs; a key someone does ask about is judged by the TTL.
// Insertion order is recency: an insert of an existing key moves it to the
// tail (Map.set alone would leave it in its old slot and let the cap evict a
// live entry as "oldest").

class ClaimSet {
    constructor(ttlMs, max) {
        this.ttl = ttlMs;
        this.max = max;
        this.map = new Map();   // key -> expiry (ms)
    }
    // Check-and-claim in ONE synchronous step: true when the key was not live
    // (absent or expired) and is now claimed; false when a live claim exists
    // (which is left untouched — the TTL counts from the first claim).
    claim(key, ttlMs = this.ttl) {
        const now = Date.now();
        const exp = this.map.get(key);
        if (exp !== undefined && exp > now) return false;
        this.map.delete(key);
        this.map.set(key, now + ttlMs);
        this.prune(now);
        return true;
    }
    // Insert or refresh (the TTL counts from now).
    add(key, ttlMs = this.ttl) {
        this.map.delete(key);
        this.map.set(key, Date.now() + ttlMs);
        this.prune();
    }
    has(key) {
        const exp = this.map.get(key);
        if (exp === undefined) return false;
        if (exp <= Date.now()) { this.map.delete(key); return false; }
        return true;
    }
    release(key) { this.map.delete(key); }
    // Live keys, oldest first (expired ones are dropped as they are met).
    *keys() {
        const now = Date.now();
        for (const [k, exp] of this.map) {
            if (exp > now) yield k;
            else this.map.delete(k);
        }
    }
    prune(now = Date.now()) {
        if (this.map.size <= this.max) return;
        for (const [k, exp] of this.map) { if (exp <= now) this.map.delete(k); }
        while (this.map.size > this.max) this.map.delete(this.map.keys().next().value);
    }
    get size() { return this.map.size; }
    clear() { this.map.clear(); }
}

class MetaCache {
    constructor(max, ttlMs = Infinity) {
        this.max = max;
        this.ttl = ttlMs;
        this.map = new Map();   // key -> { v, exp }
    }
    set(key, value, ttlMs = this.ttl) {
        this.map.delete(key);
        this.map.set(key, { v: value, exp: Date.now() + ttlMs });
        this.prune();
    }
    get(key) {
        const e = this.map.get(key);
        if (e === undefined) return undefined;
        if (e.exp <= Date.now()) { this.map.delete(key); return undefined; }
        return e.v;
    }
    has(key) { return this.get(key) !== undefined; }
    delete(key) { this.map.delete(key); }
    prune(now = Date.now()) {
        if (this.map.size <= this.max) return;
        for (const [k, e] of this.map) { if (e.exp <= now) this.map.delete(k); }
        while (this.map.size > this.max) this.map.delete(this.map.keys().next().value);
    }
    // Live entries as [key, value], oldest first.
    *[Symbol.iterator]() {
        const now = Date.now();
        for (const [k, e] of this.map) {
            if (e.exp > now) yield [k, e.v];
            else this.map.delete(k);
        }
    }
    get size() { return this.map.size; }
    clear() { this.map.clear(); }
}


export { ClaimSet, MetaCache };
