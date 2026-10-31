#!/usr/bin/env python3
"""Build a portable static site. Python standard library only; no runtime framework."""
from pathlib import Path
import argparse
import html
import json
import re
import shutil
from urllib.parse import urlparse

BASE = Path(__file__).resolve().parent
PAGES = [
    ('index.html', 'MaxDevLab — Simple Android Apps for Everyday Life', 'Thoughtful Android apps for photo organization, security scans, and phone cleanup. Explore Photo Cleaner and Security Antivirus Max Cleaner.', 'home'),
    ('apps/photo-cleaner.html', 'Photo Cleaner — Sort Your Photos Month by Month | MaxDevLab', 'Swipe up to keep, down to remove. Tidy your Android photo gallery one month at a time, with on-device sorting and a final review before deletion.', 'product'),
    ('apps/security-antivirus.html', 'Security Antivirus Max Cleaner | MaxDevLab', 'Explore Security Antivirus Max Cleaner for Android: security scans, unwanted-file cleanup, and memory status. Find the official Google Play link and support.', 'product'),
    ('privacy/index.html', 'Privacy Center | MaxDevLab', 'Find privacy policies for Photo Cleaner, Security Antivirus Max Cleaner, and the MaxDevLab website. Learn about your data and choices.', 'page'),
    ('privacy/photo-cleaner.html', 'Photo Cleaner Privacy Policy | MaxDevLab', 'How Photo Cleaner handles photo access, on-device sorting, local records, advertising, and your privacy choices.', 'legal'),
    ('privacy/security-antivirus.html', 'Security Antivirus Max Cleaner Privacy Policy | MaxDevLab', 'The published privacy policy for Security Antivirus Max Cleaner, with support and third-party service information.', 'legal'),
    ('privacy/website.html', 'Website Privacy Policy | MaxDevLab', 'How the MaxDevLab website handles browsing, support emails, external links, and privacy requests.', 'legal'),
    ('terms/index.html', 'Terms of Use | MaxDevLab', 'Terms for using the MaxDevLab website and Photo Cleaner, including photo deletion, privacy, availability, and support.', 'legal'),
    ('terms/security-antivirus.html', 'Security Antivirus Max Cleaner App Agreement | MaxDevLab', 'The published end user license agreement for Security Antivirus Max Cleaner.', 'legal'),
    ('support/index.html', 'Support & FAQ | MaxDevLab', 'Get help with Photo Cleaner or Security Antivirus Max Cleaner. Read FAQs, prepare an email, or make a privacy and data request.', 'page'),
    ('404.html', 'Page Not Found | MaxDevLab', 'Find your way back to MaxDevLab apps and support.', 'page'),
]

def slug(text):
    plain = html.unescape(re.sub('<[^>]+>', '', text))
    return re.sub(r'[^a-z0-9]+', '-', plain.lower()).strip('-')

def render(text, values):
    for key, value in values.items():
        text = text.replace('{{' + key + '}}', value)
    return re.sub(r'\{\{icon:([a-z-]+)\}\}', lambda m: '<svg class="icon" aria-hidden="true"><use href="' + values['root'] + 'assets/icons.svg#' + m.group(1) + '"></use></svg>', text)

def build(domain=None):
    config = json.loads((BASE / 'site.json').read_text())
    domain = (domain if domain is not None else config['domain']).rstrip('/')
    if domain:
        url = urlparse(domain)
        if url.scheme != 'https' or not url.netloc or url.path:
            raise SystemExit('Use an HTTPS origin, for example https://maxdevlab.com, without a path.')
    out = BASE / 'dist'
    out.mkdir(exist_ok=True)
    shutil.copytree(BASE / 'assets', out / 'assets', dirs_exist_ok=True)
    template = (BASE / 'templates/base.html').read_text()
    for route, title, description, layout in PAGES:
        root = '../' * (len(Path(route).parts) - 1) or './'
        if route == '404.html': root = '/'
        values = {key: html.escape(str(config[key]), quote=True) for key in ('name', 'email', 'updated', 'photoStoreUrl', 'securityStoreUrl')}
        values.update(root=root, title=html.escape(title), description=html.escape(description), bodyClass='legal-page' if layout == 'legal' else layout + '-page', canonical='')
        values['photoStatus'] = 'Google Play'
        values['photoAvailabilityNote'] = 'Contains ads. Availability is shown on Google Play.'
        values['photoAction'] = ('<a class="button primary" href="' + values['photoStoreUrl'] + '" target="_blank" rel="noopener noreferrer">View on Google Play {{icon:external}}</a>')
        if domain:
            url = domain + '/' + ('' if route == 'index.html' else route)
            values['canonical'] = '<link rel="canonical" href="' + html.escape(url) + '"><meta property="og:url" content="' + html.escape(url) + '"><meta property="og:image" content="' + domain + '/assets/photo-icon.webp">'
        content = (BASE / 'pages' / route).read_text()
        if layout == 'legal':
            headings = []
            def anchor(match):
                text = match.group(1)
                ident = slug(text)
                headings.append((ident, re.sub('<[^>]+>', '', text)))
                return '<h2 id="' + ident + '">' + text + '</h2>'
            content = re.sub(r'<h2>(.*?)</h2>', anchor, content, flags=re.S)
            store_url = values['photoStoreUrl'] if route == 'privacy/photo-cleaner.html' else values['securityStoreUrl'] if route == 'privacy/security-antivirus.html' else ''
            store_link = '<a class="text-link" href="' + store_url + '" target="_blank" rel="noopener noreferrer">View on Google Play {{icon:external}}</a>' if store_url else ''
            heading = title.split(' | ')[0]
            is_security = 'security-antivirus' in route
            meta = 'Published app document' if is_security else 'Effective October 31, 2026'
            nav = ''.join('<a href="#' + ident + '">' + label + '</a>' for ident, label in headings)
            content = '<section class="page-intro"><div class="container"><div class="breadcrumb"><a href="{{root}}index.html">Home</a>{{icon:arrow}}<a href="{{root}}privacy/index.html">Privacy &amp; terms</a></div><p class="eyebrow">MaxDevLab · Your information</p><h1>' + heading + '</h1><div class="page-meta"><span>' + meta + '</span><span>Contact: {{email}}</span>' + store_link + '</div></div></section><div class="container legal-layout"><aside class="legal-nav" aria-label="On this page"><p>On this page</p>' + nav + '</aside><article class="legal-body">' + content + '</article></div>'
        content = render(content, values)
        values['content'] = content
        output = render(template, values)
        if '{{' in output: raise SystemExit('Unresolved template in ' + route)
        destination = out / route
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(output)
    (out / 'app-ads.txt').write_text('google.com, pub-7579918370231503, DIRECT, f08c47fec0942fa0\n')
    robots = 'User-agent: *\nAllow: /\n'
    sitemap = out / 'sitemap.xml'
    if domain:
        robots += 'Sitemap: ' + domain + '/sitemap.xml\n'
        urls = [domain + '/' + ('' if route == 'index.html' else route) for route, *_ in PAGES if route != '404.html']
        sitemap.write_text('<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n' + '\n'.join('<url><loc>' + html.escape(url) + '</loc></url>' for url in urls) + '\n</urlset>\n')
    elif sitemap.exists():
        sitemap.unlink()
    (out / 'robots.txt').write_text(robots)
    (out / '_headers').write_text('''/*
  X-Content-Type-Options: nosniff
  Referrer-Policy: strict-origin-when-cross-origin
  Permissions-Policy: camera=(), microphone=(), geolocation=()
  Content-Security-Policy: default-src 'self'; img-src 'self' data:; script-src 'self'; style-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self' mailto:
''')
    print(f'Built {len(PAGES)} pages in {out}')
    print('Public domain: ' + (domain or 'not configured; relative links work for preview'))

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--domain', help='Public HTTPS origin for canonical URLs and sitemap')
    build(parser.parse_args().domain)
