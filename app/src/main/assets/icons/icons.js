/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

 /*
  * This web extension looks for known icon tags, collects URLs and available
  * meta data (e.g. sizes) and passes that to the app code.
  */

/**
 * Takes a DOMTokenList and returns a String array.
 */
function sizesToList(sizes) {
    if (sizes == null) {
        return [];
    }

    if (!(sizes instanceof DOMTokenList)) {
        return [];
    }

    return Array.from(sizes);
}

function collect_link_icons(icons, rel) {
    document.querySelectorAll('link[rel="' + rel + '"]').forEach(
        function(currentValue, currentIndex, listObj) {
            icons.push({
                'type': rel,
                'href': currentValue.href,
                'sizes': sizesToList(currentValue.sizes),
                'mimeType': currentValue.type
            });
    });
}

function collect_meta_property_icons(icons, property) {
    document.querySelectorAll('meta[property="' + property + '"]').forEach(
        function(currentValue, currentIndex, listObj) {
            icons.push({
                'type': property,
                'href': currentValue.content
            });
        }
    );
}

function collect_meta_name_icons(icons, name) {
    document.querySelectorAll('meta[name="' + name + '"]').forEach(
        function(currentValue, currentIndex, listObj) {
            icons.push({
                'type': name,
                'href': currentValue.content
            });
        }
    );
}

function collectIcons() {
    let icons = [];

    collect_link_icons(icons, 'icon');
    collect_link_icons(icons, 'shortcut icon');
    collect_link_icons(icons, 'fluid-icon');
    collect_link_icons(icons, 'apple-touch-icon');
    collect_link_icons(icons, 'image_src');
    collect_link_icons(icons, 'apple-touch-icon image_src');
    collect_link_icons(icons, 'apple-touch-icon-precomposed');

    collect_meta_property_icons(icons, 'og:image');
    collect_meta_property_icons(icons, 'og:image:url');
    collect_meta_property_icons(icons, 'og:image:secure_url');

    collect_meta_name_icons(icons, 'twitter:image');
    collect_meta_name_icons(icons, 'msapplication-TileImage');

    return icons;
}

function sendIcons() {
    let message = {url: document.location.href, icons: collectIcons(), title: document.title};
    // Fire-and-forget: the app sends no reply, and a failure has nothing to
    // retry. No console output (release builds must be silent).
    browser.runtime.sendNativeMessage("icons", message).catch(() => {});
}

sendIcons();

// A page restored from the back/forward cache is the SAME document, so this
// script does not run again, but the app cleared the tab's favicon when the
// tab left this site (GeckoState.updateVisit on the host change). Without a
// resend the tab stayed iconless after Back. persisted is false on a normal
// load, where the call above already sent.
window.addEventListener('pageshow', function(event) {
    if (event.persisted) {
        sendIcons();
    }
});
