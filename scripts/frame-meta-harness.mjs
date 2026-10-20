// frame-meta-harness.mjs — per-clip capture metadata across FRAMES, in a real
// browser. The REAL js/content-script.js runs in every frame of locally served
// pages (a stubbed `browser`), and the REAL pickCaptureMeta from requests.js
// ranks what it answers, so a check fails exactly when a capture would be
// misnamed on a device.
//
// Why a browser and not the smoke's vm: the bug this pins lives BETWEEN frames
// (a clip played in an iframe, declared by the page that embeds it — the
// firedown.app /demo showcase titled every clip "Firedown · demo"), and only a
// real DOM has frames, origins, base URLs and sandboxing.
//
// Engine caveat: this runs Chromium, where the script is a page script. On a
// device it is a Firefox content script reading through Xray wrappers; the
// same-origin parent read is the one behaviour to confirm there
// (adb logcat, or a firedown.app/demo capture showing per-clip names).
//
// Needs Playwright + its Chromium (`sh scripts/setup-playwright.sh`);
// SKIPs cleanly without them.
//   node scripts/frame-meta-harness.mjs [path/to/content-script.js]
// The optional path runs another revision (proving the checks have teeth).
import { readFileSync } from "node:fs";
import http from "node:http";
import { loadPlaywright, PLAYWRIGHT_HINT } from "./load-playwright.mjs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const ext = join(root, "app/src/main/assets/webrequests");
const csPath = process.argv[2] || join(ext, "js/content-script.js");
const cs = readFileSync(csPath, "utf8");

// pickCaptureMeta is a pure exported function; lift it out of the module
// source rather than import requests.js (which registers every listener).
const reqSrc = readFileSync(join(ext, "js/requests.js"), "utf8");
const pickSrc = reqSrc.match(/export function pickCaptureMeta\(meta\) \{[\s\S]*?\n\}\n/);
if (!pickSrc) {
  console.error("FAIL: pickCaptureMeta not found in requests.js");
  process.exit(1);
}
const pickCaptureMeta = new Function(`${pickSrc[0].replace(/^export /, "")}; return pickCaptureMeta;`)();
// pickImageMeta likewise, with the two constants it reads.
const imgPickSrc = reqSrc.match(/const IMAGE_NAME_MAX = [\s\S]*?export function pickImageMeta\(meta\) \{[\s\S]*?\n\}\n/);
if (!imgPickSrc) {
  console.error("FAIL: pickImageMeta not found in requests.js");
  process.exit(1);
}
const pickImageMeta = new Function(`${imgPickSrc[0].replace(/export /, "")}; return pickImageMeta;`)();

const chromium = (await loadPlaywright())?.chromium;
if (!chromium) {
  console.log(`SKIP: playwright not installed (${PLAYWRIGHT_HINT})`);
  process.exit(0);
}

// ── Pages, keyed by host + path. a.test and b.test are two ORIGINS on one
// server (Chromium maps *.test to 127.0.0.1). ──
const P = "__PORT__";
const A = `http://a.test:${P}`;
const B = `http://b.test:${P}`;
const block = (items) =>
  `<script type="application/firedown+json">${JSON.stringify(items)}</script>`;
const ld = (obj) => `<script type="application/ld+json">${JSON.stringify(obj)}</script>`;
const html = (title, head, body) =>
  `<!doctype html><html><head><meta charset="utf-8"><title>${title}</title>${head}</head><body>${body}</body></html>`;

// A Video.js player root as the page has it once the player is set up: the
// root labelled "Video Player", a tech <video> with no URL of its own (MSE)
// or, given `src`, the mp4 the player handed it, and a control bar of titled
// buttons.
const vjsRoot = (id, poster, src) =>
  `<video-js id="${id}" class="video-js" aria-label="Video Player">`
  + `<video id="${id}_html5_api" class="vjs-tech"${poster ? ` poster="${poster}"` : ""}`
  + `${src ? ` preload="none" src="${src}"` : ""}></video>`
  + `<div class="vjs-control-bar"><button title="Play"></button>`
  + `<button title="Picture-in-Picture"></button><button title="Seek to live, currently behind live"></button></div>`
  + `</video-js>`;
// The page's videojs global: getAllPlayers() with each player's manifest,
// poster and (when named) Brightcove-style mediainfo.
const vjsScript = (players) => `<script>
  window.videojs = function () {};
  const all = ${JSON.stringify(players)}.map(([id, name, poster, mp4]) => ({
    id: () => id,
    currentSources: () => [
      ...(mp4 ? [{ src: location.origin + mp4, type: "video/mp4" }] : []),
      { src: "http://example.invalid/http-copy/" + id + "/master.m3u8", type: "application/x-mpegURL" },
      { src: location.origin + "/assets/clips/" + id + "/master.m3u8", type: "application/x-mpegURL" }],
    currentSrc: () => location.origin + "/assets/clips/" + id + "/master.m3u8",
    poster: () => poster,
    mediainfo: name ? { name, poster } : undefined,
  }));
  window.videojs.getAllPlayers = () => all;
</script>`;
// A podcast card: the picture in a figure, the title in a heading beside the
// <audio>, and a second <audio> for the same episode (the unwrapped template).
const podcastCard = (id, title) =>
  `<article><figure><a href="/${id}"><picture><img src="/img/${id}.png" width="366" alt="${title}">`
  + `</picture></a></figure><div><p>PODCAST | Episodio</p><h2><a href="/${id}">${title}</a></h2>`
  + `<audio preload="none" src="/assets/clips/${id}.mp3"></audio>`
  + `<div><div><div><audio preload="none" src="/assets/clips/${id}.mp3"></audio></div></div></div>`
  + `<p>Resumen del episodio</p></div></article>`;

const SITE = {
  // 1. The firedown.app shape: the homepage declares the clips, an iframe at
  //    /demo/index.html plays them through ../ paths. A malformed block comes
  //    first (one bad block must not hide the next); a path-relative
  //    VideoObject contentUrl belongs to the HOMEPAGE's base; an AudioObject
  //    declares the iframe's audio; a srcdoc child plays a declared clip too.
  [`a.test/home.html`]: html("Home",
    `<meta property="og:title" content="Home OG"><meta property="og:image" content="/og.png">`
    + `<script type="application/firedown+json">[{"contentUrl": oops</script>`
    + block([
      { contentUrl: "/assets/clips/a.mp4", name: "Clip A", thumbnail: "/assets/clips/a.jpg" },
      { contentUrl: "/assets/clips/own.mp4", name: "Parent Own" },
      { contentUrl: "/assets/clips/sd.mp4", name: "Clip SD" },
    ])
    + ld({ "@context": "https://schema.org", "@type": "VideoObject", name: "Clip B",
      contentUrl: "assets/clips/b.mp4", thumbnailUrl: "thumbs/b.jpg" })
    + ld({ "@context": "https://schema.org", "@type": "AudioObject", name: "Clip C",
      contentUrl: "/assets/clips/c.mp3" }),
    `<iframe src="demo/index.html" title="Demo"></iframe>`
    + `<iframe srcdoc="<video src='/assets/clips/sd.mp4'></video>"></iframe>`),
  [`a.test/demo/index.html`]: html("Demo frame",
    block([{ contentUrl: "../assets/clips/own.mp4", name: "Frame Own" }]),
    `<video src="../assets/clips/a.mp4" poster="../assets/clips/a-frame.jpg"></video>`
    + `<video src="../assets/clips/b.mp4"></video>`
    + `<video src="../assets/clips/own.mp4"></video>`
    + `<video src="../assets/clips/d.mp4"></video>`
    + `<audio src="../assets/clips/c.mp3"></audio>`
    + `<iframe src="inner.html"></iframe>`),
  [`a.test/demo/inner.html`]: html("Inner", "", `<video src="../assets/clips/a.mp4"></video>`),

  // 2. A cross-origin parent declaring this frame's clip: never read.
  [`b.test/xhome.html`]: html("X home",
    block([{ contentUrl: `${A}/assets/clips/x.mp4`, name: "Leak X" }])
    + ld({ "@type": "VideoObject", name: "Leak X LD", contentUrl: `${A}/assets/clips/x.mp4` }),
    `<iframe src="${A}/xframe.html"></iframe>`),
  [`a.test/xframe.html`]: html("X frame", "", `<video src="/assets/clips/x.mp4"></video>`),

  // 3. A same-origin top behind a CROSS-origin middle frame: the walk skips
  //    the middle (never reads its block) and still reaches the top.
  [`a.test/mhome.html`]: html("M home",
    block([{ contentUrl: "/assets/clips/m.mp4", name: "Clip M" }]),
    `<iframe src="${B}/middle.html"></iframe>`),
  [`b.test/middle.html`]: html("Middle",
    block([{ contentUrl: `${A}/assets/clips/m.mp4`, name: "Middle Says" }]),
    `<iframe src="${A}/minner.html"></iframe>`),
  [`a.test/minner.html`]: html("M inner", "", `<video src="/assets/clips/m.mp4"></video>`),

  // 4. A sandboxed (opaque-origin) frame of the SAME site: not the page's
  //    origin any more, so the parent's block is not its to read.
  [`a.test/shome.html`]: html("S home",
    block([{ contentUrl: "/assets/clips/s.mp4", name: "Leak S" }]),
    `<iframe sandbox="allow-scripts" src="/sframe.html"></iframe>`),
  [`a.test/sframe.html`]: html("S frame", "", `<video src="/assets/clips/s.mp4"></video>`),

  // 5. A news front page (lasprovincias.es, HAR 26-10-19): Video.js players
  //    playing HLS through MSE (the <video> shows no URL the wire saw), one
  //    with the API shape of Brightcove's (mediainfo.name), podcast cards
  //    whose <audio> sits beside a heading with the picture one level up and
  //    a second copy of the same <audio> (a cookie-wall template unwrapped),
  //    a placeholder card, and two JW-style players whose control buttons
  //    must never title a clip.
  [`a.test/front.html`]: html("Front Page - Site",
    `<meta property="og:title" content="Front Page - Site"><meta property="og:image" content="/logo.png">`,
    `<article><figure><div class="v-a-lnk"><div class="video-player">${vjsRoot("v0", "/img/v0.jpg")}</div></div></figure>`
    + `<div><h2><a href="/a0">Rios desbordados en la Safor</a></h2></div></article>`
    + `<ul><li><div><figure><div class="v-a-lnk"><div class="video-player">${vjsRoot("s1", "/img/s1.jpg")}</div></div></figure>`
    + `<p>Calles inundadas</p></div></li>`
    + `<li><div><figure><div class="v-a-lnk"><div class="video-player">${vjsRoot("s2", "/img/s2.jpg")}</div></div></figure></div></li>`
    + `<li><div><figure><div class="v-a-lnk"><div class="video-player">${vjsRoot("s3", "")}</div></div></figure>`
    + `<h2>Gandia bajo el agua</h2></div></li>`
    + `<li><div><figure><div class="v-a-lnk"><div class="video-player">${vjsRoot("t1", "/img/t1.jpg", "/assets/clips/t1.mp4")}</div></div></figure>`
    + `<p>Texto de la tarjeta</p></div></li></ul>`
    + podcastCard("ep3", "Un podcast con ninos: igualdad y salud")
    + podcastCard("ep4", "Un podcast con ninos: tecnologia")
    + `<article><h2>Un podcast con ninos: salud mental</h2><audio preload="none" src=""></audio></article>`
    + `<div class="card"><div class="jwplayer"><video src="/assets/clips/jw1.mp4"></video>`
    + `<div role="button" aria-label="Rewind 10 Seconds"></div><div role="button" aria-label="Closed Captions"></div></div>`
    + `<h3>Clip uno del dia</h3></div>`
    + `<div class="card"><div class="jwplayer"><video src="/assets/clips/jw2.mp4"></video>`
    + `<div role="button" aria-label="Rewind 10 Seconds"></div></div><h3>Clip dos del dia</h3></div>`
    + vjsScript([["v0", "Lluvias torrenciales en Oliva", "/img/v0.jpg"],
      ["s1", "Calles inundadas en Miramar", "/img/s1.jpg"],
      ["s2", "Noche complicada en Bellreguard", "/img/s2.jpg"],
      ["s3", null, "/img/s3.jpg"],
      ["t1", "Alerta por inundacion en Gandia", "/img/t1.jpg", "/assets/clips/t1.mp4"]])),

  // 6. The same shapes on single-clip pages: the page's own fields stay.
  [`a.test/single.html`]: html("Article",
    `<meta property="og:title" content="Article headline"><meta property="og:image" content="/lead.jpg">`,
    `<figure>${vjsRoot("only", "/img/only.jpg")}</figure>`
    + vjsScript([["only", "Clip name", "/img/only.jpg"]])),
  [`a.test/solo.html`]: html("Podcast",
    `<meta property="og:title" content="Episode heading | The show"><meta property="og:image" content="/show.jpg">`,
    podcastCard("solo", "Episode heading")),

  // 7. An ordinary article with a second <video> somewhere (an autoplay
  //    teaser): its clip is not titled by a subheading after it, nor by a
  //    "Most read" box beside it; a podcast's icon is not its picture.
  [`a.test/article.html`]: html("Headline - Site",
    `<meta property="og:title" content="Mayor resigns after flood report"><meta property="og:image" content="/og.jpg">`,
    `<article><h1>Mayor resigns after flood report</h1><div class="body"><p>${"Intro paragraph. ".repeat(30)}</p>`
    + `<figure><video controls poster="/p.jpg" src="/assets/clips/main.mp4"></video></figure>`
    + `<h2>Background</h2><p>${"More text. ".repeat(40)}</p></div></article>`
    + `<aside class="widget"><video muted autoplay loop src="/assets/clips/teaser.mp4"></video></aside>`),
  [`a.test/article-widget.html`]: html("Headline - Site",
    `<meta property="og:title" content="Mayor resigns after flood report">`,
    `<main><section class="most-read"><h2>Most read</h2><ul><li><a href="/x">Other story</a></li></ul></section>`
    + `<figure><video controls src="/assets/clips/main2.mp4"></video></figure><p>Text.</p></main>`
    + `<aside class="widget"><video muted autoplay loop src="/assets/clips/teaser.mp4"></video></aside>`),
  [`a.test/podcast-icon.html`]: html("Show - Site",
    `<meta property="og:title" content="Episode 12: The flood"><meta property="og:image" content="/show.jpg">`,
    `<header><h2>Latest from the newsroom</h2></header><div class="player-wrap"><video preload="none"></video></div>`
    + `<main><div><p>Listen:</p><audio controls src="/assets/clips/ep12.mp3"></audio>`
    + `<img src="/icons/rss.svg" alt=""></div></main>`),

  // 9. A news front page's looping clips (CNN, HAR 26-10-19): each card is a
  //    media link around the <video> (its only caption a CREDIT figcaption)
  //    and a text link to the same story holding the headline; a gallery
  //    slide's caption with its credit inside; a card with a credit and
  //    nothing else. The stylesheet is CNN's own, as the HAR's replay
  //    computes it: credits are 10 px grey, labels 12 px bold capitals.
  [`a.test/cnn.html`]: html("CNN",
    `<meta property="og:title" content="CNN front page"><style>body{font-size:16px}`
    + `.container__headline-text{font-size:18px;font-weight:700}.container__description{font-size:14px}`
    + `.card__label{font-size:12px;font-weight:700;text-transform:uppercase}`
    + `.interactive-video__credit,.image__credit,.promo__credit{font-size:10px;color:#6e6e6e}</style>`,
    `<div class="card" data-component-name="card"><a href="/2026/10/19/europe/france-protests">`
    + `<div class="container__item-media-wrapper"><div class="container__item-media"><div class="interactive-video">`
    + `<div class="interactive-video__container"><video class="interactive-video__player" loop muted playsinline>`
    + `<source src="/assets/clips/cnn1.mp4" type="video/mp4"></video></div>`
    + `<div class="media__metadata-wrapper"><div class="interactive-video__metadata">`
    + `<div itemprop="caption" class="interactive-video__caption"><span data-editable="metaCaption" class="inline-placeholder"></span>`
    + `<figcaption class="interactive-video__credit">BFMTV / Reuters</figcaption></div></div></div>`
    + `</div></div></div></a>`
    + `<a href="http://a.test:${P}/2026/10/19/europe/france-protests"><div class="container__text"><div class="container__headline">`
    + `<span class="card__label">Video</span><span class="container__headline-text" data-editable="headline">France reels from weekend of chaos</span>`
    + `</div><div class="container__description">Nationwide protests spiraled into deadly violence</div></div></a></div>`
    + `<ul><li class="card"><a href="/2026/10/16/media/other-story"><div><div class="interactive-video"><div>`
    + `<video loop muted><source src="/assets/clips/cnn4.mp4" type="video/mp4"></video></div>`
    + `<figcaption class="interactive-video__credit">Reuters</figcaption></div></div></a>`
    + `<a href="/2026/10/16/media/other-story"><span class="container__headline-text">Journalist hospitalized after attack on olive harvest</span></a></li></ul>`
    + `<figure class="gallery__slide"><video loop muted src="/assets/clips/cnn2.mp4"></video>`
    + `<div itemprop="caption" class="gallery__caption">Firefighters battle a blaze near Los Angeles `
    + `<span class="image__credit">Reuters</span></div></figure>`
    + `<div class="promo"><video loop muted src="/assets/clips/cnn3.mp4"></video>`
    + `<figcaption class="promo__credit">Getty Images</figcaption></div>`),

  // 10. One card per idiom a multi-clip page names its clips with, so the
  //     walk is not tuned to one site: a control label "Play: <title>" beside
  //     the heading (vozpopuli.com), a title only in a data-title and a class
  //     no rule can read (lasprovincias.es `v-a-t`), BEM classes with a
  //     kicker and a subtitle, BBC-style data-testid, Condé Nast camelCase
  //     "Hed", schema.org microdata with an author's name beside the clip's,
  //     an aria-labelledby region, a clip six wrappers deep, a "--with-title"
  //     modifier, an image alt (and a file-name alt), and a card with nothing
  //     but a control label (higgsfield.ai).
  // The Daily Mail's front page: each video module's <video> carries its
  // player's config as JSON in data-opts, the clip's title included, beside a
  // poster <img alt="Hero video poster">; the page title is the site's.
  [`a.test/dmhome.html`]: html("UK Home | Daily Mail Online",
    `<meta property="og:title" content="UK Home | Daily Mail Online">`,
    `<div data-video-hero="true"><div class="container"><div class="hero"><div class="player vjs-video-container">`
    + `<img class="posterImage" src="/img/d1.jpg" alt="Hero video poster">`
    + `<video class="video-js" preload="none" poster="/img/d1.jpg" src="/assets/clips/d1.mp4" data-opts='`
    + `{"title":"Police officer shot in city centre as cops hunt for suspects","src":"/assets/clips/d1.mp4","videoId":"4716928555074797746"}'>`
    + `</video></div></div></div></div>`
    + `<div class="puff"><div class="vjs-video-container"><video class="video-js" preload="none" src="/assets/clips/d2.mp4"`
    + ` data-plyr-config='{"media":{"title":"Storm closes roads in the north"}}'></video></div></div>`
    + `<div class="puff"><h2>Headline of the third card</h2><div class="vjs-video-container">`
    + `<video class="video-js" preload="none" src="/assets/clips/d3.mp4" data-setup='{"name":"heroPlayer","fluid":true}'></video></div></div>`
    + `<div class="puff"><div class="player"><img src="/img/d4.jpg" alt="Hero video poster">`
    + `<video preload="none" src="/assets/clips/d4.mp4"></video></div></div>`),

  [`a.test/cards.html`]: html("Cards page",
    `<meta property="og:title" content="Cards page">`,
    `<div class="carousel-item"><a href="/v1" aria-label="Reproducir: El temporal deja imagenes en la Marina">`
    + `<img alt="El temporal deja imagenes en la Marina"><video src="/assets/clips/k1.mp4"></video></a>`
    + `<h2 class="text-base">El temporal deja imagenes en la Marina</h2></div>`
    + `<div class="v-a"><figure class="v-a-img-c"><div class="video-player"><video src="/assets/clips/k2.mp4"></video></div></figure>`
    + `<p class="v-a-t">Calles inundadas en Miramar por las lluvias</p>`
    + `<button class="v-tbr__b" data-title="Calles inundadas en Miramar por las lluvias">Compartir</button></div>`
    + `<article class="teaser"><div class="teaser__media"><div><div><video src="/assets/clips/k3.mp4"></video></div></div></div>`
    + `<div class="teaser__body"><span class="teaser__kicker">Sport</span>`
    + `<a class="teaser__title" href="/x">Final match ends in a late draw</a><p class="teaser__subtitle">A goal in added time</p></div></article>`
    + `<div data-testid="card"><div data-testid="card-media"><video src="/assets/clips/k4.mp4"></video></div>`
    + `<div data-testid="card-text-wrapper"><p data-testid="card-headline">Storm closes roads across the north</p>`
    + `<p data-testid="card-description">Drivers are told to stay home</p></div></div>`
    + `<div class="SummaryItemWrapper"><div class="SummaryItemAssetContainer"><video src="/assets/clips/k5.mp4"></video></div>`
    + `<div class="SummaryItemContent"><a href="/y"><span class="SummaryItemHedBase">The chef who reinvented the omelette</span></a>`
    + `<div class="SummaryItemDek">A profile</div></div></div>`
    + `<div itemscope itemtype="https://schema.org/VideoObject"><meta itemprop="name" content="Microdata clip name">`
    + `<div itemprop="author" itemscope><span itemprop="name">Jane Doe</span></div>`
    + `<div itemscope itemtype="https://schema.org/Organization"><span itemprop="name">Acme News Group</span></div>`
    + `<video src="/assets/clips/k6.mp4"></video></div>`
    + `<section aria-labelledby="t7"><div><video src="/assets/clips/k7.mp4"></video></div><div id="t7">Labelled region clip</div></section>`
    + `<div class="tile"><div><div><div><div><div><div><video src="/assets/clips/k8.mp4"></video></div></div></div></div></div></div>`
    + `<h3>Deeply nested clip title</h3></div>`
    + `<div class="card card--with-title"><video src="/assets/clips/k9.mp4"></video>`
    + `<div class="meta has-title"><span class="card__title">Modifier test title</span><span>2 min</span></div></div>`
    + `<div class="g"><img src="/img/x.jpg" alt="Sunset over the bay in Valencia"><video src="/assets/clips/k10.mp4"></video></div>`
    + `<div class="g"><img src="/img/y.jpg" alt="bad audience still 1.jpg"><video src="/assets/clips/k11.mp4"></video></div>`
    + `<div class="card"><img alt=""><video aria-hidden="true" src="/assets/clips/k12.mp4"></video>`
    + `<button aria-label="View Countryside Duo"></button><span>@creator</span></div>`
    + `<div class="promo"><video src="/assets/clips/k13.mp4"></video><div class="promo__headline">`
    + `<span class="promo__label">Video</span><span class="promo__headline-text">Inner headline wins</span></div></div>`
    + `<div class="box"><video src="/assets/clips/k14.mp4"></video><div class="info has-title">Harbour lights after the storm</div></div>`),

  // 9. Pictures, named by the page: the shapes of the news fronts on hand
  //    (CNN, lasprovincias.es, vozpopuli.com) and the ones no name must come
  //    from.
  [`a.test/images.html`]: html("Front page - Site",
    `<meta property="og:title" content="Front page">`,
    // CNN: the alt ends in its credit; the card's headline is a twin link
    `<div class="card"><a href="/s1"><div class="image"><img src="/assets/clips/i1.jpg" `
    + `alt="Australian MP Helen Haines speaks to journalists in Canberra, Tuesday, Sept. 8, 2026. (Mick Tsikas/AAP Image via AP)">`
    + `</div><div class="image__credit">Mick Tsikas/AAP Image/AP</div></a>`
    + `<a href="/s1"><span class="container__headline-text">Independents rise in Canberra</span></a></div>`
    // a Getty alt: dateline first, far too long for a name
    + `<div class="card"><img src="/assets/clips/i2.jpg" alt="SAN MARCOS, TEXAS - OCTOBER 15: Democratic U.S. Senate `
    + `Candidate James Talarico attends a political event on October 15, 2026 in San Marcos, Texas. The event was also used `
    + `to produce a video for the campaign. (Photo by Joel Angel Juarez/Getty Images)"></div>`
    + `<p><img src="/assets/clips/i14.jpg" alt="San Diego, California - September 04: Fernando Tatis Jr. throws the ball"></p>`
    // a file-name alt: the card's headline names it
    + `<div class="card"><img src="/assets/clips/i3.jpg" alt="MIA EP 21 vrtc 1 clean.jpg">`
    + `<h3 class="card__title">Midterms: what the ballot says</h3></div>`
    // lasprovincias: <picture>, an aria-hidden caption whose credit is a span
    + `<article><figure><a href="/s4"><picture><source srcset="/assets/clips/i4.webp 1x">`
    + `<img src="/assets/clips/i4.jpg" alt="Una calle de Oliva totalmente inundada."></picture></a>`
    + `<figcaption class="v-fc" aria-hidden="true"><span class="v-fc__t">Una calle de Oliva totalmente inundada.</span>`
    + ` <span class="v-fc__p">(LP)</span></figcaption></figure>`
    + `<h2 class="v-a-t"><a href="/s4">Helicópteros de rescate actúan en Oliva</a></h2></article>`
    // no alt: the figure's caption, without its credit
    + `<figure><img src="/assets/clips/i5.jpg"><figcaption>Calle anegada de agua en Quart y Aldaia. (Irene Marsilla)</figcaption></figure>`
    // a gallery figure: one caption for three pictures names none of them
    + `<figure class="gallery"><img src="/assets/clips/i6a.jpg"><img src="/assets/clips/i6b.jpg"><img src="/assets/clips/i6c.jpg">`
    + `<figcaption>Three views of the flood</figcaption></figure>`
    // vozpopuli: a one-word alt, the headline as a twin link
    + `<div class="item"><a href="/n7"><img src="/assets/clips/i7.jpg" alt="Petrolero" title="Un buque petrolero."></a>`
    + `<h2><a href="/n7">La renovación de los contratos amenaza con disparar los precios</a></h2></div>`
    // ...and one whose title repeats its alt
    + `<div class="item"><a href="/n13"><img src="/assets/clips/i13.jpg" alt="Tribunal Constitucional" title="Tribunal Constitucional"></a>`
    + `<h2><a href="/n13">El Pleno del Constitucional decidirá sobre el recurso del Defensor del Pueblo</a></h2></div>`
    // never names: a picture-kind alt, a machine token, a bare word
    + `<div class="hero"><div class="player vjs-video-container"><img class="posterImage" src="/assets/clips/i9.jpg" `
    + `alt="Hero video poster"></div></div>`
    + `<p><img src="/assets/clips/i10.jpg" alt="rc2usnaifcu20260929"></p>`
    + ["i11", "i11b", "i11c"].map((f) => `<p><img src="/assets/clips/${f}.jpg" alt="Imagen"></p>`).join("")
    // a background picture with its own label
    + `<div role="img" aria-label="Countryside at dusk" style="background-image:url(/assets/clips/bg1.jpg);width:200px;height:100px"></div>`
    // a logo beside the menu button: the button's wording describes nothing
    + `<header><a href="/"><img src="/assets/clips/logo.svg" alt="Las Provincias - Diario de noticias"></a>`
    + `<button aria-label="Desplegar menú principal"></button></header>`
    // an alt that is content with a parenthesis inside the sentence
    + `<p><img src="/assets/clips/i12.jpg" alt="Lluvias en La Marina Alta (Alicante)"></p>`
    // a hidden avatar placeholder with an empty src, in a profile menu
    + `<div class="menu"><img src="" alt="" class="avatar-img hidden"><a href="/mi-perfil">Mi perfil</a></div>`),

  // 11. The device audit of 2026-10-20: the shapes the phone recorded on the
  //     sites whose clips were misnamed, shortened (the real markup's
  //     classes and nesting, our own titles and paths).
  // NPR: one clip playing at the top (its MediaSession set), a carousel of
  // clips below, each card with its title.
  [`a.test/npr.html`]: html("NPR - Breaking News",
    `<meta property="og:title" content="NPR - Breaking News">`,
    `<div class="jw-wrapper"><video id="top" src="/assets/clips/top.mp4"></video></div>`
    + `<ul id="vertical-video-playlist__items">`
    + [1, 2, 3].map((n) => `<li class="vertical-video-playlist__item"><div class="vertical-video-playlist__image-wrap">`
      + `<img class="vertical-video-playlist__image" src="/img/np${n}.jpg"></div>`
      + `<div class="vertical-video-playlist__metadata"><span class="vertical-video-playlist__title">Card clip number ${n} of the carousel</span>`
      + `<div class="vertical-video-playlist__cta-wrap"><button class="vertical-video-playlist__cta"><span>Play video</span></button>`
      + `<span class="vertical-video-playlist__duration">1:11</span></div></div>`
      + `<video class="vertical-video-playlist__video" preload="none" loop poster="/img/np${n}.jpg">`
      + `<source src="/assets/clips/np${n}.mp4" type="video/mp4"></video></li>`).join("")
    + `</ul><script>
      Object.defineProperty(document.getElementById("top"), "paused", { get: () => false });
      const md = { title: "The clip playing at the top", artist: "", artwork: [] };
      try { navigator.mediaSession.metadata = new MediaMetadata(md); }
      catch (_) { Object.defineProperty(navigator, "mediaSession", { value: { metadata: md } }); }
    </script>`),
  // antena3.com: a stories carousel of buttons labelled "Ver ahora", each
  // holding a poster and the clip; one poster named by a hash.
  [`a.test/antena3.html`]: html("Antena 3 Noticias",
    `<meta property="og:title" content="Antena 3 Noticias">`,
    `<ol class="carousel-stories__track">`
    + [["a3a", "perro-robot-que-ayuda-agricultores_68.jpg"], ["a3b", "cocina-adaptada-personas-discapacidad-visual_68.jpg"],
      ["a3c", "0D7CDF05-3FC4-422D-8496-6C6E7904553F.jpg"]].map(([id, poster]) =>
      `<li class="carousel-stories__item"><button type="button" class="carousel-stories__card" aria-label="Ver ahora"`
      + ` data-model="/json/video/${id}.json"><span class="carousel-stories__media"><video class="carousel-stories__video"`
      + ` poster="/fotos/${poster}?crop=853,1516&amp;width=360" src="/assets/clips/${id}.mp4" muted loop preload="none"`
      + ` aria-hidden="true"></video></span><span class="carousel-stories__cta"><span class="carousel-stories__cta-label">`
      + `Ver ahora</span></span></button></li>`).join("")
    + `</ol>`),
  // cope.es: vertical clips whose player config carries one placeholder
  // title, each card with its own heading; an article clip with its own.
  [`a.test/cope.html`]: html("COPE | Noticias",
    `<meta property="og:title" content="COPE | Noticias">`,
    [["cv1", "Herrera, sobre la ley de nietos: regalando nacionalidades"], ["cv2", "La lluvia deja calles cortadas en Valencia"],
      ["cv3", "Feijóo responde a Sánchez en el Senado"]].map(([id, h]) =>
      `<article class="c-player-vertical"><figure class="c-player-vertical__thumb"><picture>`
      + `<img alt="1020HERRERA_7H" src="/img/${id}.jpeg"></picture></figure><div class="c-player-vertical__video">`
      + `<video data-layer='{"id":72,"title":"prueba de vídeo vertical","credits":null}' playsinline muted preload="none"`
      + ` src="/assets/clips/${id}.mp4"></video></div><div class="c-player-vertical__txt">`
      + `<h3 class="c-player-vertical__title">${h}</h3><div class="c-player-vertical__controls"><div>`
      + `<button type="submit" class="c-player-vertical__button"></button><span class="c-player-vertical__time">1:28 min</span>`
      + `</div></div></div></article>`).join("")
    + `<article class="c-article--video"><div class="c-article__video"><video preload="none" data-layer='{"id":131916,`
    + `"title":"Ángel Expósito entrevista al comandante Luis Benítez","credits":"Redacción"}' src="/assets/clips/cart.mp4">`
    + `</video></div></article>`),
  // as.com: podcast players whose only name is the play button's label,
  // beside a title element the page keeps hidden until a stream goes live.
  [`a.test/aspod.html`]: html("AS.com - Diario online deportivo",
    `<meta property="og:title" content="AS.com - Diario online deportivo">`,
    [["as1", "Axel Torres y el sueño del Real Madrid | Minuto 116"], ["as2", "La nadadora española de 16 años que bate récords"]]
      .map(([id, t]) => `<article class="s s--aup"><div class="w w-ap"><div class="core_player_container">`
      + `<div class="core_controller_container" data-core-src="/assets/clips/${id}.mp3">`
      + `<audio src="/assets/clips/${id}.mp3" preload="none"></audio></div><div class="core_skin">`
      + `<button class="mm_boton mm_boton_play" aria-label="Reproducir ${t}"></button>`
      + `<div class="mm_live_title" style="display: none;">Hidden template title</div>`
      + `<div class="mm_progreso" role="slider" aria-label="Progreso de la reproducción"></div></div></div></div></article>`).join("")),
  // elespanol.com: a video card whose heading follows the clip, and whose
  // card also holds an inline JSON-LD script and an SVG icon (text no one
  // sees); two empty <video>s elsewhere on the page.
  [`a.test/elespanol.html`]: html("EL ESPAÑOL - Diario digital",
    `<meta property="og:title" content="EL ESPAÑOL - Diario digital">`,
    `<div class="storylines"><video muted></video><video muted></video></div>`
    + `<article class="art art--video"><div class="art__media ratio ratio--16-9"><video preload="none" muted loop`
    + ` poster="/img/ee1.jpg"><source src="/assets/clips/ee1.mp4" type="video/mp4"></video></div>`
    + `<div class="art__body"><span class="art__kicker">10.000 PASOS EN CEUTA</span><div class="art__title-wrapper">`
    + `<h2 class="art__title art__title--sm"><a href="/reportajes/ceuta.html">Del Tarajal a Loma Colmenar: Ceuta no se fía del desalojo</a></h2></div>`
    + `<address class="art__info is-premium"><svg><title>${"Contenido exclusivo para suscriptores premium. ".repeat(4)}</title></svg>`
    + `<span class="art__author">Cruz Sánchez de Lara</span></address>`
    + `<script type="application/ld+json">${JSON.stringify({ "@type": "NewsArticle", headline: "x".repeat(60),
      description: "Una descripción larga del reportaje que no se ve en la tarjeta. ".repeat(8) })}</script></div></article>`),
  // lasprovincias.es at the moment its loop was fetched: the only <video>
  // on a front page, inside a link to its story beside the headline.
  [`a.test/lprov.html`]: html("Las Provincias - Diario de noticias",
    `<meta property="og:title" content="Las Provincias - Diario de noticias">`,
    `<article class="v-a"><figure class="v-a-img-c"><a class="v-a-lnk" href="/comunitat/tormenta-safor.html">`
    + `<video preload="none" muted loop playsinline><source src="/assets/clips/gandia_loop.mp4" type="video/mp4"></video>`
    + `<img src="/img/gandia.png" alt="Así se formó la tormenta"></a></figure><div class="v-a-inf-c">`
    + `<h2 class="v-a-t"><a href="/comunitat/tormenta-safor.html">Así se formó la tormenta perfecta sobre la Safor</a></h2>`
    + `<p class="v-op">Patricia Cabezuelo</p></div></article>`
    + `<article class="v-a"><h2 class="v-a-t"><a href="/otra.html">Otra noticia sin vídeo</a></h2></article>`),
  // ...and an article page's one clip, in no link: the page's own fields.
  [`a.test/lprov-article.html`]: html("Así se formó la tormenta - Las Provincias",
    `<meta property="og:title" content="Así se formó la tormenta perfecta">`,
    `<article><h1>Así se formó la tormenta perfecta</h1><figure><video preload="none" controls src="/assets/clips/artclip.mp4"></video></figure>`
    + `<h2>Lo que dice la AEMET</h2><p>Texto.</p><a href="/otra.html">Otra noticia</a></article>`),
  // The Daily Mail's shorts carousel: the clip list in the carousel's
  // data-videos, a clip's title blank and its story linked; the story's
  // card elsewhere on the page carries the headline.
  [`a.test/dmshorts.html`]: html("UK Home | Daily Mail Online",
    `<meta property="og:title" content="UK Home | Daily Mail Online">`,
    `<div class="article-preview" data-title="Moment murderer overtakes car before headteacher">`
    + `<a href="/news/article-1/murderer-overtakes-car.html"><div class="article-preview-image"><img src="/img/dm1.jpg" alt=""></div>`
    + `<h2 class="headline"><span class="is-paywalled"></span>Moment murderer overtakes car before hitting cyclist</h2></a></div>`
    + `<div class="mol-web-mobile" data-videos='${JSON.stringify([
      { buttonLabel: "READ MORE", duration: 32000, id: 1, poster: "/img/s1.jpg", productUrl: "/news/article-1/murderer-overtakes-car.html",
        renditions: [{ frameHeight: 480, frameWidth: 270, size: 1, url: "/assets/clips/s1/480x270_MP4_3641.mp4", videoContainer: "MP4" },
          { frameHeight: 640, frameWidth: 360, size: 2, url: "/assets/clips/s1/640x360_MP4_3641.mp4", videoContainer: "MP4" }],
        title: " " },
      { buttonLabel: "Read More", duration: 35000, id: 2, poster: "/img/s2.jpg", productUrl: "/tvshowbiz/article-2/jlo.html",
        renditions: [{ frameHeight: 480, frameWidth: 270, size: 1, url: "/assets/clips/s2/480x270_MP4_7247.mp4", videoContainer: "MP4" }],
        title: "Jennifer Lopez casually dating" }])}'><ul>`
    + `<li><div class="thumbnail"><video preload="none" loop><source src="/assets/clips/s1/480x270_MP4_3641.mp4" type="video/mp4">`
    + `<source src="/assets/clips/s1/640x360_MP4_3641.mp4" type="video/mp4">Sorry, your browser doesn't support embedded videos.</video></div></li>`
    + `<li><div class="thumbnail"><video preload="none" loop><source src="/assets/clips/s2/480x270_MP4_7247.mp4" type="video/mp4"></video></div></li>`
    + `</ul></div>`),
  // NYT: the clips play in a web component's shadow root, each with its
  // master, a child playlist and an mp4 as <source>s; the headline box
  // beside the component holds a kicker and the headline.
  [`a.test/nyt.html`]: html("The New York Times",
    `<meta property="og:title" content="The New York Times">`,
    [["nyt1", "Opinion", "Silicon Valley Is Worried"], ["nyt2", "The Athletic", "These Factors Could Limit the Knicks"]]
      .map(([id, kicker, h]) => `<article data-testid="feed-item" class="_feed-item_1qgl2_157"><div class="_player-container_1qgl2_171">`
      + `<nyt-betamax data-testid="feed-player" data-clip="${id}"></nyt-betamax></div>`
      + `<div class="_headline-container_1qgl2_254"><div class="_label_1qgl2_269">${kicker}</div><p>${h}</p></div></article>`).join("")
    + `<script>customElements.define("nyt-betamax", class extends HTMLElement {
        connectedCallback() {
          if (this.shadowRoot) return;
          const id = this.dataset.clip;
          this.attachShadow({ mode: "open" }).innerHTML = '<div class="_video"><div><span class="_pool-render-target">'
            + '<video preload="none"><source src="/assets/clips/' + id + '/master.m3u8" type="application/vnd.apple.mpegurl">'
            + '<source src="/assets/clips/' + id + '/index-f1-v1-a1.m3u8" type="application/vnd.apple.mpegurl">'
            + '<source src="/assets/clips/' + id + '_1080p.mp4" type="video/mp4"></video></span></div></div>';
        }
      });</script>`),
  // NBC: the page's state in __NEXT_DATA__, each clip's headline beside its
  // assets (an HLS master and an mp4); the players are MSE (no URL in the
  // DOM). Untitled clips in a titled list are each their own.
  [`a.test/nbc.html`]: html("NBC News - Breaking Headlines",
    `<meta property="og:title" content="NBC News - Breaking Headlines">`,
    `<div class="jw-wrapper"><video></video></div><div class="jw-wrapper"><video></video></div>`
    + `<script id="__NEXT_DATA__" type="application/json">${JSON.stringify({ props: { pageProps: { layout: { items: [
      { id: "v1", headline: { primary: "Man convicted of animal cruelty for stealing a flamingo" },
        description: { primary: "The details on the conviction." }, url: { canonical: "/video/flamingo-271857733553" },
        primaryImage: { url: { primary: "/img/flamingo.jpg" } },
        videoAssets: [{ assetType: "EMP", format: "M3U", publicUrl: "/assets/clips/nbc1/1/abs/index.m3u8", height: 1080, width: 1920, bitrate: 4085000 },
          { assetType: "akamaiMp4", format: "MPEG4", publicUrl: "/assets/clips/nbc1_4500.mp4", height: 1080, width: 1920, bitrate: 4085514 }] },
      { id: "v2", headline: { primary: "Swimmer rescued off the California coast" }, url: { canonical: "/video/swimmer-1" },
        videoAssets: [{ assetType: "EMP", format: "M3U", publicUrl: "/assets/clips/nbc2/1/abs/index.m3u8", height: 1080, width: 1920 },
          { assetType: "akamaiMp4", format: "MPEG4", publicUrl: "/assets/clips/nbc2_4500.mp4", height: 1080, width: 1920 }] },
      { title: "Playlist title", clips: [{ url: "/assets/clips/u1.mp4", thumbnail: "/img/u1.jpg", height: 720 },
        { url: "/assets/clips/u2.mp4", thumbnail: "/img/u2.jpg", height: 720 }] },
      { section: { title: "Top videos", hero: { hls: "/assets/clips/w1/master.m3u8" },
        promo: { hls: "/assets/clips/w2/master.m3u8" } } },
      { title: "Format twin clip", hls: { url: "/assets/clips/f1/master.m3u8" }, cmaf: { url: "/assets/clips/f1/cmaf.m3u8" } },
    ] } } } })}</script>`),
  // A podcast widget's page: its episode as JSON, a playback and a
  // download URL for the one episode; no media element.
  [`a.test/podwidget.html`]: html("Spreaker",
    "",
    `<div id="widget-container"><div class="episode">GAZC: un taller que se convirtió en multinacional</div></div>`
    + `<script type="application/json" id="episode-data">${JSON.stringify({ episode: { episode_id: 69098857,
      title: "GAZC: un taller que se convirtió en multinacional", image_url: "/img/ep.jpg",
      playback_url: "/assets/clips/ep/play.mp3", download_url: "/assets/clips/ep/download.mp3" } })}</script>`),

  // 12. The device audit's second run (2026-10-20 12:48).
  // La Vanguardia: a carousel of cards labelled "Vertical <title> (N de 3)"
  // around the clip's poster, the first card playing a preview; a lightbox
  // (closed) plays each clip under a larger poster beside its mute button
  // and a "read the story" link, with no title.
  [`a.test/lvcarousel.html`]: html("La Vanguardia",
    `<meta property="og:title" content="La Vanguardia">`,
    `<section id="jwc-widget"><div id="jwc-carousel" class="jwc-carousel">`
    + [["lv1", "Mourinho se sale con la suya"], ["lv2", "Sergi Bruguera y el relato del hijo"], ["lv3", "Así suena la tormenta en Gandia"]]
      .map(([id, t], i) => `<article class="jwc-card" role="button" aria-label="Vertical ${t} (${i + 1} de 3)">`
        + `<img class="jwc-poster" src="/img/jw/${id}/poster.jpg?width=480" alt="">`
        + (i === 0 ? `<video src="/assets/clips/${id}-o1.mp4" preload="auto" muted playsinline></video>` : "")
        + `</article>`).join("")
    + `</div></section><div id="jwc-modal" aria-hidden="true"><button class="jwc-close" aria-label="Cerrar"></button>`
    + `<div class="jwc-stage"><div class="jwc-track" id="jwc-track">`
    + ["lv1", "lv2", "lv3", "lvx"].map((id, i) => `<div class="jwc-slide"><video playsinline preload="none"`
      + ` poster="/img/jw/${id}/poster.jpg?width=720" src="/assets/clips/${id}-n.mp4"></video><div class="jwc-grad"></div>`
      + `<div class="jwc-ctrl"><button class="jwc-btn" aria-label="Reproducir o pausar"></button>`
      + `<button class="jwc-btn" aria-label="Activar o silenciar sonido"></button></div>`
      + (i === 1 ? `<a class="jwc-newslink" href="/deportes/20261019/11662337/sergi-bruguera-relato-hijo.html"`
        + ` aria-label="Leer la noticia relacionada"><span>Ver noticia</span></a>` : "")
      + `</div>`).join("")
    + `</div></div><button class="jwc-vbtn" aria-label="Clip siguiente"></button></div>`
    // a card whose only label is a translation key the page never translated
    + `<div class="promo"><video src="/assets/clips/lvy.mp4" preload="none"></video>`
    + `<button aria-label="player.share_clip"></button></div>`),
  // The Guardian: the front page's only video, in a card laid under a
  // labelled link to its story (no text of its own), its headline beside.
  [`a.test/guardian.html`]: html("Latest news, sport and opinion from the Guardian",
    `<meta property="og:title" content="Latest news, sport and opinion from the Guardian">`,
    `<section id="documentary"><h2>Documentary</h2><ul><li><div class="card">`
    + `<a href="/environment/dream-of-birds" aria-label="Dream of Birds " class="card-link"></a>`
    + `<div class="card-media"><figure class="video-container loop"><video preload="metadata" loop muted playsinline`
    + ` poster="/img/birds.jpg"><source src="/assets/clips/Dream_of_birds_loop_480w.mp4#t=0.001" type="video/mp4"></video>`
    + `</figure></div><div class="card-headline"><span>Documentary</span><h3>Dream of Birds</h3></div></div></li></ul></section>`
    + `<section><ul><li><a href="/world/x"><h3>A story with no clip</h3></a></li></ul></section>`),
  // abc.es: the front page's only clip, a podcast card whose heading links
  // to the episode; the player repeats the same <audio>.
  [`a.test/abcpod.html`]: html("ABC - Últimas noticias",
    `<meta property="og:title" content="ABC - Últimas noticias">`,
    `<article class="v-a"><div class="v-a-inf-c"><h2 class="v-a-t"><a href="/podcast/materia-oscura/nasa.html">`
    + `La NASA busca cuevas para vivir en la Luna</a></h2>`
    + `<audio preload="none" src="/assets/clips/nasa/audio.mp3?dist=web-abc"></audio>`
    + `<div data-voc-title="La NASA busca cuevas para vivir en la Luna"><div class="v-ply"><div>`
    + `<audio preload="none" src="/assets/clips/nasa/audio.mp3?dist=web-abc">Tu navegador no soporta el elemento de audio.</audio>`
    + `</div></div></div></div></article><article class="v-a"><h2 class="v-a-t"><a href="/otra.html">Otra noticia</a></h2></article>`),
  // cope.es: the sticky radio player, the page's only clip, its programme in
  // the player's card.
  [`a.test/copeplayer.html`]: html("COPE | Noticias y radio online",
    `<meta property="og:title" content="COPE | Noticias y radio online"><style>body{font-size:16px}`
    + `.c-card--claim__epigraph__txt{font-size:11px;text-transform:uppercase;letter-spacing:.06em}`
    + `.c-card--claim__title{font-size:16px;font-weight:700}.c-card--claim__info{font-size:13px}</style>`,
    `<div id="player"><section class="o-section--player"><div class="c-player"><div class="c-player__container">`
    + `<div class="c-player__wrapper"><div class="c-card"><article class="c-card--claim c-card--live">`
    + `<figure class="c-card--claim__thumb"><img src="/img/mediodia.jpeg" alt="thumb" width="200" height="200"></figure>`
    + `<div class="c-card--claim__txt"><p class="c-card--claim__epigraph__txt"><span>COPE emisión nacional</span></p>`
    + `<p class="c-card--claim__title"><span>Mediodía COPE</span></p><p class="c-card--claim__info"><span>Con Pilar García</span></p>`
    + `</div></article></div><div class="c-audio c-audio--controls"><audio data-src="/assets/clips/net1.mp3.m3u"`
    + ` src="/assets/clips/net1.mp3?referrer_url=x&amp;player_type=mobile"></audio></div></div></div></div></section></div>`
    + `<main><article><h2><a href="/noticias/x.html">Una noticia de portada</a></h2></article></main>`),
  // ...and an article's "listen to this article" player: its label is a
  // control's wording, so the page's title stays.
  [`a.test/listen.html`]: html("Mayor resigns after flood report",
    `<meta property="og:title" content="Mayor resigns after flood report">`,
    `<article><h1>Mayor resigns after flood report</h1><div class="listen"><p class="listen__title">Escucha la noticia</p>`
    + `<audio src="/assets/clips/tts.mp3"></audio></div><p>Texto del artículo.</p></article>`),
  // as.com: the bridge read a podcast's URL before the player appended its
  // own &dist=…; the <audio> plays the refined URL beside its heading. Two
  // elements refining one URL differently say nothing.
  [`a.test/asdist.html`]: html("AS.com - Diario online deportivo",
    `<meta property="og:title" content="AS.com - Diario online deportivo">`,
    `<article class="s"><h2 class="s__tl">Axel Torres y el sueño inalcanzable</h2>`
    + `<audio src="/assets/clips/asd/audio.mp3?id=1&amp;dist=diarioas-web" preload="none"></audio></article>`
    + `<article class="s"><h2 class="s__tl">La nadadora de 16 años</h2>`
    + `<audio src="/assets/clips/asd/audio2.mp3?id=2&amp;dist=a" preload="none"></audio>`
    + `<audio src="/assets/clips/asd/audio2.mp3?id=2&amp;dist=b" preload="none"></audio></article>`),
  // Spreaker's widget: no media element; the episode's file is the download
  // button's link (a translation key for a label), its title above.
  [`a.test/spwidget.html`]: html("Spreaker",
    "",
    `<div id="widget-container"><div class="player"><div class="player_info"><div class="player_title">`
    + `<a href="https://www.spreaker.com/episode/gazc--69098857" target="_blank">GAZC: cómo un taller se convirtió en una multinacional</a>`
    + `</div><div class="player_actions"><button aria-label="Skip back 10 seconds"></button>`
    + `<a class="button_download player_action" href="/assets/clips/sp/download.mp3" aria-label="player.download_episode"></a>`
    + `</div></div></div></div>`),
  // Reuters: the podcast episodes in a JavaScript state assignment, not a
  // JSON script. Bundle-shaped code before it: JSON-capable arrays that name
  // nothing (they must not use up the per-script count), a double quote in a
  // single-quoted string and a template (the string state must not flip), an
  // opener that never closes.
  [`a.test/fusion.html`]: html("Reuters | Breaking International News",
    `<meta property="og:title" content="Reuters | Breaking International News">`,
    `<script id="fusion-metadata" type="application/javascript">`
    + `define(["a"],function(){});`.repeat(12)
    + `var esc='"',tpl=\`{\`,open=({"pending":1;`
    + `window.Fusion=window.Fusion||{};Fusion.arcSite="reuters";`
    + `Fusion.globalContent=${JSON.stringify({ result: { podcasts: [
      { id: "p1", title: "Why the Everything Bubble keeps on inflating", audio_url: `http://a.test:${P}/assets/clips/rt1.mp3?source=player`,
        image: { url: "/img/rt1.jpg" } },
      { id: "p2", title: "Macron's twilight, Trump's approval and AI hacking", audio_url: `http://a.test:${P}/assets/clips/rt2.mp3?source=player` },
    ] } })};Fusion.deployment="v1";if (Fusion.x == {}) {}</script>`),
  // A pym.js graphic: the iframe titled "Embedded content 1"; its figure's
  // caption is what it shows.
  [`a.test/pym.html`]: html("Reuters",
    "",
    `<figure><iframe title="Embedded content 1" src="http://b.test:${P}/graphic.html" width="300" height="200"></iframe>`
    + `<figcaption>Heat storms are hitting the cocoa farms harder</figcaption></figure>`
    + `<div><p>A paragraph about the second graphic and what it shows.</p>`
    + `<iframe title="Cocoa prices since 2020" src="http://b.test:${P}/graphic2.html"></iframe></div>`),
  [`b.test/graphic.html`]: html("", "", `<video src="/assets/clips/10s_vera_480.mp4" autoplay muted loop></video>`),
  [`b.test/graphic2.html`]: html("", "", `<p>chart</p>`),

  // Distinct names: a label the page puts on three or more different things
  // names none of them, in a language no word list knows. Dutch: every tile's
  // mute button ("Geluid aan/uit"), the title in the picture's alt.
  [`a.test/distinct-nl.html`]: html("Nieuws | Omroep", `<meta property="og:title" content="Nieuws | Omroep">`,
    [["nl1", "Kabinet valt over stikstofplan"], ["nl2", "Storm legt treinverkeer plat"],
      ["nl3", "Ajax wint de klassieker"], ["nl4", "Nieuwe brug over de Maas geopend"]]
      .map(([id, t]) => `<div class="tegel"><video src="/assets/clips/${id}.mp4" preload="none" muted playsinline></video>`
        + `<button class="geluid" aria-label="Geluid aan/uit"></button>`
        + `<img src="/assets/clips/${id}-thumb.jpg" width="320" alt="${t}"></div>`).join("")),
  // Polish: every story card's "see now" link to its own story, the headline
  // under it.
  [`a.test/distinct-pl.html`]: html("Wiadomości", `<meta property="og:title" content="Wiadomości">`,
    [["pl1", "Rząd przyjął budżet na przyszły rok"], ["pl2", "Powódź na południu kraju"], ["pl3", "Legia awansowała do finału"]]
      .map(([id, t]) => `<div class="karta"><a href="/${id}.html" class="obraz"><video src="/assets/clips/${id}.mp4"`
        + ` preload="none" muted></video></a><a href="/${id}.html" class="wiecej">Zobacz teraz</a><h3>${t}</h3></div>`).join("")),
  // Finnish: three different clips' own <video aria-label="Esimerkki">.
  [`a.test/distinct-fi.html`]: html("Uutiset", `<meta property="og:title" content="Uutiset">`,
    [["fi1", "Hallitus esitteli budjetin"], ["fi2", "Myrsky katkaisi sähköt"], ["fi3", "HJK voitti mestaruuden"]]
      .map(([id, t]) => `<div class="kortti"><h4>${t}</h4><video aria-label="Esimerkki" src="/assets/clips/${id}.mp4"`
        + ` preload="none" muted></video></div>`).join("")),
  // Two cuts of one story carry its title on two different clips: kept.
  [`a.test/cuts.html`]: html("News front", "",
    `<div class="c"><video src="/assets/clips/cut-v.mp4" preload="none"></video>`
    + `<button aria-label="Flood waters reach the old town"></button></div>`
    + `<div class="c"><video src="/assets/clips/cut-h.mp4" preload="none"></video>`
    + `<button aria-label="Flood waters reach the old town"></button></div>`
    + `<div class="c"><video src="/assets/clips/elect.mp4" preload="none"></video>`
    + `<button aria-label="Election night in pictures"></button></div>`),
  // One story's title on its play button, its picture's alt and its link's
  // text (Reuters' cards): three kinds of element, one story.
  [`a.test/onestory.html`]: html("Front", "",
    [["s1", "Cocoa farms still reeling from the heat"], ["s2", "Lawmaker hit by a car in Wangaratta"]]
      .map(([id, t]) => `<div class="story"><video src="/assets/clips/${id}.mp4" preload="none"></video>`
        + `<button aria-label="${t}"></button><img src="/assets/clips/${id}.jpg" width="300" alt="${t}">`
        + `<a href="/${id}.html">${t}</a></div>`).join("")),
  // A carousel that clones its slides: one clip's title on three copies of it.
  [`a.test/clones.html`]: html("Carousel", "",
    `<div class="track">` + [["cl1", "Harbour fireworks at midnight"], ["cl2", "Marathon runners cross the bridge"],
      ["cl1", "Harbour fireworks at midnight"], ["cl2", "Marathon runners cross the bridge"], ["cl1", "Harbour fireworks at midnight"]]
      .map(([id, t]) => `<div class="slide"><video src="/assets/clips/${id}.mp4" preload="none"></video>`
        + `<button aria-label="${t}"></button></div>`).join("") + `</div>`),
  // Pictures: a gallery whose photos all carry the story's alt keeps it when
  // nothing else names them; one whose photos have their own captions takes
  // the captions.
  [`a.test/gallery-fi.html`]: html("Galleria", "",
    `<div class="galleria">` + ["g1", "g2", "g3"].map((id) => `<div class="kuva"><img src="/assets/clips/${id}.jpg"`
      + ` width="640" height="400" alt="Tulvat Valenciassa"></div>`).join("") + `</div>`
    + `<div class="galleria2">` + [["h1", "Pelastajat veneessä"], ["h2", "Vesi nousi kaduille"], ["h3", "Asukkaat siivoavat"]]
      .map(([id, c]) => `<figure><img src="/assets/clips/${id}.jpg" width="640" height="400" alt="Tulvat Espanjassa">`
        + `<figcaption>${c}</figcaption></figure>`).join("") + `</div>`),
  // Iframes a generator titles alike but for a number, in Finnish.
  [`a.test/frames-fi.html`]: html("Uutiset", "",
    `<figure><iframe title="Upotettu sisältö 1" src="http://b.test:${P}/graphic2.html?n=1"></iframe>`
    + `<figcaption>Sähkön hinta nousi syksyllä</figcaption></figure>`
    + `<figure><iframe title="Upotettu sisältö 2" src="http://b.test:${P}/graphic2.html?n=2"></iframe>`
    + `<figcaption>Vaalien tulos kunnittain</figcaption></figure>`
    + `<iframe title="Kartta: myrskyn reitti" src="http://b.test:${P}/graphic2.html?n=3"></iframe>`),

  // No word list: pages in languages and with class names no list ever
  // knew — Polish under CSS-module hashes, Turkish — styled the way a news
  // site styles them. What names a clip is read from how the page SHOWS
  // each text (size, weight, capitals, hidden or not), where it sits (in a
  // control, a header, a link whose URL spells the story) and what repeats.
  [`a.test/nolist-pl.html`]: html("Wiadomości - Portal", `<meta property="og:title" content="Wiadomości - Portal"><style>`
    + `body{font-size:16px}._t_a1{font-size:21px;font-weight:700}._k_b2{font-size:14px;text-transform:uppercase;letter-spacing:.08em}`
    + `._a_c3{font-size:13px}._f_d4{font-size:10px;color:#777}._m_e5{font-size:12px}</style>`,
    // p1: a byline before the title; the title is shown larger and bolder
    `<div class="_card_x1"><div class="_media_q9"><video src="/assets/clips/p1.mp4" preload="none"></video>`
    + `<span class="_m_e5">2:41</span><span class="_f_d4">Fot. PAP</span></div>`
    + `<p class="_a_c3">Autor: Anna Nowak, redakcja</p><p class="_t_a1">Powódź zalała centrum Wrocławia w nocy</p></div>`
    // p2: a kicker the stylesheet sets in capitals, before a plain title
    + `<div class="_card_x1"><video src="/assets/clips/p2.mp4" preload="none"></video>`
    + `<span class="_k_b2">pilne z kraju</span><p class="_p_z0">Rząd zapowiada nowe przepisy dotyczące mieszkań</p></div>`
    // p3-p5: the same three-word line in three cards (a partner's mark)
    + `<div class="_card_x1"><video src="/assets/clips/p3.mp4" preload="none"></video>`
    + `<span class="_s_r8">Materiał wideo partnera</span><p class="_p_z0">Pociągi wracają na trasę do Gdańska</p></div>`
    // p4: a two-word "read more" link to a story whose URL spells it; the
    // title only in the picture's alt
    + `<div class="_card_x1"><video src="/assets/clips/p4.mp4" preload="none"></video>`
    + `<span class="_s_r8">Materiał wideo partnera</span>`
    + `<a href="/kraj/2026/10/20/strazacy-gasza-pozar-magazynu.html"><span class="_l_m1">Czytaj więcej</span></a>`
    + `<img src="/assets/clips/p4.jpg" width="300" alt="Strażacy gaszą pożar magazynu"></div>`
    // p5: a duration with its unit before a two-word title
    + `<div class="_card_x1"><video src="/assets/clips/p5.mp4" preload="none"></video>`
    + `<span class="_s_r8">Materiał wideo partnera</span><span class="_n_q2">12 min</span><span class="_n_q3">Krótki reportaż</span></div>`
    // p6: the card's own <header> holds its title, a longer description after
    + `<article class="_c_77"><div class="_m_1"><video src="/assets/clips/p6.mp4" preload="none"></video></div>`
    + `<header class="_h_2"><h3 class="_hh_3">Nowy most otwarty dla ruchu</h3></header>`
    + `<p class="_d_4">Długi opis mostu, który ma więcej słów niż tytuł i opowiada całą historię budowy.</p></article>`
    // p7: a credit in small type is the card's only text
    + `<div class="_card_x1"><video src="/assets/clips/p7.mp4" preload="none"></video>`
    + `<figcaption class="_f_d4">Agencja Gazeta</figcaption></div>`
    // p8: a slide whose only label is its position, "z" joining the numbers
    + `<div class="_card_x1"><video src="/assets/clips/p8.mp4" preload="none"></video>`
    + `<button class="_b_9" aria-label="Slajd 2 z 5"></button></div>`
    // p9: a credit caption at the page's own size (names joined by a slash)
    + `<div class="_card_x1"><figure class="_fig_1"><video src="/assets/clips/p9.mp4" preload="none"></video>`
    + `<figcaption class="_cap_2">Marek Lis / Reuters</figcaption></figure>`
    + `<p class="_p_z0">Stocznia wraca do produkcji statków</p></div>`
    // p10: a one-word caption, an agency's name in plain case
    + `<div class="_card_x1"><figure class="_fig_1"><video src="/assets/clips/p10.mp4" preload="none"></video>`
    + `<figcaption class="_cap_2">Reuters</figcaption></figure>`
    + `<p class="_p_z0">Rolnicy protestują przed ministerstwem</p></div>`
    // p11: a credit line the page hides from screen readers, before the text
    + `<div class="_card_x1"><video src="/assets/clips/p11.mp4" preload="none"></video>`
    + `<div class="_cr_5" aria-hidden="true"><p class="_p_z0">Wideo Jan Kowalski dla redakcji</p></div>`
    + `<p class="_p_z0">Most w Gdańsku otwarty po trzech latach</p></div>`
    // p12: a list item labelled with the clip's name, an error slate inside
    + `<ul class="_l_7"><li class="_it_6" aria-label="Stadion otwarty po remoncie">`
    + `<video src="/assets/clips/p12.mp4" preload="none"></video>`
    + `<p class="_p_z0">Odtwarzanie nie powiodło się, spróbuj ponownie</p></li></ul>`
    // p13: a short headline whose story URL spells something else
    + `<div class="_card_x1"><video src="/assets/clips/p13.mp4" preload="none"></video>`
    + `<a href="/opinie/2026/10/20/podcast-rozmowa-z-ministrem-finansow.html"><h3 class="_hh_3">Budżet się sypie</h3></a></div>`
    // p14: a picture whose alt is its photographer's handle
    + `<p><img src="/assets/clips/p14.jpg" width="300" alt="@marek.foto"></p>`
    // p15: a copyright caption at the page's own size
    + `<div class="_card_x1"><figure class="_fig_1"><video src="/assets/clips/p15.mp4" preload="none"></video>`
    + `<figcaption class="_cap_2">© Jan Nowak</figcaption></figure>`
    + `<p class="_p_z0">Pierwszy śnieg w Tatrach</p></div>`),
  // Turkish, two clips: nothing to count, so a control's place decides.
  [`a.test/nolist-tr.html`]: html("Haberler - Portal", `<meta property="og:title" content="Haberler - Portal"><style>`
    + `body{font-size:16px}.kart2{position:relative;width:320px;height:180px}.kart2 video{width:320px;height:180px}`
    + `.kaplama{position:absolute;left:0;top:0;width:100%;height:100%;opacity:0}</style>`,
    // t1: a small mute button is the clip's only label
    `<div class="kart"><video src="/assets/clips/t1.mp4" preload="none"></video>`
    + `<button class="ses" aria-label="Sesi aç kapat"></button></div>`
    // t2: a button laid over the whole card, labelled with the clip's name
    + `<div class="kart2"><video src="/assets/clips/t2.mp4" preload="none"></video>`
    + `<button class="kaplama" aria-label="Kapadokya balonları"></button></div>`
    // pictures: an agency dateline with a Turkish month; a role the page's
    // own (Turkish) class names spell; a credit after a colon
    + `<p><img src="/assets/clips/t3.jpg" width="300" alt="ANKARA, TÜRKİYE - EKİM 15: Meclis yeni yasayı onayladı"></p>`
    + `<div class="video-kapak"><div class="kapak-alani"><img class="kapak-gorsel" src="/assets/clips/t4.jpg" width="300" alt="Video kapak"></div></div>`
    + `<p><img src="/assets/clips/t5.jpg" width="300" alt="İtfaiye ekipleri yangına müdahale etti. Fotoğraf: Ali Yılmaz/AA"></p>`
    // an embed its generator titled, its figure captioned
    + `<figure><iframe title="Gömülü içerik 1" src="http://b.test:${P}/graphic2.html?n=tr"></iframe>`
    + `<figcaption>Seçim sonuçları il il harita</figcaption></figure>`
    // a logo (an SVG, no alt) beside the footer's text
    + `<footer class="altbilgi"><a href="/"><img src="/assets/clips/t6.svg" alt=""></a>`
    + `<p>Tüm hakları saklıdır, izinsiz kopyalanamaz</p></footer>`
    // a 16 px menu icon whose alt is one word
    + `<nav class="menu"><a href="/bultenler"><img src="/assets/clips/t7.svg" alt="Bültenler" width="16" height="16"></a></nav>`
    // a wide, short sponsor logo (a raster) after a "a project of" line
    + `<div class="sponsor"><p>Bir projesi</p><img src="/assets/clips/t8.png" alt="" width="207" height="24"></div>`
    // a game card's 400 px SVG glyph, its title beside it in the card link
    + `<a class="oyun" href="/oyunlar/zor-sudoku"><div class="oyun-gorsel"><img src="/assets/clips/t9.svg" alt="" width="400" height="400"></div>`
    + `<div class="oyun-bilgi"><h4>Zor sudoku</h4><span>Sadece uzmanlar için</span></div></a>`),

  // 13. The device audit's third run (2026-10-20 20:48).
  // AP's front page: a gallery of clips and photos crossfading in one box
  // (its real stylesheet and rotation script, shortened), one caption the
  // script rewrites as the slides change.
  [`a.test/apgallery.html`]: html("Associated Press News: Breaking News | Latest News Today",
    `<meta property="og:title" content="Associated Press News: Breaking News | Latest News Today"><style>body{font-size:16px}`
    + `.mixed-gallery-wrap{width:100%;max-width:900px;margin:0 auto}`
    + `.mixed-gallery-display{position:relative;width:100%;aspect-ratio:3/2;background:#000;overflow:hidden}`
    + `.mixed-gallery-slot{position:absolute;inset:0;opacity:0;transition:opacity .7s ease;pointer-events:none}`
    + `.mixed-gallery-slot.active{opacity:1;pointer-events:auto}`
    + `.mixed-gallery-slot img,.mixed-gallery-slot video{width:100%;height:100%;object-fit:cover;display:block}`
    + `.mixed-gallery-caption-text{font-size:13px;color:#666;font-weight:400;letter-spacing:.01em;line-height:1.4;margin:0}`
    + `h3{font-size:18px;font-weight:700}</style>`,
    `<div class="mixed-gallery-wrap"><div class="mixed-gallery-display">`
    + [["v", "apv0"], ["i", "ap1"], ["v", "apv2"], ["i", "ap3"], ["v", "apv4"]].map(([k, id], i) =>
      `<div class="mixed-gallery-slot" data-gallery-index="${i}">`
      + (k === "v" ? `<video muted playsinline preload="auto"><source src="/assets/clips/${id}-Z9ob0dUs.mp4" type="video/mp4"></video>`
        : `<img src="/img/${id}.jpg" alt="A protester throws a stone during a protest in Tirana. (AP Photo/Boris Grdanoski)">`)
      + `</div>`).join("")
    + `</div><div class="mixed-gallery-caption-bar"><p class="mixed-gallery-caption-text"></p></div></div>`
    + `<script>(function () {
      var gallery = document.currentScript.previousElementSibling;
      var ITEMS = ["Aftermath of the weekend protests over the cost of living turned into rioting and looting across France. (AP Video)",
        "Tagging on the statue of Equality, in Paris, Oct. 20, 2026. (AP Photo/Christophe Ena)",
        "Workers clean the Equality at the Republique square, after a quiet night under curfew. (AP Video)",
        "A protester throws a stone during a protest in Tirana. (AP Photo/Boris Grdanoski)",
        "Protesters fire fireworks at the police in Tirana, Albania, Oct. 19, 2026. (AP Video)"];
      var slots = gallery.querySelectorAll(".mixed-gallery-slot");
      var caption = gallery.querySelector(".mixed-gallery-caption-text");
      window.__showItem = function (index) {
        for (var i = 0; i < slots.length; i++) slots[i].classList.toggle("active", i === index);
        caption.textContent = ITEMS[index];
      };
      window.__showItem(0);
    })();</script>`
    + `<div class="PagePromo"><h3><a href="/article/storm-1">Storm brings floods to the coast</a></h3>`
    + `<video muted preload="none" src="/assets/clips/promo1.mp4"></video></div>`),
  // cope.es with clips elsewhere on the page: the radio player's programme in
  // an <article> beside its <audio>, a marquee repeating the title, and an
  // empty <video> the page keeps hidden for a video stream.
  [`a.test/copeplayer3.html`]: html("COPE | Noticias y radio online",
    `<meta property="og:title" content="COPE | Noticias y radio online"><style>body{font-size:16px}`
    + `.c-card--claim__epigraph__txt{font-size:11px;text-transform:uppercase;letter-spacing:.06em}`
    + `.c-card--claim__title{font-size:16px;font-weight:700;white-space:nowrap;overflow:hidden}`
    + `.c-card--claim__title span{display:inline-block;padding-right:2em}.c-card--claim__info{font-size:13px}</style>`,
    `<div id="player"><section class="o-section--player"><div class="c-player"><div class="c-player__container">`
    + `<div class="c-player__wrapper"><div class="c-card"><article class="c-card--claim c-card--live">`
    + `<figure class="c-card--claim__thumb"><picture><img src="/img/linterna.jpeg" alt="thumb" width="200" height="200"></picture></figure>`
    + `<div class="c-card--claim__video" style="display: none;"><video playsinline></video></div>`
    + `<div class="c-card--claim__txt"><div class="c-card--claim__epigraph"><p class="c-card--claim__epigraph__txt"><span>COPE emisión nacional</span></p></div>`
    + `<p class="text-marquee-player c-card--claim__title"><span>La Linterna</span><span>La Linterna</span></p>`
    + `<p class="c-card--claim__info"><span class="c-card--claim__lead">Con Ángel Expósito</span></p></div></article></div>`
    + `<div class="c-audio c-audio--controls"><audio data-src="/assets/clips/net3.mp3.m3u" src="/assets/clips/net3.mp3?referrer_url=x&amp;player_type=mobile"></audio>`
    + `<div class="c-audio__controls"><button class="c-audio__player__button c-audio__button"></button>`
    + `<div class="c-audio__volume"><button type="submit" class="c-audio__volume__button"> Volumen </button></div></div>`
    + `<div class="c-audio__txt"><span class="c-audio__title">&nbsp;</span></div></div></div></div></div></section></div>`
    + `<main><article><h3>Herrera, sobre la ley de nietos</h3><video preload="none" src="/assets/clips/cv31.mp4"></video></article>`
    + `<article><h3>La lluvia deja calles cortadas</h3><video preload="none" src="/assets/clips/cv32.mp4"></video></article></main>`),
  // higgsfield.ai: a promo card's heading set in capitals throughout, its
  // two lines two <span class="block">s; another card's heading holds a
  // kicker set apart in small capitals.
  [`a.test/higgs.html`]: html("Higgsfield - Choose a creation flow",
    `<meta property="og:title" content="Higgsfield - Choose a creation flow"><style>body{font-size:16px}`
    + `.block{display:block}.uppercase{text-transform:uppercase}.font-bold{font-weight:700}.t28{font-size:28px}.t12{font-size:12px}`
    + `.card{position:relative;display:block;width:340px;height:190px}.card video,.card img{position:absolute;inset:0;width:100%;height:100%}</style>`,
    `<section aria-label="News"><div class="track">`
    + `<div class="slide"><button type="button" class="card"><img src="/img/hero.jpg" alt="" aria-hidden="true">`
    + `<video loop muted playsinline preload="metadata" aria-hidden="true" src="/assets/clips/hpromo.mp4">Your browser does not support the video.</video>`
    + `<span aria-hidden="true"></span><div><h3 class="t28 font-bold uppercase"><span class="block">Sign up and get your</span>`
    + `<span class="block">extra discount</span></h3><div class="t12"><ul><li><span>Get unlimited Nano Banana Pro</span></li>`
    + `<li><span>Unlock your extra discount</span></li></ul></div></div></button></div>`
    + `<div class="slide"><button type="button" class="card"><video loop muted playsinline preload="metadata" aria-hidden="true"`
    + ` src="/assets/clips/hpromo2.mp4"></video><div><h3 class="t28 font-bold"><span class="uppercase t12">New</span>`
    + ` Katana edits your clips</h3></div></button></div></div></section>`),
  // as.com when its first podcast player was reported: the page's only
  // <audio>, in a block the page keeps hidden, its name only on the play
  // button; a list of other stories beside it.
  [`a.test/aslone.html`]: html("AS.com - Diario online deportivo",
    `<meta property="og:title" content="AS.com - Diario online deportivo"><style>body{font-size:16px}._hidden{display:none}</style>`,
    `<main class="wr-c"><div class="dp-load"><div class="b _hidden"><div class="b_w"><div class="b_gr">`
    + `<article class="s s--h s--aup"><div class="w w-ap"><div class="core_player_container"><div class="core_inner">`
    + `<div class="core_controller_container" data-core-src="/assets/clips/asn.mp3?accessToken=x">`
    + `<audio src="/assets/clips/asn.mp3?accessToken=x" preload="none"></audio></div><div class="core_skin">`
    + `<div class="mm_AudioPlayer"><div class="mm_controles"><button class="mm_boton mm_boton_play" aria-label="Reproducir La nadadora`
    + ` española de 16 años que ha batido el récord de Mireia Belmonte y que asombra al mundo"></button>`
    + `<div class="mm_live_title" style="display: none;"> En directo </div>`
    + `<div class="mm_progreso" role="slider" aria-label="Progreso de la reproducción"></div>`
    + `<span class="mm_duracion"><span aria-label="Tiempo actual: 00:00">00:00</span> <span>19:38</span></span></div>`
    + `<div class="mm_controles"><button class="mm_boton" aria-label="Silenciar audio"></button></div></div></div></div></div></div>`
    + `</article></div></div></div></div><section><h2>Lo último</h2><article><h3><a href="/futbol/otra-noticia.html">`
    + `Otra noticia del día</a></h3></article></section></main>`),
  // ...and an article's own "listen" player: a button whose label says what
  // it does; the page's title stays.
  [`a.test/listenbtn.html`]: html("El Ayuntamiento aprueba el presupuesto",
    `<meta property="og:title" content="El Ayuntamiento aprueba el presupuesto">`,
    `<article><h1>El Ayuntamiento aprueba el presupuesto</h1><div class="tts">`
    + `<button class="tts__play" aria-label="Escuchar esta noticia en audio"></button><audio src="/assets/clips/tts2.mp3"></audio></div>`
    + `<p>${"Texto del artículo con varias frases sobre el pleno y sus votaciones. ".repeat(8)}</p></article>`),
  // Spreaker's widget on elespanol.com: the download button links to the
  // file with parameters of its own; the widget's state lists the file with
  // no title.
  [`a.test/spdl.html`]: html("Spreaker",
    "",
    `<div id="widget-container"><div class="player"><div class="player_info">`
    + `<div class="player_title"><a href="https://open.spreaker.com/A4NZ?deep_link_value=x" target="_blank">`
    + `GAZC: cómo un taller se convirtió en una multinacional aeronáutica</a></div><div class="player_actions">`
    + `<button class="player_action button_skip" aria-label="Skip back 10 seconds"></button>`
    + `<a class="button_like player_action" href="https://open.spreaker.com/A4NZ?like=1" aria-label="Like episode"></a>`
    + `<button class="button_share player_action" aria-label="Share episode on social media"></button>`
    + `<a href="/assets/clips/spq/download.mp3?dl=true&amp;referrer=https%3A%2F%2Fwww.elespanol.com%2F&amp;application_id=50"`
    + ` class="button_download player_action" aria-label="player.download_episode"></a>`
    + `<button class="button_info player_action" aria-label="Read description"></button></div>`
    + `<a class="player_logo" href="https://open.spreaker.com/A4NZ?logo=1" aria-label="Listen on Spreaker"></a></div></div></div>`
    + `<script type="application/json" id="widget-state">${JSON.stringify({ episode: { episode_id: 69098857,
      download_url: `http://a.test:${P}/assets/clips/spq/download.mp3` } })}</script>`),
  // NYT: each player a <div role="group"> in a web component's shadow root,
  // labelled "<title> video" once it holds its clip and "Video player"
  // before; the test ids and the components' names are the developer's.
  [`a.test/nytlabels.html`]: html("The New York Times - Breaking News",
    `<meta property="og:title" content="The New York Times - Breaking News">`,
    `<section class="_feed_x1">`
    + [["l1", "Trump Campaigns for Republicans as Turmoil Mounts in Washington video"],
      ["l2", "France to Extend Curfews After Largest Riots in Years video"],
      ["l3", "No, Really, Is A.I. a Bubble? video"], ["l4", "Video player"]].map(([id, label]) =>
      `<div class="_item_1qgl2"><nyt-video-feed><nyt-betamax data-clip="${id}" data-label="${label}"></nyt-betamax></nyt-video-feed></div>`).join("")
    + `</section><script>customElements.define("nyt-betamax", class extends HTMLElement {
        connectedCallback() {
          if (this.shadowRoot) return;
          this.attachShadow({ mode: "open" }).innerHTML = '<div data-testid="betamax-player" role="group" aria-label="'
            + this.dataset.label + '" class="_container_a7gwk_5 container-target"><div class="_video_a7gwk_30">'
            + '<div data-testid="pool-render-container"><video data-testid="betamax-video" preload="none" muted>'
            + '<source src="/assets/clips/' + this.dataset.clip + '/master.m3u8" type="application/vnd.apple.mpegurl">'
            + '</video></div></div></div>';
        }
      });</script>`),
  // Three players labelled "Video player" (hashed class names: the words
  // are no developer's here), each card's headline beside.
  [`a.test/nytcensus.html`]: html("The New York Times - Breaking News",
    `<meta property="og:title" content="The New York Times - Breaking News">`,
    [["c1", "Floods close the coastal road"], ["c2", "A new bridge opens in the north"], ["c3", "Farmers protest outside the ministry"]]
      .map(([id, h]) => `<div class="_a1"><div role="group" aria-label="Video player" class="_c_9a">`
        + `<video preload="none" src="/assets/clips/${id}.mp4"></video></div><h3>${h}</h3></div>`).join("")),
  // ...and one "Video player" alone: its words are the page's test ids and
  // component names.
  [`a.test/nytvocab.html`]: html("The New York Times - Breaking News",
    `<meta property="og:title" content="The New York Times - Breaking News">`,
    `<article class="_story_k1"><div class="media-container ZzXvKG_container"><nyt-video-feed class="fill-container">`
    + `<nyt-betamax class="hLWj5q_betamax"></nyt-betamax></nyt-video-feed></div><h3>Where Do Autoimmune Diseases Come From?</h3></article>`
    + `<div class="_b2"><video preload="none" src="/assets/clips/nvother.mp4"></video></div>`
    + `<script>customElements.define("nyt-betamax", class extends HTMLElement {
        connectedCallback() {
          if (this.shadowRoot) return;
          this.attachShadow({ mode: "open" }).innerHTML = '<div data-testid="betamax-player" role="group" aria-label="Video player"'
            + ' class="_container_a7gwk_5 container-target"><div class="_video_a7gwk_30"><div data-testid="pool-render-container">'
            + '<video data-testid="betamax-video" preload="none" muted><source src="/assets/clips/nv1/master.m3u8"'
            + ' type="application/vnd.apple.mpegurl"></video></div></div></div>';
        }
      });</script>`),
  // Labels that start with one common word are titles, not a template.
  [`a.test/thelabels.html`]: html("News front", "",
    [["t1", "The fall of the old bridge"], ["t2", "The storm reaches the coast"], ["t3", "The mayor resigns after the report"],
      ["t4", "Farmers protest in the capital"]].map(([id, t]) =>
      `<div class="tile"><video aria-label="${t}" preload="none" src="/assets/clips/${id}.mp4"></video></div>`).join("")),
  // antena3.com: story posters whose words several stories share ("premio")
  // in the middle of their names, one photo in two crops; three posters
  // under a template's words.
  [`a.test/antena3r3.html`]: html("Antena 3 Noticias",
    `<meta property="og:title" content="Antena 3 Noticias">`,
    `<ol class="carousel-stories__track">`
    + ["rosalia-premio-nacional-musicas-actuales", "premio-planeta-cumple-75-anos", "isabel-allende-premio-planeta-2026",
      "vertical-thumb-lluvias-torrenciales-valencia", "vertical-thumb-nuevo-puente-bilbao", "vertical-thumb-feria-libro-madrid"]
      .map((name, i) => `<li class="carousel-stories__item"><button type="button" class="carousel-stories__card" aria-label="Ver ahora">`
        + `<span class="carousel-stories__media"><video poster="/fotos/${name}_68.jpg?crop=853,1516&amp;width=360"`
        + ` src="/assets/clips/a3r${i}.mp4" muted loop preload="none" aria-hidden="true"></video></span>`
        + `<span class="carousel-stories__cta"><span>Ver ahora</span></span></button></li>`).join("")
    + `</ol><div class="grid">`
    + ["rosalia-premio-nacional-musicas-actuales", "premio-planeta-cumple-75-anos", "isabel-allende-premio-planeta-2026"]
      .map((name) => `<img src="/fotos/${name}_98.jpg?width=640" alt="">`).join("")
    + `</div>`),

  // 8. A player config inlined as JSON: every '&' written \u0026 and every
  //    '/' as \/ (Vimeo's window.playerConfig). The scrape reports the URL
  //    the page fetches, or nothing claimed or fetched ever matches it.
  [`a.test/inline-config.html`]: html("Player",
    "",
    `<script>window.playerConfig = {"request":{"files":{"progressive":[{"quality":"360p",`
    + `"url":"http:\\/\\/a.test:${P}\\/assets\\/clips\\/cfg.mp4?a=1\\u0026b=2"}]}}}</script>`),
};

const server = http.createServer((req, res) => {
  const host = (req.headers.host || "").split(":")[0];
  const body = SITE[`${host}${req.url.split("?")[0]}`];
  if (body == null) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { "content-type": "text/html; charset=utf-8" });
  res.end(body.split(P).join(String(server.address().port)));
});
await new Promise((r) => server.listen(0, "127.0.0.1", r));
const port = server.address().port;
const a = A.replace(P, port);
const b = B.replace(P, port);

// The extension's `browser`, per frame: the responder registers on
// onMessage, the scanner's batches land in __reports.
const STUB = `window.__listeners = []; window.__reports = [];
window.browser = { runtime: {
  sendNativeMessage: () => Promise.resolve(false),
  sendMessage: (m) => { window.__reports.push(m); return Promise.resolve(false); },
  onMessage: { addListener: (f) => window.__listeners.push(f) } },
  tabs: { sendMessage: () => Promise.resolve() } };
window.__ask = async (u) => {
  for (const f of window.__listeners) {
    const r = f({ kind: 'get-page-metadata', mediaUrl: u });
    if (r !== undefined) return await r;
  }
  return null;
};`;

let failures = 0;
let checks = 0;
const expect = (ok, label, detail) => {
  checks++;
  if (ok) {
    console.log(`  ok   ${label}`);
  } else {
    failures++;
    console.log(`  FAIL ${label}${detail ? `  (${detail})` : ""}`);
  }
};

let browser;
try {
  browser = await chromium.launch({
    args: ["--host-resolver-rules=MAP *.test 127.0.0.1", "--no-proxy-server"],
  });
} catch (e) {
  console.log(`SKIP: Chromium did not launch (${e.message.split("\n")[0]})`);
  server.close();
  process.exit(0);
}

// Load a page, inject the content script into every frame, let the scanner
// flush (BATCH_MS 200), return a helper bound to that page.
async function open(url) {
  const page = await (await browser.newContext()).newPage();
  await page.route("**/*", (r) =>
    new URL(r.request().url()).hostname.endsWith(".test") ? r.continue() : r.abort());
  await page.addInitScript(STUB);
  await page.goto(url, { waitUntil: "load" });
  for (const f of page.frames()) await f.addScriptTag({ content: cs });
  await page.waitForTimeout(500);
  const frame = (pathPart) => page.frames().find((f) =>
    pathPart === "srcdoc" ? f.url() === "about:srcdoc" : f.url().endsWith(pathPart));
  const ask = async (f, clip) => {
    const meta = await f.evaluate((u) => window.__ask(u), `${a}/assets/clips/${clip}`);
    return { meta, pick: meta ? pickCaptureMeta(meta) : null };
  };
  const scanned = async (f, clip) => {
    const reports = await f.evaluate(() => window.__reports.filter((r) => r.kind === "images-detected"));
    for (const r of reports) {
      const m = r.meta && r.meta[`${a}/assets/clips/${clip}`];
      if (m) return pickCaptureMeta(m);
    }
    return null;
  };
  const scannedImage = async (f, file) => {
    const reports = await f.evaluate(() => window.__reports.filter((r) => r.kind === "images-detected"));
    for (const r of reports) {
      const m = r.meta && r.meta[`${a}/assets/clips/${file}`];
      if (m) return pickImageMeta(m);
    }
    return null;
  };
  return { page, frame, ask, scanned, scannedImage };
}

try {
  console.log(`content script: ${csPath}`);

  console.log("same-origin parent (the firedown.app /demo shape)");
  {
    const { frame, ask, scanned } = await open(`${a}/home.html`);
    const demo = frame("/demo/index.html");
    const inner = frame("/demo/inner.html");
    const top = frame("/home.html");

    let r = await ask(demo, "a.mp4");
    expect(r.pick?.name === "Clip A", "iframe clip named by the parent's firedown+json (wire query)", r.pick?.name);
    expect(r.meta?.videoLdMatchThumbnail === `${a}/assets/clips/a.jpg`,
      "declared relative thumbnail made absolute against the PARENT", r.meta?.videoLdMatchThumbnail);
    const s = await scanned(demo, "a.mp4");
    expect(s?.name === "Clip A", "the DOM-scan report carries the same name", s?.name);

    r = await ask(demo, "b.mp4");
    expect(r.pick?.name === "Clip B", "path-relative VideoObject contentUrl resolved against the parent's base", r.pick?.name);
    expect(r.pick?.img === `${a}/thumbs/b.jpg`, "VideoObject thumbnail absolute against the parent", r.pick?.img);

    r = await ask(demo, "c.mp3");
    expect(r.meta?.audioLdName === "Clip C" && r.meta?.audioRole === "content",
      "AudioObject in the parent names the iframe's audio", `${r.meta?.audioLdName}/${r.meta?.audioRole}`);

    r = await ask(demo, "own.mp4");
    expect(r.pick?.name === "Frame Own", "the frame's own declaration wins over the parent's", r.pick?.name);

    r = await ask(demo, "d.mp4");
    expect(r.pick?.name === "Demo frame", "an undeclared clip keeps the FRAME's title", r.pick?.name);
    expect(r.meta?.ogTitle === "" && r.meta?.ogImage === "",
      "the parent's og:title / og:image never cross frames", `${r.meta?.ogTitle}|${r.meta?.ogImage}`);

    r = await ask(inner, "a.mp4");
    expect(r.pick?.name === "Clip A", "two frames down still reaches the declaring page", r.pick?.name);

    r = await ask(top, "a.mp4");
    expect(r.pick?.name === "Clip A", "top frame: a malformed block does not hide the next one", r.pick?.name);

    r = await ask(frame("srcdoc"), "sd.mp4");
    expect(r.pick?.name === "Clip SD", "srcdoc child (inherits the origin) reads the parent", r.pick?.name);
  }

  console.log("cross-origin parent");
  {
    const { frame, ask } = await open(`${b}/xhome.html`);
    const r = await ask(frame("/xframe.html"), "x.mp4");
    expect(r.meta !== null, "the responder still answers (the cross-origin read did not throw out)");
    expect(r.pick?.name === "X frame", "a cross-origin parent's declarations are never read", r.pick?.name);
  }

  console.log("same-origin top behind a cross-origin middle frame");
  {
    const { frame, ask } = await open(`${a}/mhome.html`);
    const r = await ask(frame("/minner.html"), "m.mp4");
    expect(r.pick?.name === "Clip M", "the walk climbs past the middle and reads the top", r.pick?.name);
  }

  console.log("sandboxed (opaque-origin) frame");
  {
    const { frame, ask } = await open(`${a}/shome.html`);
    const r = await ask(frame("/sframe.html"), "s.mp4");
    expect(r.meta !== null && r.pick?.name === "S frame",
      "a sandboxed frame does not read its parent", r.pick?.name);
  }

  console.log("news front page (Video.js players, podcast cards)");
  {
    const { frame, ask, scanned } = await open(`${a}/front.html`);
    const top = frame("/front.html");

    let r = await ask(top, "s1/master.m3u8");
    expect(r.pick?.name === "Calles inundadas en Miramar", "an MSE player's clip is named by its player", r.pick?.name);
    expect(r.pick?.img === `${a}/img/s1.jpg`, "...and pictured by its own poster, not the first player's", r.pick?.img);
    r = await ask(top, "s2/master.m3u8");
    expect(r.pick?.name === "Noche complicada en Bellreguard" && r.pick?.img === `${a}/img/s2.jpg`,
      "a second player gets its own name and poster", `${r.pick?.name} | ${r.pick?.img}`);
    r = await ask(top, "v0/master.m3u8");
    expect(r.pick?.name === "Lluvias torrenciales en Oliva", "the first player on the page keeps its own too", r.pick?.name);
    r = await ask(top, "s3/master.m3u8");
    expect(r.pick?.name === "Gandia bajo el agua",
      "a player with no clip name takes its card's heading, not a control or \"Video Player\"", r.pick?.name);
    expect(r.pick?.img === `${a}/img/s3.jpg`, "...and the player's poster() when the tech has none", r.pick?.img);

    r = await ask(top, "elsewhere/master.m3u8");
    expect(r.meta?.poster === "" && r.meta?.posterlessClip === true && r.pick?.img === "",
      "a clip no element or player shows takes no other clip's poster", `${r.meta?.poster} | ${r.pick?.img}`);
    r = await ask(top, "ding.mp3");
    expect(r.meta?.posterlessClip === false && r.pick?.img === "/logo.png",
      "an audio file no element shows keeps the page image", `${r.meta?.posterlessClip} | ${r.pick?.img}`);

    r = await ask(top, "ep3.mp3");
    expect(r.pick?.name === "Un podcast con ninos: igualdad y salud", "a podcast card's <audio> is named by its heading", r.pick?.name);
    expect(r.pick?.img === `${a}/img/ep3.png`, "...and pictured by its card's image, not the site logo", r.pick?.img);
    const sc = await scanned(top, "ep4.mp3");
    expect(sc?.name === "Un podcast con ninos: tecnologia" && sc?.img === `${a}/img/ep4.png`,
      "the DOM-scan report of another card carries its own", `${sc?.name} | ${sc?.img}`);

    r = await ask(top, "jw1.mp4");
    expect(r.pick?.name === "Clip uno del dia", "a player's control buttons never title a clip", r.pick?.name);

    // A player that handed its tech an mp4 (Brightcove on lasprovincias.es):
    // the tech is bound, and still named by its player.
    r = await ask(top, "t1.mp4");
    expect(r.pick?.name === "Alerta por inundacion en Gandia" && r.pick?.img === `${a}/img/t1.jpg`,
      "a Video.js tech bound to its mp4 is named by its player", `${r.pick?.name} | ${r.pick?.img}`);
    const reported = await top.evaluate(() => window.__reports
      .filter((x) => x.kind === "images-detected").flatMap((x) => x.urls));
    expect(!reported.includes(`${a}/assets/clips/t1.mp4`),
      "the tech's mp4 is left to the bridge, which reports the whole player", reported.filter((u) => /t1/.test(u)).join(","));
    expect(reported.includes(`${a}/assets/clips/jw1.mp4`) && reported.includes(`${a}/assets/clips/ep3.mp3`),
      "...while other players' and plain elements' files are still reported", reported.filter((u) => /clips/.test(u)).join(","));
    expect(!reported.includes(`${a}/front.html`), "an <audio src=\"\"> is never reported as the page itself",
      reported.filter((u) => /front/.test(u)).join(","));
  }

  console.log("single-clip pages keep the page's own fields");
  {
    let o = await open(`${a}/single.html`);
    let r = await o.ask(o.frame("/single.html"), "only/master.m3u8");
    expect(r.pick?.name === "Article headline", "a single player's page keeps og:title", r.pick?.name);
    expect(r.pick?.img === `${a}/img/only.jpg`, "...with the player's poster", r.pick?.img);
    o = await open(`${a}/solo.html`);
    r = await o.ask(o.frame("/solo.html"), "solo.mp3");
    expect(r.pick?.name === "Episode heading | The show" && r.pick?.img === "/show.jpg",
      "a single podcast's page keeps og:title and og:image", `${r.pick?.name} | ${r.pick?.img}`);
  }

  console.log("inline JSON config: the scraped URL is the one the page fetches");
  {
    const o = await open(`${a}/inline-config.html`);
    const reported = await o.frame("/inline-config.html").evaluate(() => window.__reports
      .filter((x) => x.kind === "images-detected").flatMap((x) => x.urls));
    expect(reported.includes(`${a}/assets/clips/cfg.mp4?a=1&b=2`),
      "a \\u0026 and \\/ escaped URL is reported decoded", reported.join(","));
  }

  console.log("news front page: a looping clip takes its card's headline, never its credit");
  {
    const o = await open(`${a}/cnn.html`);
    const top = o.frame("/cnn.html");
    let r = await o.ask(top, "cnn1.mp4");
    expect(r.pick?.name === "France reels from weekend of chaos",
      "a clip in a story link is named by the headline of the link beside it", r.pick?.name);
    const sc = await o.scanned(top, "cnn1.mp4");
    expect(sc?.name === "France reels from weekend of chaos", "...and so is its DOM-scan report", sc?.name);
    r = await o.ask(top, "cnn4.mp4");
    expect(r.pick?.name === "Journalist hospitalized after attack on olive harvest",
      "...each card its own headline", r.pick?.name);
    r = await o.ask(top, "cnn2.mp4");
    expect(r.pick?.name === "Firefighters battle a blaze near Los Angeles",
      "a slide is named by its caption, its credit left out", r.pick?.name);
    r = await o.ask(top, "cnn3.mp4");
    expect(r.pick?.name === "CNN front page", "a credit alone never names a clip", r.pick?.name);
  }

  console.log("images: a picture is named by the page, not its URL");
  {
    const o = await open(`${a}/images.html`);
    const top = o.frame("/images.html");
    const im = (f) => o.scannedImage(top, f);
    let r = await im("i1.jpg");
    expect(r?.name === "Australian MP Helen Haines speaks to journalists in Canberra, Tuesday, Sept. 8, 2026",
      "an alt names its picture, its credit left out", r?.name);
    expect(r?.description === "Independents rise in Canberra", "the card's headline describes it", r?.description);
    r = await im("i2.jpg");
    expect(r?.name?.startsWith("Democratic U.S. Senate Candidate James Talarico") && r.name.length <= 100
      && !/[ ,.]$/.test(r.name), "an agency dateline is dropped and a long alt is cut at a word", r?.name);
    expect(r?.description?.includes("(Photo by Joel Angel Juarez/Getty Images)"),
      "the whole alt, credit included, is the description", r?.description);
    r = await im("i14.jpg");
    expect(r?.name === "Fernando Tatis Jr. throws the ball", "a dateline in mixed case is dropped too", r?.name);
    r = await im("i3.jpg");
    expect(r?.name === "Midterms: what the ballot says", "a file-name alt gives way to the card's title", r?.name);
    // the <img> of a <picture> shows (and reports) the <source> it picked
    r = await im("i4.webp");
    expect(r?.name === "Una calle de Oliva totalmente inundada",
      "a <picture>'s file is named by its <img>, the alt's full stop dropped", r?.name);
    expect(r?.description === "Helicópteros de rescate actúan en Oliva",
      "a caption that only adds its credit leaves the description to the card", r?.description);
    r = await im("i5.jpg");
    expect(r?.name === "Calle anegada de agua en Quart y Aldaia", "no alt: the figure's caption, without its credit", r?.name);
    r = await im("i6a.jpg");
    expect(!r, "one caption over three pictures names none of them", r?.name);
    r = await im("i7.jpg");
    expect(r?.name === "Petrolero" && r.description === "Un buque petrolero",
      "a one-word alt is a name, the picture's own title its description", `${r?.name} | ${r?.description}`);
    r = await im("i13.jpg");
    expect(r?.name === "Tribunal Constitucional"
      && r.description === "El Pleno del Constitucional decidirá sobre el recurso del Defensor del Pueblo",
      "a title that repeats the alt leaves the description to the headline", `${r?.name} | ${r?.description}`);
    const none = [];
    for (const f of ["i9.jpg", "i10.jpg", "i11.jpg"]) {
      const x = await im(f);
      if (x && x.name) none.push(`${f}=${x.name}`);
    }
    expect(none.length === 0, "a role the page's class names spell, a machine token and a CMS's default alt name nothing",
      none.join(", "));
    r = await im("bg1.jpg");
    expect(r?.name === "Countryside at dusk", "a background picture's own label names it", r?.name);
    r = await im("logo.svg");
    expect(r?.name === "Las Provincias - Diario de noticias" && !r.description,
      "a control's label never describes a picture", `${r?.name} | ${r?.description}`);
    r = await im("i12.jpg");
    expect(r?.name === "Lluvias en La Marina Alta (Alicante)", "a parenthesis inside the sentence is content", r?.name);
    const pageAsPicture = await top.evaluate(() => window.__reports.some((x) => x.kind === "images-detected"
      && (x.urls || []).some((u) => u.split("#")[0] === location.href.split("#")[0])));
    expect(!pageAsPicture, "an <img src=\"\"> does not report the page itself as a picture", pageAsPicture);
  }

  console.log("player config: a clip named by its player's data-* JSON");
  {
    const o = await open(`${a}/dmhome.html`);
    const top = o.frame("/dmhome.html");
    let r = await o.ask(top, "d1.mp4");
    expect(r.pick?.name === "Police officer shot in city centre as cops hunt for suspects",
      "a data-opts title names its clip, not the page", r.pick?.name);
    r = await o.ask(top, "d2.mp4");
    expect(r.pick?.name === "Storm closes roads in the north", "a title nested under media", r.pick?.name);
    r = await o.ask(top, "d3.mp4");
    expect(r.pick?.name === "Headline of the third card", "a player's own top-level name is no title", r.pick?.name);
    r = await o.ask(top, "d4.mp4");
    expect(r.pick?.name !== "Hero video poster", "a poster's \"Hero video poster\" alt is no title", r.pick?.name);
    const scan = await o.scanned(top, "d1.mp4");
    expect(scan?.name === "Police officer shot in city centre as cops hunt for suspects",
      "the scan's report carries the config title too", scan?.name);
  }

  console.log("card idioms: what names a clip, whatever the site calls it");
  {
    const o = await open(`${a}/cards.html`);
    const top = o.frame("/cards.html");
    const cases = [
      ["k1.mp4", "El temporal deja imagenes en la Marina", "a heading beats a control's \"Play: <title>\" label"],
      ["k2.mp4", "Calles inundadas en Miramar por las lluvias", "a card's plain text names it, whatever its class (lasprovincias' v-a-t)"],
      ["k3.mp4", "Final match ends in a late draw", "a title link before its subtitle; a one-word kicker is no title"],
      ["k4.mp4", "Storm closes roads across the north", "a card's first phrase, before its description"],
      ["k5.mp4", "The chef who reinvented the omelette", "a headline in a span in a link"],
      ["k6.mp4", "Microdata clip name", "a microdata name, not its author's"],
      ["k7.mp4", "Labelled region clip", "the region's aria-labelledby"],
      ["k8.mp4", "Deeply nested clip title", "a clip six wrappers deep still reaches its card"],
      ["k9.mp4", "Modifier test title", "a card's text, not the duration beside it"],
      ["k10.mp4", "Sunset over the bay in Valencia", "an image alt when nothing else names the clip"],
      ["k11.mp4", "Cards page", "a file-name alt is not a title"],
      ["k12.mp4", "View Countryside Duo", "a control label stays when the card has nothing else"],
      ["k13.mp4", "Inner headline wins", "the innermost title element, not the headline box around it"],
      ["k14.mp4", "Harbour lights after the storm", "a card's only text names its clip, whatever its class says"],
    ];
    for (const [clip, want, label] of cases) {
      const r = await o.ask(top, clip);
      expect(r.pick?.name === want, label, r.pick?.name);
    }
  }

  console.log("articles: the clip's heading, not the page's other headings");
  {
    let o = await open(`${a}/article.html`);
    let r = await o.ask(o.frame("/article.html"), "main.mp4");
    expect(r.pick?.name === "Mayor resigns after flood report",
      "a subheading after an article's clip does not title it", r.pick?.name);
    o = await open(`${a}/article-widget.html`);
    r = await o.ask(o.frame("/article-widget.html"), "main2.mp4");
    expect(r.pick?.name === "Mayor resigns after flood report",
      "a heading of a section that does not hold the clip does not title it", r.pick?.name);
    o = await open(`${a}/podcast-icon.html`);
    r = await o.ask(o.frame("/podcast-icon.html"), "ep12.mp3");
    expect(r.pick?.img === "/show.jpg", "an icon in a podcast's card is not its picture", r.pick?.img);
  }
  console.log("device audit 2026-10-20: the shapes the phone recorded");
  {
    const urlsOf = (f) => f.evaluate(() => window.__reports
      .filter((x) => x.kind === "images-detected").flatMap((x) => x.urls));
    let o = await open(`${a}/npr.html`);
    let top = o.frame("/npr.html");
    let r = await o.ask(top, "np2.mp4");
    expect(r.pick?.name === "Card clip number 2 of the carousel",
      "a paused clip in a carousel is named by its card, not by the clip playing (NPR)", r.pick?.name);
    let sc = await o.scanned(top, "np3.mp4");
    expect(sc?.name === "Card clip number 3 of the carousel", "...and so is the scan's report of another card", sc?.name);
    r = await o.ask(top, "top.mp4");
    expect(r.pick?.name === "The clip playing at the top", "the clip that is playing keeps its MediaSession title", r.pick?.name);

    o = await open(`${a}/antena3.html`);
    top = o.frame("/antena3.html");
    r = await o.ask(top, "a3a.mp4");
    expect(r.pick?.name === "Perro robot que ayuda agricultores",
      "a \"Ver ahora\" button never names a clip; its poster's words do (antena3)", r.pick?.name);
    r = await o.ask(top, "a3b.mp4");
    expect(r.pick?.name === "Cocina adaptada personas discapacidad visual", "...each clip its own", r.pick?.name);
    r = await o.ask(top, "a3c.mp4");
    expect(r.pick?.name === "Antena 3 Noticias", "a poster named by a hash names nothing", r.pick?.name);

    o = await open(`${a}/cope.html`);
    top = o.frame("/cope.html");
    r = await o.ask(top, "cv1.mp4");
    expect(r.pick?.name === "Herrera, sobre la ley de nietos: regalando nacionalidades",
      "a config title several clips share is a placeholder; each card's heading names its clip (cope)", r.pick?.name);
    r = await o.ask(top, "cv3.mp4");
    expect(r.pick?.name === "Feijóo responde a Sánchez en el Senado", "...each its own", r.pick?.name);
    r = await o.ask(top, "cart.mp4");
    expect(r.pick?.name === "Ángel Expósito entrevista al comandante Luis Benítez",
      "a config title only one clip carries still names it", r.pick?.name);

    o = await open(`${a}/aspod.html`);
    top = o.frame("/aspod.html");
    r = await o.ask(top, "as1.mp3");
    expect(r.pick?.name === "Reproducir Axel Torres y el sueño del Real Madrid | Minuto 116",
      "a title element the page keeps hidden is not the clip's; the play button's label is (as.com)", r.pick?.name);
    r = await o.ask(top, "as2.mp3");
    expect(r.pick?.name === "Reproducir La nadadora española de 16 años que bate récords", "...each player its own", r.pick?.name);

    o = await open(`${a}/elespanol.html`);
    r = await o.ask(o.frame("/elespanol.html"), "ee1.mp4");
    expect(r.pick?.name === "Del Tarajal a Loma Colmenar: Ceuta no se fía del desalojo",
      "script and icon text in a card do not make it an article body (elespanol)", r.pick?.name);

    o = await open(`${a}/lprov.html`);
    r = await o.ask(o.frame("/lprov.html"), "gandia_loop.mp4");
    expect(r.pick?.name === "Así se formó la tormenta perfecta sobre la Safor",
      "the page's only video, a story's teaser, takes the story's headline (lasprovincias)", r.pick?.name);
    o = await open(`${a}/lprov-article.html`);
    r = await o.ask(o.frame("/lprov-article.html"), "artclip.mp4");
    expect(r.pick?.name === "Así se formó la tormenta perfecta", "an article's own clip keeps the page's title", r.pick?.name);

    o = await open(`${a}/dmshorts.html`);
    top = o.frame("/dmshorts.html");
    r = await o.ask(top, "s1/480x270_MP4_3641.mp4");
    expect(r.pick?.name === "Moment murderer overtakes car before hitting cyclist",
      "a clip whose list entry has no title is named by the story it links to (Daily Mail shorts)", r.pick?.name);
    expect(r.pick?.img === `${a}/img/s1.jpg`, "...and pictured by its entry's poster", r.pick?.img);
    r = await o.ask(top, "s1/640x360_MP4_3641.mp4");
    expect(r.pick?.name === "Moment murderer overtakes car before hitting cyclist", "...its other rendition too", r.pick?.name);
    r = await o.ask(top, "s2/480x270_MP4_7247.mp4");
    expect(r.pick?.name === "Jennifer Lopez casually dating", "an entry's own title names its clip", r.pick?.name);

    o = await open(`${a}/nyt.html`);
    top = o.frame("/nyt.html");
    let urls = await urlsOf(top);
    expect(urls.includes(`${a}/assets/clips/nyt1/master.m3u8`) && urls.includes(`${a}/assets/clips/nyt2/master.m3u8`),
      "a <video> in a web component's shadow root is reported (NYT)", urls.filter((u) => /nyt/.test(u)).join(","));
    expect(!urls.some((u) => /index-f1|_1080p\.mp4/.test(u)),
      "...its manifest alone, not the files listed beside it", urls.filter((u) => /nyt/.test(u)).join(","));
    r = await o.ask(top, "nyt1/master.m3u8");
    expect(r.pick?.name === "Silicon Valley Is Worried", "...named by its card's headline, the kicker left out", r.pick?.name);
    sc = await o.scanned(top, "nyt2/master.m3u8");
    expect(sc?.name === "These Factors Could Limit the Knicks", "...each card its own, in the scan's report too", sc?.name);

    o = await open(`${a}/nbc.html`);
    top = o.frame("/nbc.html");
    urls = await urlsOf(top);
    expect(urls.includes(`${a}/assets/clips/nbc1/1/abs/index.m3u8`) && !urls.includes(`${a}/assets/clips/nbc1_4500.mp4`),
      "a clip in the page's JSON is reported once, by its manifest (NBC)", urls.filter((u) => /nbc|u[12]/.test(u)).join(","));
    r = await o.ask(top, "nbc1/1/abs/index.m3u8");
    expect(r.pick?.name === "Man convicted of animal cruelty for stealing a flamingo"
      && r.pick?.img === `${a}/img/flamingo.jpg`, "...named and pictured by its own entry", `${r.pick?.name} | ${r.pick?.img}`);
    r = await o.ask(top, "nbc1_4500.mp4");
    expect(r.pick?.name === "Man convicted of animal cruelty for stealing a flamingo",
      "a wire capture of the clip's mp4 gets the same name", r.pick?.name);
    sc = await o.scanned(top, "nbc2/1/abs/index.m3u8");
    expect(sc?.name === "Swimmer rescued off the California coast", "...another entry its own, in the scan's report", sc?.name);
    r = await o.ask(top, "u1.mp4");
    expect(urls.includes(`${a}/assets/clips/u1.mp4`) && urls.includes(`${a}/assets/clips/u2.mp4`)
      && r.pick?.name === "NBC News - Breaking Headlines" && r.pick?.img === `${a}/img/u1.jpg`,
      "untitled clips in a titled list are each their own, never named by the list", `${r.pick?.name} | ${r.pick?.img}`);
    r = await o.ask(top, "w1/master.m3u8");
    expect(urls.includes(`${a}/assets/clips/w1/master.m3u8`) && urls.includes(`${a}/assets/clips/w2/master.m3u8`)
      && r.pick?.name === "NBC News - Breaking Headlines",
      "two clips under one titled wrapper are two clips, and the wrapper names neither", r.pick?.name);
    r = await o.ask(top, "f1/cmaf.m3u8");
    expect(urls.includes(`${a}/assets/clips/f1/master.m3u8`) && !urls.includes(`${a}/assets/clips/f1/cmaf.m3u8`)
      && r.pick?.name === "Format twin clip", "an hls and a cmaf of one clip are one clip, named by it", r.pick?.name);

    o = await open(`${a}/podwidget.html`);
    top = o.frame("/podwidget.html");
    urls = await urlsOf(top);
    expect(urls.includes(`${a}/assets/clips/ep/play.mp3`) && !urls.includes(`${a}/assets/clips/ep/download.mp3`),
      "an episode in a widget's JSON is reported once", urls.join(","));
    r = await o.ask(top, "ep/play.mp3");
    expect(r.pick?.name === "GAZC: un taller que se convirtió en multinacional" && r.meta?.audioRole === "content",
      "...named by its JSON title and taken as content", `${r.pick?.name} | ${r.meta?.audioRole}`);
  }
  console.log("device audit 2026-10-20, second run");
  {
    const urlsOf = (f) => f.evaluate(() => window.__reports
      .filter((x) => x.kind === "images-detected").flatMap((x) => x.urls));
    let o = await open(`${a}/lvcarousel.html`);
    let top = o.frame("/lvcarousel.html");
    let r = await o.ask(top, "lv2-n.mp4");
    expect(r.pick?.name === "Sergi Bruguera y el relato del hijo",
      "a lightbox slide is named by the carousel card showing its poster, the position and the carousel's word left out (La Vanguardia)",
      r.pick?.name);
    r = await o.ask(top, "lv3-n.mp4");
    expect(r.pick?.name === "Así suena la tormenta en Gandia", "...each slide by its own card", r.pick?.name);
    r = await o.ask(top, "lv1-o1.mp4");
    expect(r.pick?.name === "Mourinho se sale con la suya", "a card's own preview is named by its label", r.pick?.name);
    r = await o.ask(top, "lvx-n.mp4");
    expect(r.pick?.name === "La Vanguardia",
      "a mute button, a play toggle and a read-the-story link never name a clip", r.pick?.name);
    let sc = await o.scanned(top, "lv1-n.mp4");
    expect(sc?.name === "Mourinho se sale con la suya", "...the scan's report names the slide too", sc?.name);
    r = await o.ask(top, "lvy.mp4");
    expect(r.pick?.name === "La Vanguardia", "an untranslated key (\"player.share_clip\") is no label", r.pick?.name);

    o = await open(`${a}/guardian.html`);
    r = await o.ask(o.frame("/guardian.html"), "Dream_of_birds_loop_480w.mp4#t=0.001");
    expect(r.pick?.name === "Dream of Birds",
      "the page's only video, under a labelled link to its story, is that story's (Guardian)", r.pick?.name);

    o = await open(`${a}/abcpod.html`);
    r = await o.ask(o.frame("/abcpod.html"), "nasa/audio.mp3?dist=web-abc");
    expect(r.pick?.name === "La NASA busca cuevas para vivir en la Luna",
      "the page's only podcast is named by its card's heading, the copy of its <audio> no other clip (abc.es)", r.pick?.name);

    o = await open(`${a}/copeplayer.html`);
    r = await o.ask(o.frame("/copeplayer.html"), "net1.mp3?referrer_url=x&player_type=mobile");
    expect(r.pick?.name === "Mediodía COPE", "a radio player's programme names its stream (COPE)", r.pick?.name);
    expect(r.pick?.img === `${a}/img/mediodia.jpeg`, "...and its card's picture pictures it", r.pick?.img);
    o = await open(`${a}/listen.html`);
    r = await o.ask(o.frame("/listen.html"), "tts.mp3");
    expect(r.pick?.name === "Mayor resigns after flood report",
      "a \"listen to this article\" label is a control's wording; the page's title stays", r.pick?.name);

    o = await open(`${a}/asdist.html`);
    top = o.frame("/asdist.html");
    r = await o.ask(top, "asd/audio.mp3?id=1");
    expect(r.pick?.name === "Axel Torres y el sueño inalcanzable" && r.meta?.audioRole === "content",
      "an <audio> playing the URL with a parameter the player added is its element (as.com &dist=)",
      `${r.pick?.name} | ${r.meta?.audioRole}`);
    r = await o.ask(top, "asd/audio2.mp3?id=2");
    expect(r.meta?.audioRole !== "content",
      "two elements refining one URL differently bind neither", r.meta?.audioRole);

    o = await open(`${a}/spwidget.html`);
    r = await o.ask(o.frame("/spwidget.html"), "sp/download.mp3");
    expect(r.pick?.name === "GAZC: cómo un taller se convirtió en una multinacional" && r.meta?.audioRole === "content",
      "a file the widget links to is named by the link's card, not its button (Spreaker)",
      `${r.pick?.name} | ${r.meta?.audioRole}`);

    o = await open(`${a}/fusion.html`);
    top = o.frame("/fusion.html");
    const urls = await urlsOf(top);
    expect(urls.includes(`${a}/assets/clips/rt1.mp3?source=player`) && urls.includes(`${a}/assets/clips/rt2.mp3?source=player`),
      "episodes in a JavaScript state assignment are reported (Reuters)", urls.join(","));
    r = await o.ask(top, "rt1.mp3?source=player");
    expect(r.pick?.name === "Why the Everything Bubble keeps on inflating" && r.meta?.audioRole === "content",
      "...each named by its entry", `${r.pick?.name} | ${r.meta?.audioRole}`);
    sc = await o.scanned(top, "rt2.mp3?source=player");
    expect(sc?.name === "Macron's twilight, Trump's approval and AI hacking", "...in the scan's report too", sc?.name);

    o = await open(`${a}/pym.html`);
    top = o.frame("/pym.html");
    await o.page.waitForTimeout(400);
    const caps = await top.evaluate(() => window.__reports.filter((x) => x.kind === "frame-captions")
      .flatMap((x) => x.items));
    const capOf = (path) => (caps.find((c) => c.src.endsWith(path)) || {}).title;
    expect(capOf("/graphic.html") === "Heat storms are hitting the cocoa farms harder",
      "an iframe titled \"Embedded content 1\" is captioned by its figure (pym.js)", capOf("/graphic.html"));
    expect(capOf("/graphic2.html") === "Cocoa prices since 2020", "an iframe's real title is still its caption", capOf("/graphic2.html"));
  }
  console.log("device audit 2026-10-20, third run");
  {
    let o = await open(`${a}/apgallery.html`);
    let top = o.frame("/apgallery.html");
    let r = await o.ask(top, "apv0-Z9ob0dUs.mp4");
    expect(r.pick?.name === "Aftermath of the weekend protests over the cost of living turned into rioting and looting across France",
      "a slideshow's caption names the slide it shows, its credit left out (AP)", r.pick?.name);
    r = await o.ask(top, "apv2-Z9ob0dUs.mp4");
    expect(r.pick?.name === "Associated Press News: Breaking News | Latest News Today",
      "...and never a slide it does not show", r.pick?.name);
    await top.evaluate(() => {
      window.__showItem(2);
      const v = document.querySelectorAll(".mixed-gallery-slot video")[1];
      Object.defineProperty(v, "paused", { get: () => false });
      v.dispatchEvent(new Event("playing"));
    });
    await o.page.waitForTimeout(1500);
    let named = await top.evaluate(() => window.__reports.filter((x) => x.kind === "clip-named"));
    let n2 = named.find((x) => x.url.endsWith("/apv2-Z9ob0dUs.mp4"));
    expect(n2 && pickCaptureMeta(n2.meta).name === "Workers clean the Equality at the Republique square, after a quiet night under curfew",
      "a slide reported unnamed is named when it plays, once the slides have settled", named.map((x) => x.url).join(","));

    o = await open(`${a}/copeplayer3.html`);
    r = await o.ask(o.frame("/copeplayer3.html"), "net3.mp3?referrer_url=x&player_type=mobile");
    expect(r.pick?.name === "La Linterna",
      "a radio player's programme names its stream on a page with other clips; a hidden empty <video> is no clip (COPE)", r.pick?.name);
    expect(r.pick?.img === `${a}/img/linterna.jpeg`, "...and its card's picture pictures it", r.pick?.img);

    o = await open(`${a}/higgs.html`);
    top = o.frame("/higgs.html");
    r = await o.ask(top, "hpromo.mp4");
    expect(r.pick?.name === "Sign up and get your extra discount",
      "a heading in capitals throughout holds no kicker; its lines are spaced (higgsfield)", r.pick?.name);
    r = await o.ask(top, "hpromo2.mp4");
    expect(r.pick?.name === "Katana edits your clips", "...a kicker set apart in capitals is still left out", r.pick?.name);

    o = await open(`${a}/aslone.html`);
    r = await o.ask(o.frame("/aslone.html"), "asn.mp3?accessToken=x");
    expect(r.pick?.name === "Reproducir La nadadora española de 16 años que ha batido el récord de Mireia Belmonte y que asombra al mundo",
      "the page's only podcast is named by its play button's long label, not a list of stories beside it (as.com)", r.pick?.name);
    o = await open(`${a}/listenbtn.html`);
    r = await o.ask(o.frame("/listenbtn.html"), "tts2.mp3");
    expect(r.pick?.name === "El Ayuntamiento aprueba el presupuesto",
      "a \"listen\" button's label on an article's only player is what it does; the page's title stays", r.pick?.name);

    o = await open(`${a}/spdl.html`);
    r = await o.ask(o.frame("/spdl.html"), "spq/download.mp3");
    expect(r.pick?.name === "GAZC: cómo un taller se convirtió en una multinacional aeronáutica" && r.meta?.audioRole === "content",
      "a download link with parameters of its own is the file's; its card names it (Spreaker on elespanol)",
      `${r.pick?.name} | ${r.meta?.audioRole}`);

    o = await open(`${a}/nytlabels.html`);
    top = o.frame("/nytlabels.html");
    r = await o.ask(top, "l1/master.m3u8");
    expect(r.pick?.name === "Trump Campaigns for Republicans as Turmoil Mounts in Washington",
      "the word every player's label ends with is the template's (NYT \"<title> video\")", r.pick?.name);
    r = await o.ask(top, "l3/master.m3u8");
    expect(r.pick?.name === "No, Really, Is A.I. a Bubble?", "...the title keeps its own punctuation", r.pick?.name);
    r = await o.ask(top, "l4/master.m3u8");
    expect(r.pick?.name === "The New York Times - Breaking News",
      "a player still labelled with the developer's words (\"Video player\") is not named by them", r.pick?.name);
    await top.evaluate(() => {
      const host = document.querySelector('nyt-betamax[data-clip="l4"]');
      host.shadowRoot.querySelector('[role="group"]').setAttribute("aria-label", "Where Do Autoimmune Diseases Come From? video");
      const v = host.shadowRoot.querySelector("video");
      Object.defineProperty(v, "paused", { get: () => false });
      v.dispatchEvent(new Event("playing"));
    });
    await o.page.waitForTimeout(1500);
    named = await top.evaluate(() => window.__reports.filter((x) => x.kind === "clip-named"));
    n2 = named.find((x) => x.url.endsWith("/l4/master.m3u8"));
    expect(n2 && pickCaptureMeta(n2.meta).name === "Where Do Autoimmune Diseases Come From?",
      "...and is named when it plays, by the label its player gives it then", named.map((x) => x.url).join(","));
    o = await open(`${a}/nytcensus.html`);
    r = await o.ask(o.frame("/nytcensus.html"), "c1.mp4");
    expect(r.pick?.name === "Floods close the coastal road",
      "a group label three players share names none; each card's headline does", r.pick?.name);
    o = await open(`${a}/nytvocab.html`);
    r = await o.ask(o.frame("/nytvocab.html"), "nv1/master.m3u8");
    expect(r.pick?.name === "Where Do Autoimmune Diseases Come From?",
      "a lone label made of the page's test ids and component names is no title", r.pick?.name);
    o = await open(`${a}/thelabels.html`);
    r = await o.ask(o.frame("/thelabels.html"), "t1.mp4");
    expect(r.pick?.name === "The fall of the old bridge",
      "a first word three of four titles share is theirs, not a template's", r.pick?.name);

    o = await open(`${a}/antena3r3.html`);
    top = o.frame("/antena3r3.html");
    const want = ["Rosalia premio nacional musicas actuales", "Premio planeta cumple anos", "Isabel allende premio planeta",
      "Lluvias torrenciales valencia"];
    const got = [];
    for (let i = 0; i < 4; i++) got.push((await o.ask(top, `a3r${i}.mp4`)).pick?.name);
    expect(got.slice(0, 3).join("|") === want.slice(0, 3).join("|"),
      "a word several stories share inside their posters' names stays (antena3 \"premio\")", got.join(" | "));
    expect(got[3] === want[3], "...the words a template begins three posters' names with go", got[3]);
  }
  console.log("distinct names: a label on three or more different things names none, in any language");
  {
    let o = await open(`${a}/distinct-nl.html`);
    let top = o.frame("/distinct-nl.html");
    let r = await o.ask(top, "nl2.mp4");
    expect(r.pick?.name === "Storm legt treinverkeer plat",
      "a mute button on every tile names no tile; each takes its own picture's alt (Dutch)", r.pick?.name);
    let sc = await o.scanned(top, "nl3.mp4");
    expect(sc?.name === "Ajax wint de klassieker", "...in the scan's report too", sc?.name);

    o = await open(`${a}/distinct-pl.html`);
    top = o.frame("/distinct-pl.html");
    r = await o.ask(top, "pl2.mp4");
    expect(r.pick?.name === "Powódź na południu kraju",
      "a \"see now\" link on every story card names no story; the headline does (Polish)", r.pick?.name);

    o = await open(`${a}/distinct-fi.html`);
    top = o.frame("/distinct-fi.html");
    r = await o.ask(top, "fi3.mp4");
    expect(r.pick?.name === "HJK voitti mestaruuden",
      "three clips' own label \"Esimerkki\" names none of them; the card's heading does (Finnish)", r.pick?.name);

    o = await open(`${a}/cuts.html`);
    top = o.frame("/cuts.html");
    const v = await o.ask(top, "cut-v.mp4");
    const h = await o.ask(top, "cut-h.mp4");
    expect(v.pick?.name === "Flood waters reach the old town" && h.pick?.name === "Flood waters reach the old town",
      "two cuts of one story keep the title they share", `${v.pick?.name} | ${h.pick?.name}`);

    o = await open(`${a}/onestory.html`);
    top = o.frame("/onestory.html");
    r = await o.ask(top, "s1.mp4");
    expect(r.pick?.name === "Cocoa farms still reeling from the heat",
      "one story's title on its button, its picture and its link is not three things (Reuters' cards)", r.pick?.name);

    o = await open(`${a}/clones.html`);
    top = o.frame("/clones.html");
    r = await o.ask(top, "cl1.mp4");
    expect(r.pick?.name === "Harbour fireworks at midnight",
      "a carousel's clones of one slide are one clip: its title stays", r.pick?.name);

    o = await open(`${a}/gallery-fi.html`);
    top = o.frame("/gallery-fi.html");
    await o.page.waitForTimeout(300);
    let im = await o.scannedImage(top, "g2.jpg");
    expect(im?.name === "Tulvat Valenciassa", "a gallery's shared alt still names a photo nothing else names", im?.name);
    im = await o.scannedImage(top, "h2.jpg");
    expect(im?.name === "Vesi nousi kaduille", "...and gives way to the photo's own caption", im?.name);

    o = await open(`${a}/frames-fi.html`);
    top = o.frame("/frames-fi.html");
    await o.page.waitForTimeout(400);
    const caps = await top.evaluate(() => window.__reports.filter((x) => x.kind === "frame-captions")
      .flatMap((x) => x.items));
    const capOf = (path) => (caps.find((c) => c.src.endsWith(path)) || {}).title;
    expect(capOf("graphic2.html?n=1") === "Sähkön hinta nousi syksyllä" && capOf("graphic2.html?n=2") === "Vaalien tulos kunnittain",
      "iframes titled alike but for a number are captioned by their figures (Finnish)",
      `${capOf("graphic2.html?n=1")} | ${capOf("graphic2.html?n=2")}`);
    expect(capOf("graphic2.html?n=3") === "Kartta: myrskyn reitti", "...an iframe's own title stays", capOf("graphic2.html?n=3"));
  }
  console.log("no word list: what names a clip, read from how the page shows it (Polish, Turkish, hashed classes)");
  {
    let o = await open(`${a}/nolist-pl.html`);
    let top = o.frame("/nolist-pl.html");
    const cases = [
      ["p1.mp4", "Powódź zalała centrum Wrocławia w nocy", "the text shown larger and bolder is the title, not the byline before it"],
      ["p2.mp4", "Rząd zapowiada nowe przepisy dotyczące mieszkań", "a kicker the stylesheet sets in capitals is no title"],
      ["p3.mp4", "Pociągi wracają na trasę do Gdańska", "a line three cards repeat is no title"],
      ["p4.mp4", "Strażacy gaszą pożar magazynu", "a two-word link text its URL does not spell is no title"],
      ["p5.mp4", "Krótki reportaż", "a duration with its unit is no title"],
      ["p6.mp4", "Nowy most otwarty dla ruchu", "a card's own <header> holds its title"],
      ["p7.mp4", "Wiadomości - Portal", "a credit in small type names nothing"],
      ["p8.mp4", "Wiadomości - Portal", "a carousel position names nothing, whatever word joins its numbers"],
      ["p9.mp4", "Stocznia wraca do produkcji statków", "a credit caption at the page's own size names nothing (names joined by a slash)"],
      ["p10.mp4", "Rolnicy protestują przed ministerstwem", "a one-word caption is an agency's name, not a description"],
      ["p11.mp4", "Most w Gdańsku otwarty po trzech latach", "text the page hides from screen readers names nothing"],
      ["p12.mp4", "Stadion otwarty po remoncie", "a list item's own label names its clip before the text inside it"],
      ["p13.mp4", "Budżet się sypie", "a headline names its clip though its story's URL spells something else"],
      ["p15.mp4", "Pierwszy śnieg w Tatrach", "a copyright caption names nothing"],
    ];
    for (const [clip, want, label] of cases) {
      const r = await o.ask(top, clip);
      expect(r.pick?.name === want, label, r.pick?.name);
    }
    await o.page.waitForTimeout(400);
    const handle = await o.scannedImage(top, "p14.jpg");
    expect(!handle || !handle.name, "a photographer's handle names no picture", handle?.name);
    o = await open(`${a}/nolist-tr.html`);
    top = o.frame("/nolist-tr.html");
    let r = await o.ask(top, "t1.mp4");
    expect(r.pick?.name === "Haberler - Portal", "a small button's label is what it does, on a page of two clips", r.pick?.name);
    r = await o.ask(top, "t2.mp4");
    expect(r.pick?.name === "Kapadokya balonları", "a button laid over the card names the card", r.pick?.name);
    await o.page.waitForTimeout(400);
    let im = await o.scannedImage(top, "t3.jpg");
    expect(im?.name === "Meclis yeni yasayı onayladı", "an agency dateline is dropped, whatever the month's language", im?.name);
    im = await o.scannedImage(top, "t4.jpg");
    expect(!im || !im.name, "a role the page's own class names spell names nothing, in the developer's language", im?.name);
    im = await o.scannedImage(top, "t5.jpg");
    expect(im?.name === "İtfaiye ekipleri yangına müdahale etti", "a credit after a colon is dropped, in any language", im?.name);
    const caps = await top.evaluate(() => window.__reports.filter((x) => x.kind === "frame-captions").flatMap((x) => x.items));
    const cap = (caps.find((c) => c.src.endsWith("graphic2.html?n=tr")) || {}).title;
    expect(cap === "Seçim sonuçları il il harita", "an embed is captioned by its figure before its own title", cap);
    im = await o.scannedImage(top, "t6.svg");
    expect(!im || !im.name, "a logo is not named by the footer text beside it", im?.name);
    im = await o.scannedImage(top, "t7.svg");
    expect(im?.name === "Bültenler", "an icon's one-word alt names it", im?.name);
    im = await o.scannedImage(top, "t8.png");
    expect(!im || !im.name, "a logo shown 24 px high is not named by the line before it, however wide", im?.name);
    im = await o.scannedImage(top, "t9.svg");
    expect(im?.name === "Zor sudoku", "an SVG of a declared picture size is named by its card", im?.name);
  }
} finally {
  await browser.close();
  server.close();
}

console.log(`\n${checks - failures}/${checks} checks passed`);
process.exit(failures ? 1 : 0);
