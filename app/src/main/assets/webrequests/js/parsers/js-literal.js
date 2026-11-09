// A reader for JavaScript OBJECT LITERALS that pages inline as data: JSON's
// grammar plus unquoted identifier keys, single-quoted strings, `void 0`, the
// minified booleans `!0`/`!1`, and the `$R[n]` capture / back-reference scheme
// of a streamed router (X's SSR). Anything else (a function, `new …`, an IIFE)
// is skipped as one balanced expression and reads as undefined, so the object
// around it keeps parsing. Used by twitter.js (the SSR stores) and facebook.js
// (the mobile reel page's player config, a JS literal in a script).
//
// Call parseJsValue(src, { i: <index of the value>, R: {} }); it returns the
// value and leaves state.i after it. Bounded by the input's length.

// Recursive-descent parser for the grammar above. Returns the value
// starting at `state.i`, advancing the index. `state.R` is the per-parse $R
// capture table (numeric id -> already-parsed value). Bounded by the input
// length (the document is a single bounded response body).
function parseJsValue(src, state) {
    skipWs(src, state);
    // $R[n]  (back-reference)  or  $R[n]=<value>  (capture).
    if (src.startsWith("$R[", state.i)) {
        const close = src.indexOf("]", state.i);
        const n = src.slice(state.i + 3, close);
        state.i = close + 1;
        skipWs(src, state);
        if (src[state.i] === "=") {
            state.i++;
            const v = parseJsValue(src, state);
            state.R[n] = v;
            return v;
        }
        return state.R[n]; // may be undefined if it points outside relayRecords
    }
    const c = src[state.i];
    if (c === "{") return parseJsObject(src, state);
    if (c === "[") return parseJsArray(src, state);
    if (c === '"' || c === "'") return parseJsString(src, state);
    if (src.startsWith("!0", state.i)) { state.i += 2; return true; }   // minified true
    if (src.startsWith("!1", state.i)) { state.i += 2; return false; }  // minified false
    if (src.startsWith("void 0", state.i)) { state.i += 6; return undefined; }
    if (src.startsWith("true", state.i)) { state.i += 4; return true; }
    if (src.startsWith("false", state.i)) { state.i += 5; return false; }
    if (src.startsWith("null", state.i)) { state.i += 4; return null; }
    if (c === "-" || c === "+" || c === "." || (c >= "0" && c <= "9")) return parseJsNumber(src, state);
    // Anything else is live JS the streamed router embeds beside the data
    // (an arrow function, `new ReadableStream(...)`, an IIFE). Skip it as one
    // balanced expression so the ENCLOSING object keeps parsing; the value is
    // undefined. Before this, the object ended at the first such value and
    // every later key was lost.
    return skipJsExpression(src, state);
}

// Advance past one JS expression: balanced ()/[]/{} with string literals
// skipped, stopping at a top-level `,` `}` `]` `)` or the end. Bounded by the
// script length. Returns undefined (the value is not data we can use).
function skipJsExpression(src, state) {
    let depth = 0;
    while (state.i < src.length) {
        const c = src[state.i];
        if (c === '"' || c === "'" || c === "`") {
            state.i++;
            while (state.i < src.length && src[state.i] !== c) {
                if (src[state.i] === "\\") state.i++;
                state.i++;
            }
            state.i++;
            continue;
        }
        if (c === "(" || c === "[" || c === "{") depth++;
        else if (c === ")" || c === "]" || c === "}") {
            if (depth === 0) break;
            depth--;
        } else if (c === "," && depth === 0) break;
        state.i++;
    }
    return undefined;
}

function skipWs(src, state) {
    while (state.i < src.length) {
        const c = src[state.i];
        if (c === " " || c === "\n" || c === "\t" || c === "\r") state.i++;
        else break;
    }
}

function parseJsObject(src, state) {
    const o = {};
    state.i++; // {
    skipWs(src, state);
    if (src[state.i] === "}") { state.i++; return o; }
    while (state.i < src.length) {
        skipWs(src, state);
        let key;
        if (src[state.i] === '"' || src[state.i] === "'") {
            key = parseJsString(src, state);
        } else {
            let j = state.i;
            while (j < src.length && src[j] !== ":" && !/\s/.test(src[j])) j++;
            key = src.slice(state.i, j);
            state.i = j;
        }
        skipWs(src, state);
        state.i++; // :
        o[key] = parseJsValue(src, state);
        skipWs(src, state);
        if (src[state.i] === ",") { state.i++; continue; }
        if (src[state.i] === "}") { state.i++; break; }
        break; // unexpected token — stop this object (caller decides)
    }
    return o;
}

function parseJsArray(src, state) {
    const a = [];
    state.i++; // [
    skipWs(src, state);
    if (src[state.i] === "]") { state.i++; return a; }
    while (state.i < src.length) {
        a.push(parseJsValue(src, state));
        skipWs(src, state);
        if (src[state.i] === ",") { state.i++; skipWs(src, state); continue; }
        if (src[state.i] === "]") { state.i++; break; }
        break;
    }
    return a;
}

function parseJsString(src, state) {
    const quote = src[state.i];
    state.i++; // opening quote
    let s = "";
    while (state.i < src.length) {
        const c = src[state.i];
        if (c === "\\") {
            const n = src[state.i + 1];
            if (n === "n") s += "\n";
            else if (n === "t") s += "\t";
            else if (n === "r") s += "\r";
            else if (n === "u") { s += String.fromCharCode(parseInt(src.slice(state.i + 2, state.i + 6), 16)); state.i += 4; }
            else s += n; // \" \\ \/ etc. -> the literal char
            state.i += 2;
            continue;
        }
        if (c === quote) { state.i++; break; }
        s += c;
        state.i++;
    }
    return s;
}

function parseJsNumber(src, state) {
    let j = state.i;
    while (j < src.length && /[-+0-9.eEnx]/.test(src[j])) j++;
    const t = src.slice(state.i, j);
    if (j === state.i) return skipJsExpression(src, state); // never stall on an unknown token
    state.i = j;
    if (t.endsWith("n")) return Number(t.slice(0, -1)); // BigInt literal
    return Number(t);
}

export { parseJsValue, skipJsExpression };
