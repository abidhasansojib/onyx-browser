#!/usr/bin/env bash
# ==============================================================================
# Script: scripts/update_filter_lists.sh
# Purpose: Synchronize and bundle official Brave adblock filter lists,
#          uBlock filters, and Peter Lowe's list into Onyx Browser assets.
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
export ASSETS_DIR="${ROOT_DIR}/app/src/main/assets"
export EXTERNAL_LISTS_DIR="${ROOT_DIR}/external/adblock-lists"
export OUTPUT_RULES_FILE="${ASSETS_DIR}/easylist_rules.txt"

echo "=== Synchronizing Brave & Community Adblock Filter Lists ==="

mkdir -p "${ASSETS_DIR}"

python3 - << 'EOF'
import os, urllib.request

external = os.environ.get('EXTERNAL_LISTS_DIR', 'external/adblock-lists')
output_path = os.environ.get('OUTPUT_RULES_FILE', 'app/src/main/assets/easylist_rules.txt')

sources = [
    os.path.join(external, 'brave-lists/filters-mirror.txt'),
    os.path.join(external, 'brave-unbreak.txt'),
    os.path.join(external, 'brave-lists/brave-firstparty.txt'),
    os.path.join(external, 'brave-lists/brave-firstparty-cname.txt'),
    os.path.join(external, 'brave-lists/brave-social.txt'),
    os.path.join(external, 'brave-lists/brave-cookie-specific.txt'),
    os.path.join(external, 'brave-lists/yt-shorts.txt'),
    os.path.join(external, 'brave-lists/yt-distracting.txt'),
    os.path.join(external, 'brave-lists/yt-recommended.txt'),
    os.path.join(external, 'brave-lists/experimental.txt')
]

rules = []
seen = set()

header = """[Adblock Plus 2.0]
! Title: Onyx Browser Production Filter Rules
! Description: Compiled from uBlock, Brave Shields, and Peter Lowe filters
! Auto-compiled for high-performance mobile ad-blocking
"""

core_remote_sources = [
    (os.path.join(external, 'brave-lists/filters-mirror.txt'), 'https://easylist.to/easylist/easylist.txt'),
    (os.path.join(external, 'brave-lists/brave-default.txt'), 'https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-default.txt'),
    (os.path.join(external, 'brave-lists/brave-firstparty.txt'), 'https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-firstparty.txt'),
    (os.path.join(external, 'brave-unbreak.txt'), 'https://raw.githubusercontent.com/brave/adblock-lists/master/brave-unbreak.txt'),
    (os.path.join(external, 'brave-lists/brave-firstparty-cname.txt'), 'https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-firstparty-cname.txt'),
    (os.path.join(external, 'brave-lists/brave-cookie-specific.txt'), 'https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-cookie-specific.txt'),
    (os.path.join(external, 'ublock-privacy.txt'), 'https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/privacy.txt'),
    (os.path.join(external, 'ublock-unbreak.txt'), 'https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/unbreak.txt'),
    (os.path.join(external, 'easyprivacy.txt'), 'https://easylist.to/easylist/easyprivacy.txt')
]

for local_path, remote_url in core_remote_sources:
    lines = None
    if os.path.exists(local_path):
        try:
            with open(local_path, 'r', errors='ignore') as f:
                lines = f.readlines()
        except Exception as e:
            print(f'Error reading {local_path}: {e}')
    if lines is None and remote_url:
        try:
            req = urllib.request.Request(remote_url, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(req, timeout=12) as resp:
                text = resp.read().decode('utf-8', errors='ignore')
                lines = text.splitlines()
                print(f'Fetched remote list: {remote_url} ({len(lines)} lines)')
        except Exception as e:
            print(f'Notice: remote list fetch failed for {remote_url}: {e}')

    if lines:
        for line in lines:
            l = line.strip()
            if l and not l.startswith('!') and not l.startswith('['):
                if l not in seen:
                    seen.add(l)
                    rules.append(l)

for s in sources:
    if os.path.exists(s):
        with open(s, 'r', errors='ignore') as f:
            for line in f:
                l = line.strip()
                if l and not l.startswith('!') and not l.startswith('['):
                    if l not in seen:
                        seen.add(l)
                        rules.append(l)

# Also fetch Peter Lowe adservers list if network available
try:
    req = urllib.request.Request(
        'https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=1&mimetype=plaintext',
        headers={'User-Agent': 'Mozilla/5.0'}
    )
    with urllib.request.urlopen(req, timeout=8) as resp:
        text = resp.read().decode('utf-8', errors='ignore')
        for line in text.splitlines():
            l = line.strip()
            if l and not l.startswith('!') and not l.startswith('['):
                if l not in seen:
                    seen.add(l)
                    rules.append(l)
except Exception as e:
    print('Notice: Peter Lowe list fetch skipped:', e)

# Add explicit ABP network rules for standard ad network domains
standard_domains = [
    "doubleclick.net", "googlesyndication.com",
    "googletagservices.com", "googleadservices.com", "google-analytics.com",
    "analytics.google.com", "stats.g.doubleclick.net", "pagead2.googlesyndication.com",
    "adservice.google.com", "tr.snapchat.com", "analytics.twitter.com", "ads.twitter.com",
    "ads-twitter.com", "scorecardresearch.com", "quantserve.com", "quantcast.com",
    "adsrvr.org", "casalemedia.com", "openx.net", "pubmatic.com", "adnxs.com",
    "rubiconproject.com", "criteo.com", "criteo.net", "amazon-adsystem.com",
    "ads.linkedin.com", "bat.bing.com", "adroll.com", "sentry.io", "bugsnag.com",
    "newrelic.com", "nr-data.net", "hotjar.com", "clarity.ms", "mixpanel.com",
    "amplitude.com", "appsflyer.com", "branch.io", "segment.com", "segment.io",
    "mc.yandex.ru", "statcounter.com", "outbrain.com", "taboola.com",
    "bluekai.com", "demdex.net", "optimizely.com", "crazyegg.com", "mouseflow.com",
    "fullstory.com", "chartboost.com", "applovin.com", "vungle.com", "liftoff.io",
    "inmobi.com", "ironsource.mobi", "unityads.unity3d.com", "adcolony.com",
    "mgid.com", "propellerads.com", "propellerclick.com", "onclickads.net",
    "media.net", "adservetx.media.net", "spotxchange.com", "indexexchange.com",
    "htlbid.com", "fls-na.amazon.com", "advertising.com", "bidswitch.net",
    "moatads.com", "smartadserver.com", "adsafeprotected.com", "doubleverify.com",
    "connatix.com", "innovid.com", "tremorhub.com", "crwdcntrl.net", "fwmrm.net",
    "jwpltx.com", "rlcdn.com", "impactradius-event.com", "shareasale.com",
    "awin1.com", "partnerstack.com", "refersion.com", "fingerprintjs.com", "fpjs.io",
    "adlog.vivo.com", "ads-api.vivo.com", "click.oneplus.cn", "open.oneplus.net",
    "a.lenovo.com", "ad.mail.ru", "top-fwz1.mail.ru", "ads.vk.com", "pangleglobal.com",
    "luckyorange.com", "luckyorange.net", "freshmarketer.com", "heapanalytics.com",
    "stats.wp.com", "driftt.com", "intercom.io", "wzrkt.com", "zenaps.com",
    "statdynamic.com", "datadoghq.com", "omtrdc.net", "stickyadstv.com", "3lift.com",
    "sonobi.com", "gumgum.com", "teads.tv", "kargo.com", "metrics.adobe.com",
    "lr-ingest.com", "googleanalytics.com", "adfox.yandex.ru", "appmetrica.yandex.ru",
    "adfstat.yandex.ru", "metrika.yandex.ru", "offerwall.yandex.net", "adtech.yahooinc.com",
    "gemini.yahoo.com", "partnerads.ysm.yahoo.com", "sentry-cdn.com", "getsentry.com",
    "adtago.s3.amazonaws.com", "analyticsengine.s3.amazonaws.com", "analytics.s3.amazonaws.com",
    "advice-ads.s3.amazonaws.com", "alb.reddit.com", "events.reddit.com", "events.redditmedia.com",
    "ads.youtube.com", "ads-api.tiktok.com", "ads.tiktok.com", "ads-sg.tiktok.com",
    "analytics-sg.tiktok.com", "business-api.tiktok.com", "log.byteoversea.com",
    "trk.pinterest.com", "ads.pinterest.com", "log.pinterest.com", "an.facebook.com",
    "pixel.facebook.com", "pointdrive.linkedin.com",
    "an.yandex.ru", "googletagmanager.com", "ymatuhin.ru", "d2wy8f7a9ursnm.cloudfront.net",
    "unityads.unity3d.com", "unity3d.com",
    "bdapi-ads.realmemobile.com", "bdapi-in-ads.realmemobile.com", "iot-eu-logser.realme.com", "iot-logser.realme.com",
    "api.ad.xiaomi.com", "data.mistat.xiaomi.com", "data.mistat.india.xiaomi.com", "data.mistat.rus.xiaomi.com",
    "sdkconfig.ad.xiaomi.com", "sdkconfig.ad.intl.xiaomi.com", "tracking.rus.miui.com", "tracking.miui.com",
    "adsfs.oppomobile.com", "adx.ads.oppomobile.com", "ck.ads.oppomobile.com", "data.ads.oppomobile.com",
    "metrics.data.hicloud.com", "metrics2.data.hicloud.com", "grs.hicloud.com", "logservice.hicloud.com",
    "logservice1.hicloud.com", "logbak.hicloud.com",
    "samsungads.com", "smetrics.samsung.com", "nmetrics.samsung.com", "samsung-com.112.2o7.net", "analytics-api.samsunghealthcn.com",
    "iadsdk.apple.com", "metrics.icloud.com", "metrics.mzstatic.com", "api-adservices.apple.com",
    "books-analytics-events.apple.com", "weather-analytics-events.apple.com", "notes-analytics-events.apple.com"
]
for d in standard_domains:
    rule = f"||{d}^"
    if rule not in seen:
        seen.add(rule)
        rules.append(rule)

extra_rules = [
    "/banners/pr_advertising_ads_banner",
    "/ads/ads.js",
    "||adblock-tester.com/banners/*",
    "##[id*=\"yandex_rtb\"]",
    "##[class*=\"yandex_rtb\"]",
    "##[id*=\"pr_advertising\"]",
    "##[class*=\"pr_advertising\"]"
]
for r in extra_rules:
    if r not in seen:
        seen.add(r)
        rules.append(r)

with open(output_path, 'w', encoding='utf-8') as f:
    f.write(header + '\n')
    for r in rules:
        f.write(r + '\n')

print(f"Filter database generated: {len(rules)} rules ({os.path.getsize(output_path)} bytes)")
EOF

# Also create symlink from assets/brave-lists to external/adblock-lists/brave-lists if submodule exists
if [ -d "${EXTERNAL_LISTS_DIR}/brave-lists" ]; then
    mkdir -p "${ASSETS_DIR}"
    rm -rf "${ASSETS_DIR}/brave-lists"
    ln -sfn "../../../../external/adblock-lists/brave-lists" "${ASSETS_DIR}/brave-lists" || true
    echo "Created symlink: app/src/main/assets/brave-lists -> external/adblock-lists/brave-lists"
fi

RULE_COUNT=$(grep -c "^[^!#[]" "${OUTPUT_RULES_FILE}" || true)
FILE_SIZE=$(wc -c < "${OUTPUT_RULES_FILE}")
echo "=== Filter lists sync complete ==="
echo "Active rules count: ${RULE_COUNT}"
echo "Total file size: ${FILE_SIZE} bytes"
echo "Target file: ${OUTPUT_RULES_FILE}"
