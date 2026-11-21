# PayFlash web

React 19 + TypeScript + Vite + Tailwind CSS 4 frontend for PayFlash. See the [main README](../README.md).

```bash
npm install
npm run dev     # http://localhost:5173, proxies /api and /ws to http://localhost:8090
npm run build   # type-check + production build into dist/
npm run lint
```

In Docker (`docker compose up -d --build` from the repo root) it is built and served by nginx on http://localhost:3000.
