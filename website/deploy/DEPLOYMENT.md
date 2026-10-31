# Production deployment

Deployed October 31, 2026 (Asia/Shanghai).

- Website: https://www.maxdevlab.com/
- Photo privacy: https://www.maxdevlab.com/privacy/photo-cleaner.html
- Security privacy: https://www.maxdevlab.com/privacy/security-antivirus.html
- Support: https://www.maxdevlab.com/support/index.html
- Ads declaration: https://www.maxdevlab.com/app-ads.txt
- Server: `45.79.40.29`
- Base directory: `/home/www/websites/www.maxdevlab.com`
- Published release: `releases/20261031-02`; `current` symlink selects the release.
- Nginx config: `/etc/nginx/conf.d/www.maxdevlab.com.conf`
- Pre-deployment configuration backup: `/root/deployment-backups/www.maxdevlab.com-20261031`
- Dedicated access/error logs: `/var/log/nginx/www.maxdevlab.com_{access,error}.log`

## HTTPS

Independent Let's Encrypt certificate, valid through January 29, 2027, 19:47:02 Asia/Shanghai. HTTP redirects to HTTPS except the ACME challenge path. Certificate private key remains on the server and is not included in this project.

Renewal uses Certbot's webroot authenticator at `/home/www/websites/www.maxdevlab.com/acme`, with `nginx -t && systemctl reload nginx` as the certificate-specific deployment hook. The existing Certbot timer handles scheduling. A renewal simulation limited to this certificate passed:

```sh
certbot renew --cert-name www.maxdevlab.com --dry-run --non-interactive
```

The server's existing OpenSSL 1.0.2k negotiates TLS 1.2. During browser verification, explicitly disabling session tickets in this new SNI site caused a Chrome `ERR_SSL_PROTOCOL_ERROR` (unexpected TLS record). Removing that per-site override restored compatibility; existing host configurations were not changed. TLS 1.3 requires a future server TLS-library upgrade. This deployment does not upgrade shared packages.

## Verification

- Authoritative DNS plus Cloudflare and Google public DNS returned `45.79.40.29`.
- Normal Chrome DNS resolution and certificate verification passed; no ignored certificate errors or host mapping in the final browser check.
- All 10 content pages returned 200; images decoded; mobile menu worked; no horizontal overflow on the checked mobile privacy page; no JavaScript or CSP console errors.
- `app-ads.txt`, `robots.txt`, `sitemap.xml` returned 200; missing paths returned a branded 404.
- HTTP redirects preserve the requested path.
- Existing 16 Nginx configuration file hashes unchanged; HTTP status/redirect results for 15 existing hostnames matched their pre-deployment results. This is a configuration and availability comparison, not a full functional test of every existing application.
- Nginx configuration validation and graceful reload passed; new-site error log was checked.
- Evidence: `before-http.json`, `after-http.json`, `before-nginx.sha256`, `after-nginx.sha256`, `live-verification.json`, and live screenshots.

## Future updates and rollback

Build locally, upload only `website/dist` content to a new directory under `releases/`, and point `current` to the new version with an atomic symlink replacement. Keep the previous release for rollback. Do not upload source, deployment evidence, Android files or signing material. The `_headers` file is for other static hosts; its headers are implemented in this Nginx configuration instead.

If disabling this newly introduced site is necessary, move only its Nginx configuration outside `conf.d`, validate Nginx, then reload. Do not restore the entire configuration backup over unrelated changes. A file-only update needs no shared-service restart.

The old `maxdevlab.com` and `antivirus.maxdevlab.com` routing and app backend remain unchanged. Existing renewal failures for other domains were not changed by this deployment. Android app links and Google Play Console settings were not modified.

## Google Play entry update — October 31, 2026

Release `20261031-02` adds direct Google Play links for both apps on the homepage, product pages and app-specific privacy headers. Photo Cleaner no longer hides the store button behind a Coming soon flag. Store availability itself could not be verified from this environment; link visibility does not assert a worldwide public release. The previous release `20261031-01` is retained. Only the `current` symlink was switched; no Nginx reload or other-site change was needed.
