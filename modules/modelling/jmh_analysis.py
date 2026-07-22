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


def main():
    raw = parse_results(RESULTS_JSON)

    # 提取 JMH 元信息（取第一条）
    meta = raw[0] if raw else {}
    jmh_version = meta.get('jmhVersion', '未知')
    jvm = meta.get('jvm', '未知')
    jdk = meta.get('jdkVersion', '未知')
    warmup = f"{meta.get('warmupIterations', '?')} x {meta.get('warmupTime', '?')}"
    measurement = f"{meta.get('measurementIterations', '?')} x {meta.get('measurementTime', '?')}"

    metrics = {}
    for item in raw:
        name = short_name(item['benchmark'])
        pm = item['primaryMetric']
        metrics[name] = {
            'score': pm['score'],
            'error': pm['scoreError'],
            'unit': pm['scoreUnit'],
            'benchmark': item['benchmark'],
        }

    mux_tick = metrics['MultiplexerBenchmark.perTick']
    mux_event = metrics['MultiplexerBenchmark.onEvent']
    fsm_tick = metrics['StateMachineBenchmark.perTick']
    fsm_event = metrics['StateMachineBenchmark.onEvent']

    # 汇总文本（中文）
    summary_lines = [
        '======== JMH 基准测试结果 ========',
        f'JMH 版本: {jmh_version}',
        f'JVM: {jvm}',
        f'JDK: {jdk}',
        f'Warmup: {warmup}',
        f'Measurement: {measurement}',
        '',
        '基准测试耗时（ns/op）：',
        f'  Multiplexer.perTick   = {mux_tick["score"]:.3f} ± {mux_tick["error"]:.3f}',
        f'  Multiplexer.onEvent   = {mux_event["score"]:.3f} ± {mux_event["error"]:.3f}',
        f'  StateMachine.perTick  = {fsm_tick["score"]:.3f} ± {fsm_tick["error"]:.3f}',
        f'  StateMachine.onEvent  = {fsm_event["score"]:.3f} ± {fsm_event["error"]:.3f}',
        '',
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
    ]
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

    # 多子图中文图表（百分比 of 50 ms tick 预算，更直观）
    INSTANCES_CHART = [100, 1_000, 10_000, 100_000, 1_000_000]
    x = np.arange(len(INSTANCES_CHART))
    width = 0.35

    fig, axes = plt.subplots(1, 3, figsize=(16, 6), sharey=True)
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

        # 在柱顶加数值标签
        ax.bar_label(bars1, labels=[pct_label(v) for v in mux_pct], padding=2, fontsize=8)
        ax.bar_label(bars2, labels=[pct_label(v) for v in fsm_pct], padding=2, fontsize=8)

        if ax == axes[0]:
            ax.legend(fontsize=9)

    fig.suptitle('Multiplexer / StateMachine 对 TPS/MSPT 的影响（基于 JMH）', fontsize=15, y=1.02)

    # 底部 JMH 信息
    env_text = (
        f'JMH {jmh_version}  |  {jvm}  |  JDK {jdk}  |  Warmup {warmup}  |  Measurement {measurement}\n'
        'Multiplexer.perTick = {0:.3f}±{1:.3f} ns/op  |  Multiplexer.onEvent = {2:.3f}±{3:.3f} ns/op\n'
        'StateMachine.perTick = {4:.3f}±{5:.3f} ns/op  |  StateMachine.onEvent = {6:.3f}±{7:.3f} ns/op\n'
        '假设：每 tick/事件调用一次；未计入其它游戏逻辑开销'
    ).format(
        mux_tick['score'], mux_tick['error'],
        mux_event['score'], mux_event['error'],
        fsm_tick['score'], fsm_tick['error'],
        fsm_event['score'], fsm_event['error'],
    )
    fig.text(0.5, -0.06, env_text, ha='center', va='top', fontsize=9,
             bbox=dict(boxstyle='round,pad=0.5', facecolor='white', alpha=0.9, edgecolor='gray'))

    fig.tight_layout(rect=[0, 0.06, 1, 1])
    fig.savefig(OUT_DIR / 'tps_mspt_subplots.png', dpi=150, bbox_inches='tight')
    print(f'\n多子图图表已保存至 {OUT_DIR / "tps_mspt_subplots.png"}')
    print(f'汇总文本已保存至 {OUT_DIR / "tps_mspt.txt"}')


if __name__ == '__main__':
    main()
