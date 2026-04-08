#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import landscape, A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import PageBreak, Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle


ROOT = Path(__file__).resolve().parent
REPORT_DIR = ROOT / "reports"

BASE_PATH = REPORT_DIR / "spike-scale-latest-r135.json"
CONS1_PATH = REPORT_DIR / "spike-scale-consumer1-r135.json"
PART12_PATH = REPORT_DIR / "spike-scale-partitions12-r135.json"
OUT_PATH = REPORT_DIR / "partition-scaling-study-2026-04-08.pdf"


def load(path: Path) -> dict:
    return json.loads(path.read_text())


def metric(data: dict, replicas: int, field: str) -> float | None:
    return data["runs"][f"replicas_{replicas}"]["prometheus_after_test"].get(field)


def phase_metric(data: dict, replicas: int, phase: str, metric_name: str, key: str) -> float | None:
    return (
        data["runs"][f"replicas_{replicas}"]["prometheus_by_phase"]["phases"][phase][metric_name].get(key)
    )


def build_styles():
    styles = getSampleStyleSheet()
    return {
        "title": ParagraphStyle(
            "title",
            parent=styles["Heading1"],
            fontName="Helvetica-Bold",
            fontSize=26,
            leading=30,
            textColor=colors.HexColor("#13253f"),
            alignment=TA_LEFT,
            spaceAfter=8,
        ),
        "subtitle": ParagraphStyle(
            "subtitle",
            parent=styles["BodyText"],
            fontName="Helvetica",
            fontSize=13,
            leading=18,
            textColor=colors.HexColor("#40536b"),
            spaceAfter=12,
        ),
        "h2": ParagraphStyle(
            "h2",
            parent=styles["Heading2"],
            fontName="Helvetica-Bold",
            fontSize=18,
            leading=22,
            textColor=colors.HexColor("#17375e"),
            spaceAfter=8,
        ),
        "body": ParagraphStyle(
            "body",
            parent=styles["BodyText"],
            fontName="Helvetica",
            fontSize=12,
            leading=17,
            textColor=colors.HexColor("#1f2937"),
            spaceAfter=6,
        ),
        "bullet": ParagraphStyle(
            "bullet",
            parent=styles["BodyText"],
            fontName="Helvetica",
            fontSize=12,
            leading=17,
            leftIndent=14,
            bulletIndent=0,
            textColor=colors.HexColor("#1f2937"),
            spaceAfter=4,
        ),
        "small": ParagraphStyle(
            "small",
            parent=styles["BodyText"],
            fontName="Helvetica",
            fontSize=10,
            leading=14,
            textColor=colors.HexColor("#546173"),
            spaceAfter=4,
        ),
    }


def table(data, col_widths=None):
    t = Table(data, colWidths=col_widths, repeatRows=1)
    t.setStyle(
        TableStyle(
            [
                ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#17375e")),
                ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
                ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
                ("FONTNAME", (0, 1), (-1, -1), "Helvetica"),
                ("FONTSIZE", (0, 0), (-1, -1), 10),
                ("LEADING", (0, 0), (-1, -1), 12),
                ("GRID", (0, 0), (-1, -1), 0.5, colors.HexColor("#b9c7d8")),
                ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.HexColor("#f7fbff"), colors.HexColor("#eef5fb")]),
                ("ALIGN", (1, 1), (-1, -1), "CENTER"),
                ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
                ("LEFTPADDING", (0, 0), (-1, -1), 6),
                ("RIGHTPADDING", (0, 0), (-1, -1), 6),
                ("TOPPADDING", (0, 0), (-1, -1), 6),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
            ]
        )
    )
    return t


def build_pdf():
    base = load(BASE_PATH)
    cons1 = load(CONS1_PATH)
    part12 = load(PART12_PATH)
    styles = build_styles()

    story = []

    story.append(Paragraph("Kafka Partition 増設の再試験", styles["title"]))
    story.append(Paragraph("FX Trading Sample / Spike Test / 2026-04-08", styles["subtitle"]))
    story.append(Paragraph("目的: 5 replicas 以上で Kafka lag が増える問題に対し、partition 数の増設が有効かを確認する。", styles["body"]))
    story.append(Spacer(1, 8))
    story.append(Paragraph("前提", styles["h2"]))
    for text in [
        "base: partitions=6, consumersCount=2",
        "cons1: partitions=6, consumersCount=1",
        "part12: partitions=12, consumersCount=1",
        "増設対象: trade / cover / risk / accounting / settlement / notification / compliance",
    ]:
        story.append(Paragraph(text, styles["bullet"], bulletText="•"))

    story.append(PageBreak())

    story.append(Paragraph("Slide 2. 結果サマリー", styles["title"]))
    story.append(Paragraph("Kafka lag max（試験直後 3m スナップショット）", styles["h2"]))
    lag_rows = [["replicas", "base", "cons1", "part12"]]
    for r in (1, 3, 5):
        lag_rows.append(
            [
                str(r),
                str(metric(base, r, "kafka_lag_max")),
                str(metric(cons1, r, "kafka_lag_max")),
                str(metric(part12, r, "kafka_lag_max")),
            ]
        )
    story.append(table(lag_rows, [30 * mm, 45 * mm, 45 * mm, 45 * mm]))
    story.append(Spacer(1, 10))
    story.append(Paragraph("POST 201 rate（試験直後 3m スナップショット）", styles["h2"]))
    rate_rows = [["replicas", "base", "cons1", "part12"]]
    for r in (1, 3, 5):
        rate_rows.append(
            [
                str(r),
                f"{metric(base, r, 'http_req_rate'):.2f}/s",
                f"{metric(cons1, r, 'http_req_rate'):.2f}/s",
                f"{metric(part12, r, 'http_req_rate'):.2f}/s",
            ]
        )
    story.append(table(rate_rows, [30 * mm, 45 * mm, 45 * mm, 45 * mm]))

    story.append(PageBreak())

    story.append(Paragraph("Slide 3. 何が効いたか", styles["title"]))
    story.append(Paragraph("consumersCount=1 の効果", styles["h2"]))
    for text in [
        "r=1: lag 554 -> 302（-45%）",
        "r=3: lag 910 -> 697（-23%）",
        "r=5: lag 1357 -> 1157（-15%）",
        "まず効いたのは partition 増設ではなく、consumer の過剰並列を止めたこと。",
    ]:
        story.append(Paragraph(text, styles["bullet"], bulletText="•"))

    story.append(Spacer(1, 8))
    story.append(Paragraph("partition 12 の効果", styles["h2"]))
    for text in [
        "r=1: lag 302 -> 583（悪化）",
        "r=3: lag 697 -> 825（悪化）",
        "r=5: lag 1157 -> 918（改善）",
        "partition 増設は、十分な consumer / pod 並列があるときだけ効く。",
    ]:
        story.append(Paragraph(text, styles["bullet"], bulletText="•"))

    story.append(PageBreak())

    story.append(Paragraph("Slide 4. レプリカ別の推奨設定", styles["title"]))
    recommendation_rows = [
        ["replicas", "暫定ベスト", "理由"],
        ["1", "partitions=6, consumersCount=1", "lag 最小。12 partitions は管理コストが先に出る"],
        ["3", "partitions=6, consumersCount=1", "最もバランスが良い。完了レートも維持"],
        ["5", "partitions=12, consumersCount=1", "6 partitions より改善。ただし lag はまだ高い"],
    ]
    story.append(table(recommendation_rows, [20 * mm, 70 * mm, 130 * mm]))
    story.append(Spacer(1, 12))
    story.append(Paragraph("現時点の判断", styles["h2"]))
    for text in [
        "標準運用の基本線は 3 replicas。",
        "5 replicas を使うなら 12 partitions が前提。",
        "ただし 5 replicas でも lag は 918 なので、次の論点は Saga / consumer 責務分割。",
    ]:
        story.append(Paragraph(text, styles["bullet"], bulletText="•"))

    story.append(PageBreak())

    story.append(Paragraph("Slide 5. 問題点と解決策", styles["title"]))
    problem_rows = [
        ["問題", "観測", "解決策"],
        ["過剰 consumer", "6 partitions に対し consumersCount=2 は 5 pods 時に 10 consumer/group", "consumersCount=1 へ削減"],
        ["5 replicas 時の lag", "6 partitions では 1157、12 partitions で 918", "高頻度 topic の partitions を 12 へ増設"],
        ["設定の非対称性", "1/3 と 5 で最適値が違う", "運用ガイドを replicas 別に分ける"],
        ["残る構造課題", "5 replicas でも lag が高い", "trade-saga-service の責務分割、将来的には shard / domain split"],
    ]
    story.append(table(problem_rows, [45 * mm, 65 * mm, 110 * mm]))
    story.append(Spacer(1, 12))
    story.append(Paragraph("結論", styles["h2"]))
    story.append(
        Paragraph(
            "Pod を増やせば自動で速くなるわけではない。まず consumer 並列を適正化し、その上で必要な replicas にだけ partition を増やすのが有効。",
            styles["body"],
        )
    )

    story.append(PageBreak())

    story.append(Paragraph("Slide 6. 参照データ", styles["title"]))
    for text in [
        f"base: {BASE_PATH.name}",
        f"cons1: {CONS1_PATH.name}",
        f"part12: {PART12_PATH.name}",
        "補足レポート: partition-scaling-study-2026-04-08.md",
    ]:
        story.append(Paragraph(text, styles["bullet"], bulletText="•"))
    story.append(Spacer(1, 10))
    story.append(Paragraph("注記", styles["h2"]))
    story.append(
        Paragraph(
            "本 PDF は OpenShift 上の実測値から自動生成した簡易スライドです。観測値は試行時刻とクラスタ状態に依存します。",
            styles["body"],
        )
    )

    doc = SimpleDocTemplate(
        str(OUT_PATH),
        pagesize=landscape(A4),
        leftMargin=14 * mm,
        rightMargin=14 * mm,
        topMargin=12 * mm,
        bottomMargin=12 * mm,
    )
    doc.build(story)
    print(OUT_PATH)


if __name__ == "__main__":
    build_pdf()
