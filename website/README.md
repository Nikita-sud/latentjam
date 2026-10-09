# LatentJam website

Static project website published with GitHub Pages at https://latentjam.com/.
The page uses local CSS, JavaScript, fonts, and selected public app demo media.

## Preview locally

From the repository root:

```sh
python3 tools/build_site.py
python3 -m http.server 4173 --bind 127.0.0.1 --directory build/site
```

Open http://127.0.0.1:4173/. Rebuild after changing the source.

## Publish

In GitHub, set Settings → Pages → Source to GitHub Actions.
Run Actions → Publish website → Run workflow on main.
Publication is manual; there is no push or scheduled trigger.
Only the generated build/site folder is uploaded. Media are selected explicitly
in tools/build_site.py from docs/media and branding/logo.svg.

Configure latentjam.com as the custom domain in Settings → Pages and point its
web DNS records at GitHub Pages. Email Routing records are separate.

## Assets and content

- Alsina Ultrajada is bundled with its original font license in fonts/.
- Screenshots show the Android app with a fictional demo library.
- Installation links point to F-Droid and GitHub Releases.
- iOS builds require sideloading and signing with an Apple ID.
- The privacy statement describes the app; GitHub hosts this website.
- Contact: founder@latentjam.com.
