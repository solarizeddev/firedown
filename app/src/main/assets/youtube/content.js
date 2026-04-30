/**
 * content.js — YouTube page context BotGuard PO token generator.
 * 1. Visibility override (all youtube.com pages)
 * 2. BotGuard PO token generator (only on robots.txt — no CSP)
 * 3. Embed detection
 */

// VISIBILITY OVERRIDE (all pages)
(function() {
    const s = document.createElement('script');
    s.textContent = `(function(){
        try{Object.defineProperty(document,'hidden',{get:()=>false,configurable:false});
        Object.defineProperty(document,'visibilityState',{get:()=>'visible',configurable:false});
        Object.defineProperty(document,'webkitHidden',{get:()=>false,configurable:false});
        Object.defineProperty(document,'webkitVisibilityState',{get:()=>'visible',configurable:false});}catch(e){}
        const d=document.addEventListener.bind(document);
        document.addEventListener=function(t,l,o){if(t==='visibilitychange'||t==='webkitvisibilitychange')return;return d(t,l,o);};
        const w=window.addEventListener.bind(window);
        window.addEventListener=function(t,l,o){if(t==='pagehide'||t==='blur')return;return w(t,l,o);};
        const p=document.dispatchEvent.bind(document);
        document.dispatchEvent=function(e){if(e&&e.type==='visibilitychange')return true;return p(e);};
    })();`;
    (document.head || document.documentElement).appendChild(s);
    s.remove();
})();

// BOTGUARD ON ROBOTS.TXT ONLY
if (location.pathname === '/robots.txt') {

    const BGUTILS_CODE = "(() => {\n  var __defProp = Object.defineProperty;\n  var __export = (target, all) => {\n    for (var name in all)\n      __defProp(target, name, { get: all[name], enumerable: true });\n  };\n\n  // node_modules/bgutils-js/dist/core/index.js\n  var core_exports = {};\n  __export(core_exports, {\n    BotGuardClient: () => BotGuardClient,\n    Challenge: () => challengeFetcher_exports,\n    PoToken: () => webPoClient_exports,\n    WebPoMinter: () => WebPoMinter\n  });\n\n  // node_modules/bgutils-js/dist/core/challengeFetcher.js\n  var challengeFetcher_exports = {};\n  __export(challengeFetcher_exports, {\n    create: () => create,\n    descramble: () => descramble,\n    parseChallengeData: () => parseChallengeData\n  });\n\n  // node_modules/bgutils-js/dist/utils/constants.js\n  var GOOG_BASE_URL = \"https://jnn-pa.googleapis.com\";\n  var YT_BASE_URL = \"https://www.youtube.com\";\n  var GOOG_API_KEY = \"AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw\";\n  var USER_AGENT = \"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36(KHTML, like Gecko)\";\n\n  // node_modules/bgutils-js/dist/utils/helpers.js\n  var base64urlCharRegex = /[-_.]/g;\n  var base64urlToBase64Map = {\n    \"-\": \"+\",\n    _: \"/\",\n    \".\": \"=\"\n  };\n  var DeferredPromise = class {\n    constructor() {\n      this.promise = new Promise((resolve, reject) => {\n        this.resolve = resolve;\n        this.reject = reject;\n      });\n    }\n  };\n  var BGError = class extends TypeError {\n    constructor(code, message, info) {\n      super(message);\n      this.name = \"BGError\";\n      this.code = code;\n      if (info)\n        this.info = info;\n    }\n  };\n  function base64ToU8(base64) {\n    let base64Mod;\n    if (base64urlCharRegex.test(base64)) {\n      base64Mod = base64.replace(base64urlCharRegex, function(match) {\n        return base64urlToBase64Map[match];\n      });\n    } else {\n      base64Mod = base64;\n    }\n    base64Mod = atob(base64Mod);\n    return new Uint8Array([...base64Mod].map((char) => char.charCodeAt(0)));\n  }\n  function u8ToBase64(u8, base64url = false) {\n    const result = btoa(String.fromCharCode(...u8));\n    if (base64url) {\n      return result.replace(/\\+/g, \"-\").replace(/\\//g, \"_\");\n    }\n    return result;\n  }\n  function isBrowser() {\n    const isBrowser2 = typeof window !== \"undefined\" && typeof window.document !== \"undefined\" && typeof window.document.createElement !== \"undefined\" && typeof window.HTMLElement !== \"undefined\" && typeof window.navigator !== \"undefined\" && typeof window.getComputedStyle === \"function\" && typeof window.requestAnimationFrame === \"function\" && typeof window.matchMedia === \"function\";\n    const hasValidWindow = Object.getOwnPropertyDescriptor(globalThis, \"window\")?.get?.toString().includes(\"[native code]\") ?? false;\n    return isBrowser2 && hasValidWindow;\n  }\n  function getHeaders() {\n    const headers = {\n      \"content-type\": \"application/json+protobuf\",\n      \"x-goog-api-key\": GOOG_API_KEY,\n      \"x-user-agent\": \"grpc-web-javascript/0.1\"\n    };\n    if (!isBrowser()) {\n      headers[\"user-agent\"] = USER_AGENT;\n    }\n    return headers;\n  }\n  function buildURL(endpointName, useYouTubeAPI) {\n    return `${useYouTubeAPI ? YT_BASE_URL : GOOG_BASE_URL}/${useYouTubeAPI ? \"api/jnn/v1\" : \"$rpc/google.internal.waa.v1.Waa\"}/${endpointName}`;\n  }\n\n  // node_modules/bgutils-js/dist/core/challengeFetcher.js\n  async function create(bgConfig, interpreterHash) {\n    const requestKey = bgConfig.requestKey;\n    if (!bgConfig.fetch)\n      throw new BGError(\"BAD_CONFIG\", \"No fetch function provided\");\n    const payload = [requestKey];\n    if (interpreterHash)\n      payload.push(interpreterHash);\n    const response = await bgConfig.fetch(buildURL(\"Create\", bgConfig.useYouTubeAPI), {\n      method: \"POST\",\n      headers: getHeaders(),\n      body: JSON.stringify(payload)\n    });\n    if (!response.ok)\n      throw new BGError(\"REQUEST_FAILED\", \"Failed to fetch challenge\", { status: response.status });\n    const rawData = await response.json();\n    return parseChallengeData(rawData);\n  }\n  function parseChallengeData(rawData) {\n    let challengeData = [];\n    if (rawData.length > 1 && typeof rawData[1] === \"string\") {\n      const descrambled = descramble(rawData[1]);\n      challengeData = JSON.parse(descrambled || \"[]\");\n    } else if (rawData.length && typeof rawData[0] === \"object\") {\n      challengeData = rawData[0];\n    }\n    const [messageId, wrappedScript, wrappedUrl, interpreterHash, program, globalName, , clientExperimentsStateBlob] = challengeData;\n    const privateDoNotAccessOrElseSafeScriptWrappedValue = Array.isArray(wrappedScript) ? wrappedScript.find((value) => value && typeof value === \"string\") : null;\n    const privateDoNotAccessOrElseTrustedResourceUrlWrappedValue = Array.isArray(wrappedUrl) ? wrappedUrl.find((value) => value && typeof value === \"string\") : null;\n    return {\n      messageId,\n      interpreterJavascript: {\n        privateDoNotAccessOrElseSafeScriptWrappedValue,\n        privateDoNotAccessOrElseTrustedResourceUrlWrappedValue\n      },\n      interpreterHash,\n      program,\n      globalName,\n      clientExperimentsStateBlob\n    };\n  }\n  function descramble(scrambledChallenge) {\n    const buffer = base64ToU8(scrambledChallenge);\n    if (buffer.length)\n      return new TextDecoder().decode(buffer.map((b) => b + 97));\n  }\n\n  // node_modules/bgutils-js/dist/core/webPoClient.js\n  var webPoClient_exports = {};\n  __export(webPoClient_exports, {\n    decodeColdStartToken: () => decodeColdStartToken,\n    generate: () => generate,\n    generateColdStartToken: () => generateColdStartToken,\n    generatePlaceholder: () => generatePlaceholder\n  });\n\n  // node_modules/bgutils-js/dist/core/botGuardClient.js\n  var BotGuardClient = class _BotGuardClient {\n    constructor(options) {\n      this.deferredVmFunctions = new DeferredPromise();\n      this.defaultTimeout = 3e3;\n      this.userInteractionElement = options.userInteractionElement;\n      this.vm = options.globalObj[options.globalName];\n      this.program = options.program;\n    }\n    /**\n     * Factory method to create and load a BotGuardClient instance.\n     * @param options - Configuration options for the BotGuardClient.\n     * @returns A promise that resolves to a loaded BotGuardClient instance.\n     */\n    static async create(options) {\n      return await new _BotGuardClient(options).load();\n    }\n    async load() {\n      if (!this.vm)\n        throw new BGError(\"VM_INIT\", \"VM not found\");\n      if (!this.vm.a)\n        throw new BGError(\"VM_INIT\", \"VM init function not found\");\n      const vmFunctionsCallback = (asyncSnapshotFunction, shutdownFunction, passEventFunction, checkCameraFunction) => {\n        this.deferredVmFunctions.resolve({\n          asyncSnapshotFunction,\n          shutdownFunction,\n          passEventFunction,\n          checkCameraFunction\n        });\n      };\n      try {\n        this.syncSnapshotFunction = await this.vm.a(this.program, vmFunctionsCallback, true, this.userInteractionElement, () => {\n        }, [[], []])[0];\n      } catch (error) {\n        throw new BGError(\"VM_ERROR\", \"Could not load program\", { error });\n      }\n      return this;\n    }\n    /**\n     * Takes a snapshot asynchronously.\n     * @returns The snapshot result.\n     * @example\n     * ```ts\n     * const result = await botguard.snapshot({\n     *   contentBinding: {\n     *     c: \"a=6&a2=10&b=SZWDwKVIuixOp7Y4euGTgwckbJA&c=1729143849&d=1&t=7200&c1a=1&c6a=1&c6b=1&hh=HrMb5mRWTyxGJphDr0nW2Oxonh0_wl2BDqWuLHyeKLo\",\n     *     e: \"ENGAGEMENT_TYPE_VIDEO_LIKE\",\n     *     encryptedVideoId: \"P-vC09ZJcnM\"\n     *    }\n     * });\n     *\n     * console.log(result);\n     * ```\n     */\n    async snapshot(args, timeout = 3e3) {\n      return await Promise.race([\n        new Promise(async (resolve, reject) => {\n          const vmFunctions = await this.deferredVmFunctions.promise;\n          if (!vmFunctions.asyncSnapshotFunction)\n            return reject(new BGError(\"ASYNC_SNAPSHOT\", \"Asynchronous snapshot function not found\"));\n          await vmFunctions.asyncSnapshotFunction((response) => resolve(response), [\n            args.contentBinding,\n            args.signedTimestamp,\n            args.webPoSignalOutput,\n            args.skipPrivacyBuffer\n          ]);\n        }),\n        new Promise((_, reject) => setTimeout(() => reject(new BGError(\"TIMEOUT\", \"VM operation timed out\")), timeout))\n      ]);\n    }\n    /**\n     * Passes an event to the VM.\n     * @throws Error Throws an error if the pass event function is not found.\n     */\n    async passEvent(args, timeout = this.defaultTimeout) {\n      return await Promise.race([\n        (async () => {\n          const vmFunctions = await this.deferredVmFunctions.promise;\n          if (!vmFunctions.passEventFunction)\n            throw new BGError(\"PASS_EVENT\", \"Pass event function not found\");\n          vmFunctions.passEventFunction(args);\n        })(),\n        new Promise((_, reject) => setTimeout(() => reject(new BGError(\"TIMEOUT\", \"VM operation timed out\")), timeout))\n      ]);\n    }\n    /**\n     * Checks the \"camera\".\n     * @throws Error Throws an error if the check camera function is not found.\n     */\n    async checkCamera(args, timeout = this.defaultTimeout) {\n      return await Promise.race([\n        (async () => {\n          const vmFunctions = await this.deferredVmFunctions.promise;\n          if (!vmFunctions.checkCameraFunction)\n            throw new BGError(\"CHECK_CAMERA\", \"Check camera function not found\");\n          vmFunctions.checkCameraFunction(args);\n        })(),\n        new Promise((_, reject) => setTimeout(() => reject(new BGError(\"TIMEOUT\", \"VM operation timed out\")), timeout))\n      ]);\n    }\n    /**\n     * Shuts down the VM. Taking a snapshot after this will throw an error.\n     * @throws Error Throws an error if the shutdown function is not found.\n     */\n    async shutdown(timeout = this.defaultTimeout) {\n      return await Promise.race([\n        (async () => {\n          const vmFunctions = await this.deferredVmFunctions.promise;\n          if (!vmFunctions.shutdownFunction)\n            throw new BGError(\"SHUTDOWN\", \"Shutdown function not found\");\n          vmFunctions.shutdownFunction();\n        })(),\n        new Promise((_, reject) => setTimeout(() => reject(new BGError(\"TIMEOUT\", \"VM operation timed out\")), timeout))\n      ]);\n    }\n    /**\n     * Takes a snapshot synchronously.\n     * @returns The snapshot result.\n     * @throws Error Throws an error if the synchronous snapshot function is not found.\n     */\n    async snapshotSynchronous(args) {\n      if (!this.syncSnapshotFunction)\n        throw new BGError(\"SYNC_SNAPSHOT\", \"Synchronous snapshot function not found\");\n      return this.syncSnapshotFunction([\n        args.contentBinding,\n        args.signedTimestamp,\n        args.webPoSignalOutput,\n        args.skipPrivacyBuffer\n      ]);\n    }\n  };\n\n  // node_modules/bgutils-js/dist/core/webPoMinter.js\n  var WebPoMinter = class _WebPoMinter {\n    constructor(mintCallback) {\n      this.mintCallback = mintCallback;\n    }\n    static async create(integrityTokenResponse, webPoSignalOutput) {\n      const getMinter = webPoSignalOutput[0];\n      if (!getMinter)\n        throw new BGError(\"VM_ERROR\", \"PMD:Undefined\");\n      if (!integrityTokenResponse.integrityToken)\n        throw new BGError(\"INTEGRITY_ERROR\", \"No integrity token provided\", { integrityTokenResponse });\n      const mintCallback = await getMinter(base64ToU8(integrityTokenResponse.integrityToken));\n      if (!(mintCallback instanceof Function))\n        throw new BGError(\"VM_ERROR\", \"APF:Failed\");\n      return new _WebPoMinter(mintCallback);\n    }\n    async mintAsWebsafeString(identifier) {\n      const result = await this.mint(identifier);\n      return u8ToBase64(result, true);\n    }\n    async mint(identifier) {\n      const result = await this.mintCallback(new TextEncoder().encode(identifier));\n      if (!result)\n        throw new BGError(\"VM_ERROR\", \"YNJ:Undefined\");\n      if (!(result instanceof Uint8Array))\n        throw new BGError(\"VM_ERROR\", \"ODM:Invalid\");\n      return result;\n    }\n  };\n\n  // node_modules/bgutils-js/dist/core/webPoClient.js\n  async function generate(args) {\n    const { program, bgConfig, globalName } = args;\n    const { identifier } = bgConfig;\n    const botguard = await BotGuardClient.create({ program, globalName, globalObj: bgConfig.globalObj });\n    const webPoSignalOutput = [];\n    const botguardResponse = await botguard.snapshot({ webPoSignalOutput });\n    const payload = [bgConfig.requestKey, botguardResponse];\n    const integrityTokenResponse = await bgConfig.fetch(buildURL(\"GenerateIT\", bgConfig.useYouTubeAPI), {\n      method: \"POST\",\n      headers: getHeaders(),\n      body: JSON.stringify(payload)\n    });\n    const integrityTokenJson = await integrityTokenResponse.json();\n    const [integrityToken, estimatedTtlSecs, mintRefreshThreshold, websafeFallbackToken] = integrityTokenJson;\n    const integrityTokenData = {\n      integrityToken,\n      estimatedTtlSecs,\n      mintRefreshThreshold,\n      websafeFallbackToken\n    };\n    const webPoMinter = await WebPoMinter.create(integrityTokenData, webPoSignalOutput);\n    const poToken = await webPoMinter.mintAsWebsafeString(identifier);\n    return { poToken, integrityTokenData };\n  }\n  function generateColdStartToken(identifier, clientState) {\n    const encodedIdentifier = new TextEncoder().encode(identifier);\n    if (encodedIdentifier.length > 118)\n      throw new BGError(\"BAD_INPUT\", \"Content binding is too long.\", { identifierLength: encodedIdentifier.length });\n    const timestamp = Math.floor(Date.now() / 1e3);\n    const randomKeys = [Math.floor(Math.random() * 256), Math.floor(Math.random() * 256)];\n    const header = randomKeys.concat([\n      0,\n      clientState ?? 1\n    ], [\n      timestamp >> 24 & 255,\n      timestamp >> 16 & 255,\n      timestamp >> 8 & 255,\n      timestamp & 255\n    ]);\n    const packet = new Uint8Array(2 + header.length + encodedIdentifier.length);\n    packet[0] = 34;\n    packet[1] = header.length + encodedIdentifier.length;\n    packet.set(header, 2);\n    packet.set(encodedIdentifier, 2 + header.length);\n    const payload = packet.subarray(2);\n    const keyLength = randomKeys.length;\n    for (let i = keyLength; i < payload.length; i++) {\n      payload[i] ^= payload[i % keyLength];\n    }\n    return u8ToBase64(packet, true);\n  }\n  function generatePlaceholder(identifier, clientState) {\n    return generateColdStartToken(identifier, clientState);\n  }\n  function decodeColdStartToken(token) {\n    const packet = base64ToU8(token);\n    const payloadLength = packet[1];\n    const totalPacketLength = 2 + payloadLength;\n    if (packet.length !== totalPacketLength)\n      throw new BGError(\"BAD_INPUT\", \"Invalid packet length.\", { packetLength: packet.length, expectedLength: totalPacketLength });\n    const payload = packet.subarray(2);\n    const keyLength = 2;\n    for (let i = keyLength; i < payload.length; ++i) {\n      payload[i] ^= payload[i % keyLength];\n    }\n    const keys = [payload[0], payload[1]];\n    const unknownVal = payload[2];\n    const clientState = payload[3];\n    const timestamp = payload[4] << 24 | payload[5] << 16 | payload[6] << 8 | payload[7];\n    const date = new Date(timestamp * 1e3);\n    const identifier = new TextDecoder().decode(payload.subarray(8));\n    return {\n      identifier,\n      timestamp,\n      unknownVal,\n      clientState,\n      keys,\n      date\n    };\n  }\n\n  // node_modules/bgutils-js/dist/index.js\n  var dist_default = core_exports;\n\n  // bgutils-entry.js\n  globalThis.BG = dist_default;\n  globalThis.bgBase64ToU8 = base64ToU8;\n  globalThis.bgU8ToBase64 = u8ToBase64;\n})();\n";

    try { window.wrappedJSObject.eval(BGUTILS_CODE); }
    catch(e) { console.error('[BG-robots] bgutils inject failed:', e.message); }

    // Expose callback for results
    exportFunction(function(resultJson) {
        try {
            browser.runtime.sendMessage({ type: 'poTokenResult', data: JSON.parse(resultJson) });
        } catch (e) {}
    }, window.wrappedJSObject, { defineAs: '__fdPoTokenCB' });

    // Expose a function that lets the page context request a script fetch
    // (content script can fetch cross-origin; page context can't due to CORS)
    exportFunction(function(url) {
        return new window.wrappedJSObject.Promise(
            exportFunction(function(resolve, reject) {
                fetch(url).then(r => r.text()).then(text => {
                    resolve(cloneInto(text, window.wrappedJSObject));
                }).catch(e => {
                    reject(cloneInto(e.message, window.wrappedJSObject));
                });
            }, window.wrappedJSObject)
        );
    }, window.wrappedJSObject, { defineAs: '__fdFetchText' });

    const RUNNER = `(function() {
        const RK = 'O43z0dpjhgX20SCx4KAo';
        const AK = 'AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw';
        let cm = null, cmt = 0;

        async function gen(vid, vd) {
            const BG = window.BG;
            if (!BG) throw new Error('BG not loaded');
            const id = vid || vd;
            if (cm && (Date.now()-cmt) < 18000000) return await cm.mintAsWebsafeString(id);

            const cv = '2.20260401.01.00';
            const ctx = {client:{clientName:'WEB',clientVersion:cv,hl:'en',gl:'US',visitorData:vd||''}};

            const cr = await fetch('https://www.youtube.com/youtubei/v1/att/get?prettyPrint=false&alt=json',{
                method:'POST',headers:{'Accept':'*/*','Content-Type':'application/json','X-Goog-Visitor-Id':vd||'','X-Youtube-Client-Version':cv,'X-Youtube-Client-Name':'1'},
                body:JSON.stringify({engagementType:'ENGAGEMENT_TYPE_UNBOUND',context:ctx})
            });
            if (!cr.ok) throw new Error('att/get '+cr.status);
            const cd = await cr.json();
            if (!cd.bgChallenge) throw new Error('no bgChallenge');

            let iu = cd.bgChallenge.interpreterUrl.privateDoNotAccessOrElseTrustedResourceUrlWrappedValue;
            if (iu.startsWith('//')) iu='https:'+iu;

            // Use content script's fetch to bypass CORS (page fetch blocked by google.com CORS)
            const js = await window.__fdFetchText(iu);
            if (!js) throw new Error('empty VM');
            new Function(js)();

            const bg = await BG.BotGuardClient.create({program:cd.bgChallenge.program,globalName:cd.bgChallenge.globalName,globalObj:window});
            const wps = [];
            const bgr = await bg.snapshot({webPoSignalOutput:wps},10000);
            if (!bgr) throw new Error('empty snapshot');

            const ir = await fetch('https://www.youtube.com/api/jnn/v1/GenerateIT',{
                method:'POST',headers:{'Content-Type':'application/json+protobuf','x-goog-api-key':AK,'x-user-agent':'grpc-web-javascript/0.1'},
                body:JSON.stringify([RK,bgr])
            });
            const ij = await ir.json();
            if (typeof ij[0]!=='string') throw new Error('no IT');

            const m = await BG.WebPoMinter.create({integrityToken:ij[0]},wps);
            cm=m; cmt=Date.now();
            return await m.mintAsWebsafeString(id);
        }

        window.__fdGenPoToken = async function(vid,vd,rid) {
            try {
                const t = await gen(vid,vd);
                window.__fdPoTokenCB(JSON.stringify({requestId:rid,token:t}));
            } catch(e) {
                window.__fdPoTokenCB(JSON.stringify({requestId:rid,error:e.message}));
            }
        };
        console.log('[BG-robots] BotGuard runner ready');
    })();`;

    try { window.wrappedJSObject.eval(RUNNER); }
    catch(e) { console.error('[BG-robots] runner inject failed:', e.message); }

    browser.runtime.onMessage.addListener((msg) => {
        if (msg?.type === 'generatePoToken') {
            try {
                window.wrappedJSObject.__fdGenPoToken(
                    msg.data.videoId || '', msg.data.visitorData || '', msg.data.requestId || ''
                );
            } catch(e) { console.error('[BG-robots] trigger failed:', e.message); }
        }
        // Liveness probe — background.js sends this to verify the content script
        // and page context are still alive before attempting a mint.
        if (msg?.type === 'ping') return Promise.resolve('pong');
    });

    // Signal background.js that the content script + BotGuard runner are ready.
    // background.js waits for this instead of a blind 2500ms sleep — avoids the
    // race where GeckoView tears down the tab before the sleep finishes.
    browser.runtime.sendMessage({ type: 'poTokenTabReady' }).catch(() => {});

    console.log('[BG-robots] PO token content script ready on robots.txt');
}

// EMBED
if (window.self !== window.top && /\/(embed|v|e)\//.test(location.pathname)) {
    browser.runtime.sendMessage({ type: "embedVideo", url: location.href });
}