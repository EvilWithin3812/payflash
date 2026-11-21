"""Generates monitoring/grafana/dashboards/payflash.json (run: python monitoring/build_dashboard.py).

Kept as code so the dashboard is reviewable in a diff and easy to extend.
"""
import json
import pathlib

PANELS = [
    # (title, unit, [(expr, legend)], kind)
    ("Requests / s by service", "reqps",
     [("sum by (application) (rate(http_server_requests_seconds_count[1m]))", "{{application}}")], "ts"),
    ("p95 latency by service", "s",
     [("histogram_quantile(0.95, sum by (le, application) (rate(http_server_requests_seconds_bucket[1m])))",
       "{{application}}")], "ts"),
    ("5xx responses / s", "reqps",
     [("sum by (application) (rate(http_server_requests_seconds_count{status=~\"5..\"}[1m]))", "{{application}}")],
     "ts"),
    ("Flash-sale reservations by outcome", "ops",
     [("sum by (outcome) (rate(payflash_sale_reservations_total[1m]))", "{{outcome}}")], "ts"),
    ("Sale payments and expired holds", "ops",
     [("sum(rate(payflash_sale_payments_total[1m]))", "paid"),
      ("sum(rate(payflash_sale_expired_holds_total[1m]))", "expired holds")], "ts"),
    ("Wallet transactions by type", "ops",
     [("sum by (type) (rate(payflash_wallet_transactions_total[1m]))", "{{type}}")], "ts"),
    ("Outbox backlog (unpublished events)", "short",
     [("max(payflash_outbox_pending)", "pending")], "ts"),
    ("Kafka consumer lag (max records)", "short",
     [("max by (application) (kafka_consumer_fetch_manager_records_lag_max)", "{{application}}")], "ts"),
    ("Emails sent by transaction type", "ops",
     [("sum by (type) (rate(payflash_notifications_sent_total[1m]))", "{{type}}")], "ts"),
    ("Rate-limit rejections by policy", "ops",
     [("sum by (policy) (rate(payflash_ratelimit_rejected_total[1m]))", "{{policy}}")], "ts"),
    ("Open circuit breakers (gateway)", "short",
     [("sum by (name) (resilience4j_circuitbreaker_state{state=\"open\"})", "{{name}}")], "ts"),
    ("DB connections in use", "short",
     [("sum by (application) (hikaricp_connections_active)", "{{application}}")], "ts"),
    ("JVM heap used", "bytes",
     [("sum by (application) (jvm_memory_used_bytes{area=\"heap\"})", "{{application}}")], "ts"),
    ("JVM threads (live)", "short",
     [("sum by (application) (jvm_threads_live_threads)", "{{application}}")], "ts"),
]


def panel(i, title, unit, targets):
    return {
        "id": i + 1,
        "type": "timeseries",
        "title": title,
        "datasource": {"type": "prometheus", "uid": "prometheus"},
        "gridPos": {"h": 8, "w": 12, "x": (i % 2) * 12, "y": (i // 2) * 8},
        "fieldConfig": {
            "defaults": {
                "unit": unit,
                "custom": {"lineWidth": 2, "fillOpacity": 10, "showPoints": "never"},
            },
            "overrides": [],
        },
        "options": {
            "legend": {"displayMode": "list", "placement": "bottom"},
            "tooltip": {"mode": "multi", "sort": "desc"},
        },
        "targets": [
            {"refId": chr(65 + n), "expr": expr, "legendFormat": legend,
             "datasource": {"type": "prometheus", "uid": "prometheus"}}
            for n, (expr, legend) in enumerate(targets)
        ],
    }


dashboard = {
    "uid": "payflash-overview",
    "title": "PayFlash overview",
    "tags": ["payflash"],
    "timezone": "browser",
    "refresh": "5s",
    "time": {"from": "now-15m", "to": "now"},
    "schemaVersion": 39,
    "editable": True,
    "panels": [panel(i, t, u, tg) for i, (t, u, tg, _) in enumerate(PANELS)],
}

out = pathlib.Path(__file__).parent / "grafana" / "dashboards" / "payflash.json"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(dashboard, indent=2) + "\n", encoding="utf-8")
print(f"wrote {out} with {len(PANELS)} panels")
