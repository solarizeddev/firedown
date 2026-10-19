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
    `<meta property="og:title" content="Episode page"><meta property="og:image" content="/show.jpg">`,
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
  //    nothing else.
  [`a.test/cnn.html`]: html("CNN",
    `<meta property="og:title" content="CNN front page">`,
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
    + `<div class="box"><video src="/assets/clips/k14.mp4"></video><div class="info has-title">Modifier box text, not a title</div></div>`),

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
    + `<div class="hero"><img src="/assets/clips/i9.jpg" alt="Hero video poster"></div>`
    + `<p><img src="/assets/clips/i10.jpg" alt="rc2usnaifcu20260929"></p>`
    + `<p><img src="/assets/clips/i11.jpg" alt="Imagen"></p>`
    // a background picture with its own label
    + `<div role="img" aria-label="Countryside at dusk" style="background-image:url(/assets/clips/bg1.jpg);width:200px;height:100px"></div>`
    // a logo beside the menu button: the button's wording describes nothing
    + `<header><a href="/"><img src="/assets/clips/logo.svg" alt="Las Provincias - Diario de noticias"></a>`
    + `<button aria-label="Desplegar menú principal"></button></header>`
    // an alt that is content with a parenthesis inside the sentence
    + `<p><img src="/assets/clips/i12.jpg" alt="Lluvias en La Marina Alta (Alicante)"></p>`),

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
    expect(r.pick?.name === "Episode page" && r.pick?.img === "/show.jpg",
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
    expect(none.length === 0, "a picture-kind alt, a machine token and a bare \"Imagen\" name nothing",
      none.join(", "));
    r = await im("bg1.jpg");
    expect(r?.name === "Countryside at dusk", "a background picture's own label names it", r?.name);
    r = await im("logo.svg");
    expect(r?.name === "Las Provincias - Diario de noticias" && !r.description,
      "a control's label never describes a picture", `${r?.name} | ${r?.description}`);
    r = await im("i12.jpg");
    expect(r?.name === "Lluvias en La Marina Alta (Alicante)", "a parenthesis inside the sentence is content", r?.name);
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
      ["k2.mp4", "Calles inundadas en Miramar por las lluvias", "a data-title names a card whose title class says nothing"],
      ["k3.mp4", "Final match ends in a late draw", "a BEM __title, not its kicker or subtitle"],
      ["k4.mp4", "Storm closes roads across the north", "a data-testid headline"],
      ["k5.mp4", "The chef who reinvented the omelette", "a camelCase Hed class"],
      ["k6.mp4", "Microdata clip name", "a microdata name, not its author's"],
      ["k7.mp4", "Labelled region clip", "the region's aria-labelledby"],
      ["k8.mp4", "Deeply nested clip title", "a clip six wrappers deep still reaches its card"],
      ["k9.mp4", "Modifier test title", "a title element, not the card--with-title modifier"],
      ["k10.mp4", "Sunset over the bay in Valencia", "an image alt when nothing else names the clip"],
      ["k11.mp4", "Cards page", "a file-name alt is not a title"],
      ["k12.mp4", "View Countryside Duo", "a control label stays when the card has nothing else"],
      ["k13.mp4", "Inner headline wins", "the innermost title element, not the headline box around it"],
      ["k14.mp4", "Cards page", "a has-title modifier is not a title element"],
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
} finally {
  await browser.close();
  server.close();
}

console.log(`\n${checks - failures}/${checks} checks passed`);
process.exit(failures ? 1 : 0);
