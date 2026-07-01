/*******************************************************************************

    uBlock Origin - a comprehensive, efficient content blocker
    Copyright (C) 2014-present Raymond Hill

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see {http://www.gnu.org/licenses/}.

    Home: https://github.com/gorhill/uBlock

    -----------------------------------------------------------------------

    Front-end for Firedown's restored "page blocked" interstitial. See the
    header of document-blocked.html for why this lives in the UI-stripped
    uBlock build. It is deliberately dependency-light: it only relies on
    `vAPI.messaging.send` (from vapi-client.js) to reach the background's
    still-present listeners. Static UI text is localized via browser.i18n from
    the extension's _locales (uBlock's own translated docblocked* strings plus a
    Firedown-added docblockedSubtitle key); the HTML keeps English fallbacks.

*******************************************************************************/

'use strict';

/******************************************************************************/

// The blocking engine packs the context as a JSON blob in the query string:
//   document-blocked.html?details=<encodeURIComponent(JSON.stringify(query))>
// where query = { url, dn (domain), fs (filter text), hn (hostname),
//                 to (redirect target, '' when none), reason? }.
// See assets/ublock/js/traffic.js `onBeforeRootFrameRequest`.

const details = (( ) => {
    const out = { url: '', dn: '', fs: '', hn: '', to: '', reason: '' };
    try {
        const raw = new URLSearchParams(window.location.search).get('details');
        if ( raw === null ) { return out; }
        Object.assign(out, JSON.parse(raw));
    } catch {
    }
    return out;
})();

/******************************************************************************/

// Localize the static UI. Every element with a data-i18n attribute keeps an
// English fallback in the HTML; browser.i18n.getMessage resolves the key to the
// browser-locale string from _locales/<lang>/messages.json (uBlock's own
// translations, plus the one Firedown-added docblockedSubtitle key). Missing
// locales/keys fall back to the default (en), and the English text already in
// the DOM covers the case where the i18n API is somehow unavailable.

const i18n = key => {
    try {
        return (typeof browser !== 'undefined' && browser.i18n)
            ? browser.i18n.getMessage(key) : '';
    } catch {
        return '';
    }
};

(function applyI18n() {
    const els = document.querySelectorAll('[data-i18n]');
    for ( let i = 0; i < els.length; i++ ) {
        const msg = i18n(els[i].getAttribute('data-i18n'));
        if ( msg ) { els[i].textContent = msg; }
    }
    const title = i18n('docblockedTitle');
    if ( title ) { document.title = title; }
})();

/******************************************************************************/

// Populate the page. textContent only — `details` is attacker-influenced
// (it carries the blocked URL and the matching filter), so it must never be
// written as HTML.

const setText = (id, text) => {
    const el = document.getElementById(id);
    if ( el !== null ) { el.textContent = text; }
};

setText('theURL', details.url || '');
setText('theFilter', details.fs || '');

if ( details.reason ) {
    setText('theReason', details.reason);
    const row = document.getElementById('foundInRow');
    if ( row !== null ) { row.style.display = ''; }
}

// A `$document` filter can additionally redirect; if so, surface where
// Proceed would actually take the user (mirrors uBO's redirect prompt).
if ( details.to ) {
    const warn = document.getElementById('redirectWarn');
    if ( warn !== null ) {
        const tmpl = i18n('docblockedRedirectPrompt') ||
            'The blocked page wants to redirect to another site. ' +
            'If you choose to proceed, you will navigate directly to: {{url}}';
        warn.textContent = tmpl.replace('{{url}}', details.to);
        warn.style.display = '';
    }
}

/******************************************************************************/

const proceedToURL = ( ) => {
    // Proceed to the page the user originally asked for (or the redirect
    // target when the filter declared one).
    const target = details.to || details.url;
    if ( typeof target === 'string' && target !== '' ) {
        window.location.replace(target);
    }
};

// "Don't warn me again about this site" → permanently disable strict blocking
// for the domain (deep = also its subdomains). Handled by the `popupPanel`
// channel's `toggleHostnameSwitch`.
const proceedPermanent = ( ) => {
    return vAPI.messaging.send('popupPanel', {
        what: 'toggleHostnameSwitch',
        name: 'no-strict-blocking',
        hostname: details.dn || details.hn,
        deep: true,
        state: true,
        persist: true,
    });
};

// Plain Proceed → temporary, session-scoped bypass for this hostname.
// Handled by the `documentBlocked` channel's `temporarilyWhitelistDocument`.
const proceedTemporary = ( ) => {
    return vAPI.messaging.send('documentBlocked', {
        what: 'temporarilyWhitelistDocument',
        hostname: details.hn,
    });
};

/******************************************************************************/

document.getElementById('proceed').addEventListener('click', ev => {
    ev.target.disabled = true;
    const dontWarn = document.getElementById('dontWarn');
    const permanent = dontWarn !== null && dontWarn.checked;
    const pending = permanent ? proceedPermanent() : proceedTemporary();
    // The bypass must register on the background side before we re-navigate,
    // otherwise the fresh request is strict-blocked again and we bounce back
    // to this page. `send` resolves once the background has handled it.
    Promise.resolve(pending).then(proceedToURL, proceedToURL);
});

document.getElementById('back').addEventListener('click', ( ) => {
    if ( window.history.length > 1 ) {
        window.history.back();
        return;
    }
    // No history to go back to (e.g. the block hit on a freshly-opened tab):
    // close the tab instead of stranding the user on the interstitial.
    vAPI.messaging.send('documentBlocked', { what: 'closeThisTab' });
});

/******************************************************************************/
