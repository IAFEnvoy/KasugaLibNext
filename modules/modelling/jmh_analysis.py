#!/usr/bin/env python3
import json
from pathlib import Path
import matplotlib.pyplot as plt
import matplotlib as mpl
import numpy as np

# 中文显示支持
mpl.rcParams['font.sans-serif'] = ['Arial Unicode MS', 'STHeiti', 'DejaVu Sans']
mpl.rcParams['axes.unicode_minus'] = False

ROOT = Path(__file__).parent
RESULTS_JSON = ROOT / 'build' / 'reports' / 'jmh' / 'results.json'
OUT_DIR = ROOT / 'build' / 'reports' / 'jmh'
OUT_DIR.mkdir(parents=True, exist_ok=True)

TPS = 20
BUDGET_MS = 1000.0 / TPS  # 每 tick 50 ms 预算


def parse_results(path: Path) -> list[dict]:
    with open(path, 'r') as f:
        return json.load(f)


def short_name(benchmark: str) -> str:
    parts = benchmark.split('.')
    return f'{parts[-2]}.{parts[-1]}'


def mspt_per_tick(ns_per_op: float, instances: np.ndarray) -> np.ndarray:
    """每 tick 都调用时的 MSPT（毫秒）。"""
    return ns_per_op * instances / 1_000_000.0


def mspt_event(ns_per_op: float, instances: np.ndarray, events_per_second: float) -> np.ndarray:
    """每秒 events_per_second 次事件，分摊到 20 tick 的 MSPT（毫秒）。"""
    return ns_per_op * instances * events_per_second / (TPS * 1_000_000.0)


def max_instances_per_tick(ns_per_op: float) -> int:
    if ns_per_op <= 0:
        return 0
    return int(BUDGET_MS * 1_000_000.0 / ns_per_op)


def max_instances_event(ns_per_op: float, events_per_second: float) -> int:
    if ns_per_op <= 0 or events_per_second <= 0:
        return 0
    return int(BUDGET_MS * TPS * 1_000_000.0 / (ns_per_op * events_per_second))


def extract_metrics(raw: list[dict]) -> dict:
    metrics = {}
    for item in raw:
        name = short_name(item['benchmark'])
        pm = item['primaryMetric']
        pct = pm.get('scorePercentiles', {})
        metrics[name] = {
            'benchmark': item['benchmark'],
            'score': pm['score'],
            'error': pm['scoreError'],
            'ci_low': pm['scoreConfidence'][0],
            'ci_high': pm['scoreConfidence'][1],
            'unit': pm['scoreUnit'],
            'min': float(pct.get('0.0', pm['score'])),
            'p50': float(pct.get('50.0', pm['score'])),
            'p90': float(pct.get('90.0', pm['score'])),
            'p99': float(pct.get('99.0', pm['score'])),
            'max': float(pct.get('100.0', pm['score'])),
            'raw': [v for fork in pm.get('rawData', []) for v in fork],
        }
    return metrics


def main():
    raw = parse_results(RESULTS_JSON)

    # JMH 环境信息
    meta = raw[0] if raw else {}
    jmh_version = meta.get('jmhVersion', '未知')
    jvm = meta.get('jvm', '未知')
    jdk = meta.get('jdkVersion', '未知')
    warmup = f"{meta.get('warmupIterations', '?')} x {meta.get('warmupTime', '?')}"
    measurement = f"{meta.get('measurementIterations', '?')} x {meta.get('measurementTime', '?')}"

    metrics = extract_metrics(raw)

    mux_tick = metrics['MultiplexerBenchmark.perTick']
    mux_event = metrics['MultiplexerBenchmark.onEvent']
    fsm_tick = metrics['StateMachineBenchmark.perTick']
    fsm_event = metrics['StateMachineBenchmark.onEvent']

    # 文本汇总（中文）
    summary_lines = [
        '======== JMH 基准测试结果 ========',
        f'JMH 版本: {jmh_version}',
        f'JVM: {jvm}',
        f'JDK: {jdk}',
        f'Warmup: {warmup}',
        f'Measurement: {measurement}',
        '模式: AverageTime（充分 Warmup 后，每次调用的平均耗时）',
        '',
    ]
    for name, info in [
        ('Multiplexer.perTick', mux_tick),
        ('Multiplexer.onEvent', mux_event),
        ('StateMachine.perTick', fsm_tick),
        ('StateMachine.onEvent', fsm_event),
    ]:
        summary_lines.extend([
            f'[{name}]',
            f'  score ± error: {info["score"]:.3f} ± {info["error"]:.3f} {info["unit"]}',
            f'  99.9% 置信区间: [{info["ci_low"]:.3f}, {info["ci_high"]:.3f}] {info["unit"]}',
            f'  分位数: min={info["min"]:.3f}, p50={info["p50"]:.3f}, p90={info["p90"]:.3f}, p99={info["p99"]:.3f}, max={info["max"]:.3f}',
            f'  原始测量值（每次迭代）: {" ".join(f"{v:.3f}" for v in info["raw"])}',
            '',
        ])

    summary_lines.extend([
        f'游戏 TPS = {TPS}，每 tick 预算 = {BUDGET_MS:.1f} ms',
        '',
        '50 ms 预算下最大安全实例数：',
        f'  Multiplexer.perTick       = {max_instances_per_tick(mux_tick["score"]):,}',
        f'  StateMachine.perTick      = {max_instances_per_tick(fsm_tick["score"]):,}',
        f'  Multiplexer.onEvent@1/s   = {max_instances_event(mux_event["score"], 1.0):,}',
        f'  StateMachine.onEvent@1/s  = {max_instances_event(fsm_event["score"], 1.0):,}',
        f'  Multiplexer.onEvent@10/s  = {max_instances_event(mux_event["score"], 10.0):,}',
        f'  StateMachine.onEvent@10/s = {max_instances_event(fsm_event["score"], 10.0):,}',
        '',
        '不同实例数下的 MSPT 增长：',
        f'{"实例数":>12} | Multiplexer.perTick | Multiplexer.onEvent@1/s | StateMachine.perTick | StateMachine.onEvent@1/s',
    ])
    for n in [100, 1000, 10000, 100000, 500000, 1000000]:
        mt = mspt_per_tick(mux_tick['score'], np.array([n]))[0]
        me1 = mspt_event(mux_event['score'], np.array([n]), 1.0)[0]
        ft = mspt_per_tick(fsm_tick['score'], np.array([n]))[0]
        fe1 = mspt_event(fsm_event['score'], np.array([n]), 1.0)[0]
        summary_lines.append(
            f'{n:>12,} | {mt:18.3f} ms | {me1:22.3f} ms | {ft:19.3f} ms | {fe1:23.3f} ms'
        )
    summary = '\n'.join(summary_lines)
    print(summary)
    (OUT_DIR / 'tps_mspt.txt').write_text(summary)

    # 多子图中文图表（上方三个场景，下方 JMH 指标表）
    INSTANCES_CHART = [100, 1_000, 10_000, 100_000, 1_000_000]
    x = np.arange(len(INSTANCES_CHART))
    width = 0.35

    fig = plt.figure(figsize=(16, 10))
    gs = fig.add_gridspec(2, 3, height_ratios=[3, 1.5], hspace=0.28, wspace=0.25)

    axes = [fig.add_subplot(gs[0, i]) for i in range(3)]
    ax_table = fig.add_subplot(gs[1, :])

    scenarios = [
        ('每 tick 调用', 0.0, 'per_tick'),
        ('事件 1/s', 1.0, 'event'),
        ('事件 10/s', 10.0, 'event'),
    ]

    def pct_label(p: float) -> str:
        if p < 0.01:
            return '<0.01%'
        return f'{p:.2f}%'

    for ax, (title, events, mode) in zip(axes, scenarios):
        if mode == 'per_tick':
            mux_vals = mspt_per_tick(mux_tick['score'], np.array(INSTANCES_CHART))
            fsm_vals = mspt_per_tick(fsm_tick['score'], np.array(INSTANCES_CHART))
        else:
            mux_vals = mspt_event(mux_event['score'], np.array(INSTANCES_CHART), events)
            fsm_vals = mspt_event(fsm_event['score'], np.array(INSTANCES_CHART), events)

        mux_pct = mux_vals / BUDGET_MS * 100.0
        fsm_pct = fsm_vals / BUDGET_MS * 100.0

        bars1 = ax.bar(x - width / 2, mux_pct, width, label='Multiplexer', color='#1f77b4')
        bars2 = ax.bar(x + width / 2, fsm_pct, width, label='StateMachine', color='#ff7f0e')

        ax.axhline(100.0, color='red', linestyle='--', linewidth=1.2, label='50 ms 预算线')
        ax.set_ylim(0, 120)
        ax.set_xlabel('实例数', fontsize=11)
        ax.set_ylabel('占 50 ms 预算百分比（%）', fontsize=11)
        ax.set_title(title, fontsize=13)
        ax.set_xticks(x)
        ax.set_xticklabels([f'{n:,}' for n in INSTANCES_CHART], rotation=30, ha='right')
        ax.grid(axis='y', linestyle='--', alpha=0.4)

        ax.bar_label(bars1, labels=[pct_label(v) for v in mux_pct], padding=2, fontsize=8)
        ax.bar_label(bars2, labels=[pct_label(v) for v in fsm_pct], padding=2, fontsize=8)

        if ax == axes[0]:
            ax.legend(fontsize=9)

    fig.suptitle('Multiplexer / StateMachine 对 TPS/MSPT 的影响（充分 Warmup 后的 JMH 平均耗时）', fontsize=15, y=0.98)

    # 底部 JMH 指标表
    table_data = []
    for key in ['MultiplexerBenchmark.perTick', 'MultiplexerBenchmark.onEvent',
                'StateMachineBenchmark.perTick', 'StateMachineBenchmark.onEvent']:
        info = metrics[key]
        short = key.replace('Benchmark.', '.')
        table_data.append([
            short,
            f'{info["score"]:.3f}',
            f'{info["error"]:.3f}',
            f'{info["min"]:.3f}',
            f'{info["p50"]:.3f}',
            f'{info["p90"]:.3f}',
            f'{info["p99"]:.3f}',
            f'{info["max"]:.3f}',
            f'{info["ci_low"]:.3f} ~ {info["ci_high"]:.3f}',
        ])
    col_labels = ['基准测试', 'score\n(ns/op)', 'error\n(ns/op)', 'min', 'p50', 'p90', 'p99', 'max', '99.9% CI\n(ns/op)']

    ax_table.axis('off')
    ax_table.set_title(
        f'JMH 环境：{jmh_version}  |  {jvm}  |  JDK {jdk}  |  Warmup {warmup}  |  Measurement {measurement}',
        fontsize=10, pad=10
    )
    table = ax_table.table(
        cellText=table_data,
        colLabels=col_labels,
        loc='center',
        cellLoc='center',
        colColours=['#eeeeee'] * len(col_labels),
    )
    table.auto_set_font_size(False)
    table.set_fontsize(8)
    table.scale(1.15, 1.8)

    fig.savefig(OUT_DIR / 'tps_mspt_subplots.png', dpi=150, bbox_inches='tight')
    print(f'\n多子图图表已保存至 {OUT_DIR / "tps_mspt_subplots.png"}')
    print(f'汇总文本已保存至 {OUT_DIR / "tps_mspt.txt"}')


if __name__ == '__main__':
    main()
