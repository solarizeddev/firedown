/**
 * nostr.js — NIP-07 provider for Firedown.
 *
 * Defines `window.nostr` in the PAGE world so any site's "Sign in with
 * extension" / signEvent / nip04 / nip44 flow works exactly like a desktop
 * browser with a signer extension. All key operations are delegated to a
 * native NIP-55 signer app (Amber) via the Java side — see NostrSignerBridge.
 *
 * Why this shape (mirrors youtube/content.js, the confirmed-working
 * page-world export pattern in this GeckoView):
 *   - We run in the isolated content-script sandbox with Xray vision into the
 *     page. To make `window.nostr` visible to PAGE JS we build it on
 *     `window.wrappedJSObject` with createObjectIn/exportFunction (a
 *     content-script `window.nostr = …` would only be visible to us, not the
 *     page).
 *   - Each method returns a PAGE-world Promise (`new w.Promise(...)`) whose
 *     executor is an exported (content-script) function. Inside it we call the
 *     privileged `browser.runtime.sendNativeMessage("nostr", …)`; the native
 *     side does the Amber round-trip and resolves with a JSON ENVELOPE string
 *     ({ok:true,data} | {ok:false,error}). Strings cross the Xray boundary
 *     cleanly, so the native contract is "always resolve with an envelope
 *     string, never reject" — we translate ok/error into resolve/reject here.
 *   - The unsigned event is serialized with the PAGE's own JSON.stringify
 *     (`w.JSON.stringify`) so the arbitrary page object is read with page
 *     semantics (no Xray field-visibility surprises); the result is a plain
 *     string we hand to native verbatim for `Uri.encode`.
 *
 * document_start so the provider exists before any site's load-time
 * `if (window.nostr)` probe. Top frame only (all_frames:false) — NIP-07 login
 * is a top-level affordance; skipping subframes avoids ad/tracker-iframe noise
 * and cross-frame origin ambiguity for the per-origin permission gate.
 */
(function () {
  try {
    const w = window.wrappedJSObject;
    if (!w) return;
    // Respect a real signer already present (don't clobber another provider).
    if (w.nostr) return;

    // Build the native request → page-world Promise. `msg` is a plain
    // content-script object; sendNativeMessage serializes it to the Java
    // MessageDelegate, which returns the envelope string.
    function send(msg) {
      return new w.Promise(exportFunction(function (resolve, reject) {
        try {
          browser.runtime.sendNativeMessage("nostr", msg).then(
            function (envJson) {
              let env;
              try {
                env = JSON.parse(envJson);
              } catch (e) {
                reject(new w.Error("nostr: malformed response"));
                return;
              }
              if (env && env.ok) {
                // data is a hex string (getPublicKey / nip*), a plaintext /
                // ciphertext string, or the signed-event object (signEvent).
                // Primitives cross the Xray boundary by value; only the object
                // form (signEvent) needs cloneInto into the page world.
                const data = (env.data !== null && typeof env.data === "object")
                    ? cloneInto(env.data, w)
                    : env.data;
                resolve(data);
              } else {
                reject(new w.Error(String((env && env.error) || "nostr: request failed")));
              }
            },
            function () {
              reject(new w.Error("nostr: signer bridge unavailable"));
            }
          );
        } catch (e) {
          reject(new w.Error("nostr: signer bridge unavailable"));
        }
      }, w));
    }

    const nostr = createObjectIn(w, { defineAs: "nostr" });

    exportFunction(function () {
      return send({ type: "get_public_key" });
    }, nostr, { defineAs: "getPublicKey" });

    exportFunction(function (event) {
      let json;
      try {
        // Serialize with the PAGE's JSON so the page object is read with page
        // semantics; the result is a primitive string we pass straight to
        // native (→ Uri.encode of the unsigned event).
        json = w.JSON.stringify(event);
      } catch (e) {
        json = null;
      }
      if (!json) {
        return w.Promise.reject(new w.Error("nostr: invalid event"));
      }
      return send({ type: "sign_event", eventJson: json });
    }, nostr, { defineAs: "signEvent" });

    const nip04 = createObjectIn(nostr, { defineAs: "nip04" });
    exportFunction(function (pubkey, plaintext) {
      return send({ type: "nip04_encrypt", pubkey: String(pubkey), plaintext: String(plaintext) });
    }, nip04, { defineAs: "encrypt" });
    exportFunction(function (pubkey, ciphertext) {
      return send({ type: "nip04_decrypt", pubkey: String(pubkey), ciphertext: String(ciphertext) });
    }, nip04, { defineAs: "decrypt" });

    const nip44 = createObjectIn(nostr, { defineAs: "nip44" });
    exportFunction(function (pubkey, plaintext) {
      return send({ type: "nip44_encrypt", pubkey: String(pubkey), plaintext: String(plaintext) });
    }, nip44, { defineAs: "encrypt" });
    exportFunction(function (pubkey, ciphertext) {
      return send({ type: "nip44_decrypt", pubkey: String(pubkey), ciphertext: String(ciphertext) });
    }, nip44, { defineAs: "decrypt" });
  } catch (e) {
    // Never let provider setup break the page.
  }
})();
