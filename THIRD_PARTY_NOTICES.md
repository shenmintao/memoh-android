# Third-party materials

The Memoh logo is from the official repository:
https://github.com/memohai/Memoh/blob/67fe0e6/apps/web/src/assets/logo.svg

Reference commit: `67fe0e6` (retrieved 2026-09-05). UI submodule reference: `8cd8237aa13a1a6f50f0abbe029d90729a279695`.

The original SVG path data and brand colors are preserved in `ic_memoh.xml`.
Launcher artwork adds safe-zone scaling; the Android notification icon uses the same paths as a monochrome silhouette.
The upstream license is included at `licenses/Memoh-AGPL-3.0.txt`.

Native Compose layout references:

- `apps/web/src/pages/main-section/components/mobile-top-bar.vue`
- `apps/web/src/pages/main-section/components/mobile-nav-sheet.vue`
- `apps/web/src/components/mobile-bar/index.vue`
- `apps/web/src/composables/useIsMobile.ts` (768 px shell breakpoint, mapped to dp)
- `apps/web/src/pages/login/index.vue`
- `apps/web/src/pages/home/components/composer-capsule.vue`
- `apps/web/src/pages/home/components/chat-pane.vue`
- `apps/web/src/style.css` and `packages/ui/src/style.css` (default theme tokens)

This is a separately implemented native Android client. The Vue application and its server are not embedded. Existing client limitations (attachments, files/terminal/browser panels, management, interactive user-input answers, history paging) are listed in README.md. The complete source for this Android build accompanies the APK.
